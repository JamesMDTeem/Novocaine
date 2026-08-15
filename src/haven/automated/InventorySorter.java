package haven.automated;

import haven.Audio;
import haven.Coord;
import haven.Defer;
import haven.GameUI;
import haven.Inventory;
import haven.ItemInfo;
import haven.Loading;
import haven.Resource;
import haven.WItem;
import haven.Widget;
import haven.Window;
import haven.automated.invsort.InvPlan;
import haven.automated.invsort.InvSnapshot;
import haven.res.ui.tt.q.quality.Quality;

import java.util.*;

import static haven.Inventory.sqsz;

/**
 * Sorts inventories by handing the decisions to haven.automated.invsort and doing nothing here but
 * reading the grid and sending what it is told to send.
 *
 * The split is the point. This class used to decide and act in the same pass, mutating its own
 * occupancy model while take/drop messages were already in flight, and every one of its bugs lived
 * at that seam: an item left on the cursor, a spurious "inventory too full", and a chain-swap loop
 * with no bound that flooded the server and took the game down with it. Now the whole sequence is
 * built and validated before a single message goes out - see InvPlan for why a refused move is
 * impossible rather than merely unlikely.
 *
 * The snapshot is taken on the UI thread, in sort()/sortAll(), before the worker starts. The old
 * code walked inv.lchild and read each WItem's position and size from the Defer worker while the UI
 * thread was free to add and remove those same widgets - a race that had nothing to do with the
 * sorting bugs and would have survived fixing them.
 */
public class InventorySorter implements Defer.Callable<Void> {
    private static final String[] EXCLUDE = {
	"Character Sheet", "Study",
	"Chicken Coop", "Belt", "Pouch", "Purse",
	"Cauldron", "Finery Forge", "Fireplace", "Frame",
	"Herbalist Table", "Kiln", "Ore Smelter", "Smith's Smelter",
	"Oven", "Pane mold", "Rack", "Smoke shed",
	"Stack Furnace", "Steelbox", "Tub"
    };

    private static final Comparator<WItem> ITEM_COMPARATOR = Comparator
	.comparing((WItem w) -> w.item.getname())
	.thenComparing(w -> {
	    try { return w.item.res.get().name; } catch (Loading e) { return ""; }
	})
	.thenComparing(w -> {
	    Quality q = ItemInfo.find(Quality.class, w.item.info());
	    return q != null ? q.q : 0.0;
	}, Comparator.reverseOrder());

    /** One inventory, frozen: the plain-number view, and the widgets its piece ids refer back to. */
    private static class Job {
	final Inventory inv;
	final InvSnapshot snap;
	final List<WItem> items;

	Job(Inventory inv, InvSnapshot snap, List<WItem> items) {
	    this.inv = inv;
	    this.snap = snap;
	    this.items = items;
	}
    }

    private static final Object lock = new Object();
    private static InventorySorter current;
    private Defer.Future<Void> task;
    private final List<Job> jobs;
    private final GameUI gui;

    private InventorySorter(List<Job> jobs, GameUI gui) {
	this.jobs = jobs;
	this.gui = gui;
    }

    public static void sort(Inventory inv) {
	GameUI gui = inv.ui.gui;
	if (gui.vhand != null) {
	    gui.error("Need empty cursor to sort inventory!");
	    return;
	}
	Job job = snapshot(inv);
	if (job == null) {
	    gui.error("Inventory is still loading - try again in a moment.");
	    return;
	}
	start(new InventorySorter(Collections.singletonList(job), gui));
    }

    public static void sortAll(GameUI gui) {
	if (gui.vhand != null) {
	    gui.error("Need empty cursor to sort inventory!");
	    return;
	}
	List<Job> targets = new ArrayList<>();
	boolean loading = false;
	for (Inventory inv : gui.ui.root.children(Inventory.class)) {
	    Window wnd = inv.getparent(Window.class);
	    if (wnd != null && isExcluded(wnd.cap)) continue;
	    Job job = snapshot(inv);
	    if (job == null) { loading = true; continue; }
	    targets.add(job);
	}
	if (loading)
	    gui.error("Some inventories were still loading and were skipped.");
	if (!targets.isEmpty())
	    start(new InventorySorter(targets, gui));
    }

    /**
     * Reads an inventory into plain numbers. UI thread only.
     *
     * Returns null if any item's sprite has not arrived. That is stricter than the old code, which
     * skipped such items and carried on - but an item the plan does not know about is an item the
     * plan will happily drop something onto, and every legality guarantee downstream assumes the
     * grid is fully known. Waiting a moment is cheap; a desync is not.
     */
    private static Job snapshot(Inventory inv) {
	if (inv.parent == null || inv.isz == null)
	    return null;
	try {
	    List<WItem> found = new ArrayList<>();
	    for (Widget wdg = inv.lchild; wdg != null; wdg = wdg.prev) {
		if (!wdg.visible || !(wdg instanceof WItem)) continue;
		WItem w = (WItem) wdg;
		if (w.item.spr() == null)
		    return null;
		found.add(w);
	    }
	    found.sort(ITEM_COMPARATOR);

	    boolean[] mask = new boolean[inv.isz.x * inv.isz.y];
	    if (inv.sqmask != null) {
		int mo = 0;
		for (int y = 0; y < inv.isz.y; y++)
		    for (int x = 0; x < inv.isz.x; x++)
			mask[y * inv.isz.x + x] = inv.sqmask[mo++];
	    }

	    List<InvSnapshot.Piece> pieces = new ArrayList<>();
	    for (int id = 0; id < found.size(); id++) {
		WItem w = found.get(id);
		Coord slots = w.sz.div(sqsz);
		Coord at = w.c.sub(1, 1).div(sqsz);
		pieces.add(new InvSnapshot.Piece(id, slots.x, slots.y, at.x, at.y));
	    }
	    return new Job(inv, new InvSnapshot(inv.isz.x, inv.isz.y, mask, pieces), found);
	} catch (Loading l) {
	    return null;
	}
    }

    private static boolean isExcluded(String cap) {
	if (cap == null) return false;
	for (String ex : EXCLUDE) {
	    if (ex.equals(cap)) return true;
	}
	return false;
    }

    @Override
    public Void call() throws InterruptedException {
	boolean movedAnything = false, hadWorkToDo = false;
	for (Job job : jobs) {
	    if (job.inv.parent == null) return null;
	    InvPlan.Plan plan = run(job);
	    if (plan == null) continue;
	    movedAnything |= plan.movesAnything();
	    /* An inventory already in order is not a failure, and saying so was half of the old
	     * sorter's spurious "too full" reports. Only a plan that wanted to move something and
	     * could not is worth a message. */
	    hadWorkToDo |= !plan.pinned.isEmpty();
	}
	synchronized (lock) {
	    if (current == this) current = null;
	}
	gui.ui.sfxrl(sfx_done);
	/* Checked once, after the sound, so the wait costs no time the player notices. Nothing in
	 * the plan can leave an item held; if one is held anyway, something outside the sort moved
	 * the inventory underneath us and the player needs to know rather than discover it later. */
	if (gui.vhand != null)
	    gui.error("Something moved while sorting - an item is still on your cursor.");
	else if (hadWorkToDo && !movedAnything)
	    gui.error("Nothing could be moved - the inventory is too full to rearrange.");
	return null;
    }

    private InvPlan.Plan run(Job job) throws InterruptedException {
	InvPlan.Plan plan;
	try {
	    plan = InvPlan.compute(job.snap);
	} catch (RuntimeException e) {
	    haven.automated.nbots.core.NLog.crash("inventory sort planning", e);
	    gui.error("Could not work out how to sort that (logged to logs/crash.log).");
	    return null;
	}
	for (InvPlan.Op op : plan.ops) {
	    if (op.kind == InvPlan.TAKE) {
		job.items.get(op.piece).item.wdgmsg("take", Coord.z);
		/* Throttling only - nothing waits on this for correctness. Widget messages are
		 * ordered and reliable, so the client never has to see a reply; it only has to
		 * send a sequence the server will accept, which is what InvPlan guarantees. */
		Thread.sleep(10);
	    } else {
		job.inv.wdgmsg("drop", new Coord(op.x, op.y));
	    }
	}
	return plan;
    }

    public static void cancel() {
	synchronized (lock) {
	    if (current != null) {
		current.task.cancel();
		current = null;
	    }
	}
    }

    private static final Audio.Clip sfx_done = Audio.resclip(Resource.remote().loadwait("sfx/hud/on"));

    private static void start(InventorySorter sorter) {
	cancel();
	synchronized (lock) { current = sorter; }
	sorter.task = Defer.later(sorter);
    }
}
