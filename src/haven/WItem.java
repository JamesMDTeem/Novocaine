/*
 *  This file is part of the Haven & Hearth game client.
 *  Copyright (C) 2009 Fredrik Tolf <fredrik@dolda2000.com>, and
 *                     Björn Johannessen <johannessen.bjorn@gmail.com>
 *
 *  Redistribution and/or modification of this file is subject to the
 *  terms of the GNU Lesser General Public License, version 3, as
 *  published by the Free Software Foundation.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  Other parts of this source tree adhere to other copying
 *  rights. Please see the file `COPYING' in the root directory of the
 *  source tree for details.
 *
 *  A copy the GNU Lesser General Public License is distributed along
 *  with the source tree of which this file is a part in the file
 *  `doc/LPGL-3'. If it is missing for any reason, please see the Free
 *  Software Foundation's website at <http://www.fsf.org/>, or write
 *  to the Free Software Foundation, Inc., 59 Temple Place, Suite 330,
 *  Boston, MA 02111-1307 USA
 */

package haven;

import java.awt.*;
import java.util.*;

import haven.automated.AutoRepeatFlowerMenuScript;
import haven.render.*;

import java.awt.image.BufferedImage;
import java.util.List;

import haven.Fuzzy;
import haven.ItemInfo.AttrCache;
import haven.automated.study.StudyHighlight;
import haven.res.ui.stackinv.ItemStack;
import haven.resutil.Curiosity;

import static haven.Inventory.sqsz;

public class WItem extends Widget implements DTarget {
    public static final Resource missing = Resource.local().loadwait("gfx/invobjs/missing");
    public final GItem item;
    private Resource cspr = null;
    private Message csdt = Message.nil;
	private Boolean isNotInStudy = null;
	public final AttrCache<Pair<String, String>> study = new AttrCache<Pair<String, String>>(this::info, AttrCache.map1(Curiosity.class, curio -> curio::remainingTip));
	public static final Text.Foundry studyFnd = new Text.Foundry(Text.sans, 9);;
	private String cachedStudyValue = null;
	private String cachedTipValue = null;
	private Tex cachedStudyTex = null;
	private boolean holdingShift = false;
	private boolean searchItemColorShiftUp = true;
	private int searchItemColorValue = 0;
	public static final Text.Foundry quantityFoundry = new Text.Foundry(Text.dfont, 10);
	private static final Color quantityColor = new Color(255, 255, 255, 255);
	/** The LP Helper's "take this one" ring. Opaque green, drawn over every other overlay. */
	private static final Color studyHighlightColor = new Color(60, 240, 90, 255);
	public static final Coord TEXT_PADD_BOT = new Coord(1, 2);
	public final AttrCache<Tex> heurnum = new AttrCache<Tex>(this::info, AttrCache.cache(info -> {
		String num = ItemInfo.getCount(info);
		if(num == null) return null;
		return new TexI(PUtils.strokeImg(quantityFoundry.renderstroked2(num, quantityColor, Color.BLACK)));
	}));

	public static Color redDurability = new Color(255, 0, 0, 180);
	public static Color orangeDurability = new Color(255, 153, 0, 180);
	public static Color yellowDurability = new Color(255, 234, 0, 180);
	public static Color greenDurability = new Color(0, 255, 4, 180);
	public final AttrCache<Pair<Double, Color>> wear = new AttrCache<>(this::info, AttrCache.cache(info->{
		Pair<Integer, Integer> wear = ItemInfo.getWear(info);
		if(wear == null) return (null);
		double bar = (float) (wear.b - wear.a) / wear.b;
		return new Pair<>(bar, Utils.blendcol(bar, redDurability, orangeDurability, yellowDurability, greenDurability));
	}));

    public WItem(GItem item) {
	super(sqsz);
	this.item = item;
    }

    public void drawmain(GOut g, GSprite spr) {
	spr.draw(g);
    }

    public class ItemTip implements Indir<Tex>, ItemInfo.InfoTip {
	private final List<ItemInfo> info;
	private final TexI tex;

	public ItemTip(List<ItemInfo> info, BufferedImage img) {
	    this.info = info;
	    if(img == null)
		throw(new Loading());
	    tex = new TexI(img);
	}

	public GItem item() {return(item);}
	public List<ItemInfo> info() {return(info);}
	public Tex get() {return(tex);}
    }

    public class ShortTip extends ItemTip {
	public ShortTip(List<ItemInfo> info) {super(info, ItemInfo.shorttip(info));}
    }

    public class LongTip extends ItemTip {
	public LongTip(List<ItemInfo> info) {super(info, ItemInfo.longtip(info));}
    }

    private double hoverstart;
    private ItemTip shorttip = null, longtip = null;
    private List<ItemInfo> ttinfo = null;
    public Object tooltip(Coord c, Widget prev) {
	double now = Utils.rtime();
//	if(prev == this) {
//	} else if(prev instanceof WItem) {
//	    double ps = ((WItem)prev).hoverstart;
//	    if(now - ps < 1.0)
//		hoverstart = now;
//	    else
//		hoverstart = ps;
//	} else {
//	    hoverstart = now;
//	}
	if (prev != this)
		ttinfo = null;
	try {
	    List<ItemInfo> info = item.info();
	    if(info.size() < 1)
		return(null);
	    if(info != ttinfo) {
		shorttip = longtip = null;
		ttinfo = info;
	    }
//	    if(now - hoverstart < 1.0) {
//		if(shorttip == null)
//		    shorttip = new ShortTip(info);
//		return(shorttip);
//	    } else {
		if (ui.modshift && !holdingShift) {
			holdingShift = true;
			longtip = null;
		}
		if (!ui.modshift && holdingShift) {
			holdingShift = false;
			longtip = null;
		}
		if(longtip == null)
		    longtip = new LongTip(info);
		return(longtip);
//	    }
	} catch(Loading e) {
	    return("...");
	}
    }

    public List<ItemInfo> info() {return(item.info());}
    public final AttrCache<Pipe.Op> rstate = new AttrCache<>(this::info, GItem.RStateInfo.combine);
    public AttrCache<GItem.InfoOverlay<?>[]> itemols = new AttrCache<>(this::info, info -> {
	    ArrayList<GItem.InfoOverlay<?>> buf = new ArrayList<>();
	    for(ItemInfo inf : info) {
		if(inf instanceof GItem.OverlayInfo)
		    buf.add(GItem.InfoOverlay.create((GItem.OverlayInfo<?>)inf));
	    }
	    GItem.InfoOverlay<?>[] ret = buf.toArray(new GItem.InfoOverlay<?>[0]);
	    return(() -> ret);
	});
    public final AttrCache<Double> itemmeter = new AttrCache<>(this::info, AttrCache.map1(GItem.MeterInfo.class, minf -> minf::meter));

    private Widget contparent() {
	/* XXX: This is a bit weird, but I'm not sure what the alternative is... */
	Widget cont = getparent(GameUI.class);
	return((cont == null) ? cont = ui.root : cont);
    }

    private GSprite lspr = null;
    private Widget lcont = null;
    public void tick(double dt) {
	/* XXX: This is ugly and there should be a better way to
	 * ensure the resizing happens as it should, but I can't think
	 * of one yet. */
	GSprite spr = item.spr();
	if((spr != null) && (spr != lspr)) {
	    Coord sz = spr.sz();
	    resize(Coord.of(sqsz.x * ((sz.x + sqsz.x / 2) / sqsz.x),
			    sqsz.y * ((sz.y + sqsz.y / 2) / sqsz.y)));
	    lspr = spr;
	}
	if (isNotInStudy == null)
		isNotInStudy = parentWindow() != null && !parentWindow().cap.equals("Character Sheet");
    }

    public void draw(GOut g) {
	GSprite spr = item.spr();
	if(spr != null) {
	    Coord sz = spr.sz();
	    g.defstate();
	    if(rstate.get() != null)
		g.usestate(rstate.get());
		String itemName = item.getname().toLowerCase();
		String searchKeyword = InventorySearchWindow.inventorySearchString.toLowerCase();
		if (searchKeyword.length() > 1) {
			if (Fuzzy.fuzzyContains(itemName, searchKeyword)) {
				int fps = UILoop.fps > 0 ? UILoop.fps : 1;
				int colorShiftSpeed = 800/fps;
				if (searchItemColorShiftUp) {
					if (searchItemColorValue + colorShiftSpeed <= 255) {
						searchItemColorValue += colorShiftSpeed;
					} else {
						searchItemColorShiftUp = false;
						searchItemColorValue = 255;
					}
				} else {
					if (searchItemColorValue - colorShiftSpeed >= 0){
						searchItemColorValue -= colorShiftSpeed;
					} else {
						searchItemColorShiftUp = true;
						searchItemColorValue = 0;
					}
				}
				g.usestate(new ColorMask(new Color(searchItemColorValue, searchItemColorValue, searchItemColorValue, searchItemColorValue)));
			}
		} else {
			if(olcol.get() != null){
				g.usestate(new ColorMask(olcol.get()));
			}
		}
	    drawmain(g, spr);
	    g.defstate();
	    GItem.InfoOverlay<?>[] ols = itemols.get();
	    if(ols != null) {
		for (int i = ols.length - 1; i >= 0; i--) { // ND: Reversed the order in which overlays are drawn, so the quality stays above the level bar (container liquid meter)
			GItem.InfoOverlay<?> overlay = ols[i];
			overlay.draw(g);
		}
	    }
		try {
			for (ItemInfo info : item.info()) {
				if (info instanceof ItemInfo.AdHoc) {
					ItemInfo.AdHoc ah = (ItemInfo.AdHoc) info;
					if (ah.str.text.equals("Well mined")) {
						drawwellmined(g);
					} else if (ah.str.text.equals("Black-truffled")) {
						drawadhocicon(g, "gfx/invobjs/herbs/truffle-black", 18, 0);
					} else if (ah.str.text.equals("White-truffled")) {
						drawadhocicon(g, "gfx/invobjs/herbs/truffle-white", 9, 0);
					} else if (ah.str.text.equals("Peppered")) {
						drawadhocicon(g, "gfx/invobjs/pepper", 0, 0);
					} else if (ah.str.text.equals("Salted")) {
						drawadhocicon(g, "gfx/invobjs/salt", 18, 9);
					}
				}
			}
		} catch (Exception e) {
		}
		drawnum(g, sz);
		if (isNotInStudy != null && isNotInStudy)
			drawCircleProgress(g, sz);
		else
			drawTimeProgress(g, sz);
		if (item.stackQualityTex != null && OptWnd.showQualityDisplayCheckBox.a) {
			g.aimage(item.stackQualityTex, new Coord(g.sz().x, 0), 0.95, 0.2);
		}
		drawDurabilityBars(g, sz);
		drawStudyHighlight(g, sz);
	} else {
	    g.image(missing.layer(Resource.imgc).tex(), Coord.z, sz);
	}
    }

	/**
	 * Rings the curiosities the LP Helper's plan wants taken, so they can be found by eye across a
	 * row of open chests instead of by reading names off the plan and hunting for them.
	 *
	 * Drawn last, over every other overlay, because being coverable by a quality number or a
	 * durability bar would defeat the point. A frame rather than a tint (as the inventory search
	 * uses) so the item art stays readable and the two highlights cannot be mistaken for each other.
	 *
	 * Nothing is highlighted unless a study plan is on screen, and the check behind
	 * {@link StudyHighlight#idle()} is one volatile read in that case -- this runs for every item in
	 * every open container on every frame.
	 */
	private void drawStudyHighlight(GOut g, Coord sz) {
		if (StudyHighlight.idle())
			return;
		/* A curiosity already in the study grid is not one to go and fetch. The plan is built for
		 * an empty grid, so ringing what is already in it reads as "take this" about the one thing
		 * that is already taken. */
		if (parent instanceof StudyInventory)
			return;
		try {
			String name;
			if (item.contents instanceof ItemStack) {
				/* A stack is homogeneous, and its wrapper is named "<item>, stack of" rather than
				 * the thing it holds, so the name has to come from a member. */
				ItemStack stack = (ItemStack) item.contents;
				name = stack.order.isEmpty() ? null : stack.order.get(0).getname();
			} else {
				name = item.getname();
			}
			if (!StudyHighlight.wants(name))
				return;
			g.chcolor(studyHighlightColor);
			g.rect(new Coord(1, 1), sz.sub(2, 2));
			g.rect(new Coord(2, 2), sz.sub(4, 4));
			g.chcolor();
		} catch (Loading l) {
			/* Still arriving; it will be ringed on a later frame. */
		} catch (RuntimeException e) {
			/* A broken resource must not take the whole inventory's drawing down with it. */
		}
	}

    public boolean mousedown(MouseDownEvent ev) {
	boolean inv = Inventory.fromWidget(parent) != null;
	if(ev.b == 1) {
		if (OptWnd.useImprovedInventoryTransferControlsCheckBox.a && ui.modmeta && !ui.modctrl) {
			if (inv) {
				wdgmsg("transfer-ordered", item, false);
				return true;
			}
		}
	    if(ui.modshift) {
		int n = ui.modctrl ? -1 : 1;
		item.wdgmsg("transfer", ev.c, n);
	    } else if(ui.modctrl) {
		int n = ui.modmeta ? -1 : 1;
		item.wdgmsg("drop", ev.c, n);
	    } else {
		item.wdgmsg("take", ev.c);
	    }
	    return(true);
	} else if(ev.b == 3) {
		if (OptWnd.useImprovedInventoryTransferControlsCheckBox.a && ui.modmeta && !ui.modctrl) {
			if (inv) {
				wdgmsg("transfer-ordered", item, true);
				return true;
			}
		}
		if (ui.modctrl && OptWnd.autoSelect1stFlowerMenuCheckBox.a && !ui.modshift && !ui.modmeta) {
			String itemname = item.getname();
			int option = 0;
			if (itemname.equals("Head of Lettuce")) { // ND: Don't eat it, rather split it.
				option = 1;
			}
			haven.automated.eat.EatObserver.onIact(item);
			item.wdgmsg("iact", ev.c, ui.modflags());
			ui.rcvr.rcvmsg(ui.lastWidgetID + 1, "cl", option, 0);
		}
		if(ui.modctrl && ui.modshift && OptWnd.autoRepeatFlowerMenuCheckBox.a){
			if (!(item != null && item.contents != null)) { // ND: Ctrl+Shift on stack items splits them, so ignore them.
				try {
					if (ui.gui.autoRepeatFlowerMenuScriptThread == null) {
						ui.gui.autoRepeatFlowerMenuScriptThread = new Thread(new AutoRepeatFlowerMenuScript(ui.gui, this.item.getres().name), "autoRepeatFlowerMenu");
						ui.gui.autoRepeatFlowerMenuScriptThread.start();
					} else {
						ui.gui.autoRepeatFlowerMenuScriptThread.interrupt();
						ui.gui.autoRepeatFlowerMenuScriptThread = null;
						ui.gui.autoRepeatFlowerMenuScriptThread = new Thread(new AutoRepeatFlowerMenuScript(ui.gui, this.item.getres().name), "autoRepeatFlowerMenu");
						ui.gui.autoRepeatFlowerMenuScriptThread.start();
					}
				} catch (Loading ignored) {
				}
			}
		}
	    haven.automated.eat.EatObserver.onIact(item);
	    item.wdgmsg("iact", ev.c, ui.modflags());
	    return(true);
	}
	return(super.mousedown(ev));
    }

    public boolean drop(Coord cc, Coord ul) {
	return(false);
    }

    public boolean iteminteract(Coord cc, Coord ul) {
	item.wdgmsg("itemact", ui.modflags());
	return(true);
    }

    public boolean mousehover(MouseHoverEvent ev, boolean on) {
	if(on && (item.contents != null && (!OptWnd.showHoverInventoriesWhenHoldingShiftCheckBox.a || ui.modshift))) {
	    item.hovering(this);
	    return(true);
	}
	return(super.mousehover(ev, on));
    }

	public Window parentWindow() {
		Widget parent = this.parent;
		while (parent != null) {
			if (parent instanceof Window)
				return (Window) parent;
			parent = parent.parent;
		}
		return null;
	}

	public double meter() {
		Double meter = (item.meter > 0) ? (Double) (item.meter / 100.0) : itemmeter.get();
		return meter == null ? 0 : meter;
	}

	private void drawCircleProgress(GOut g, Coord sz) {
		double meter = meter();
		if(meter > 0) {
			g.chcolor(255, 255, 255, 64);
			Coord half = sz.div(2);
			g.prect(half, half.inv(), half, meter * Math.PI * 2);
			g.chcolor();
			Tex tex = Text.renderstroked(String.format("%d%%", Math.round(100 * meter))).tex();
			g.aimage(tex, sz.div(2), 0.5, 0.5);
			tex.dispose();
		}
	}

	private void drawTimeProgress(GOut g, Coord sz) {
		double meter = meter();
		if(meter > 0) {
			Tex studyTime = getStudyTime();
			if(studyTime == null) {
				Tex tex = Text.renderstroked(String.format("%d%%", Math.round(100 * meter))).tex();
				g.aimage(tex, sz.div(2), 0.5, 0.5);
				tex.dispose();
			}
			if(studyTime != null) {
				g.chcolor();
				g.aimage(studyTime, new Coord(sz.x / 2, sz.y), 0.5, 0.9);
			}
		}
	}

	private Tex getStudyTime() {
		Pair<String, String> data = study.get();
		String value = data == null ? null : data.a;
		String tip = data == null ? null : data.b;
		if(!Objects.equals(tip, cachedTipValue)) {
			cachedTipValue = tip;
			longtip = null;
		}
		if(value != null) {
			if(!Objects.equals(value, cachedStudyValue)) {
				if(cachedStudyTex != null) {
					cachedStudyTex.dispose();
					cachedStudyTex = null;
				}
			}

			if(cachedStudyTex == null) {
				cachedStudyValue = value;
				if (!value.contains("h")) // ND: When the curio has less than 1 hour left to study (it only shows the minutes)
					cachedStudyTex = PUtils.strokeTex(Text.renderstroked(value, Color.GREEN, Color.BLACK, studyFnd));
				else
					cachedStudyTex = PUtils.strokeTex(Text.renderstroked(value, Color.WHITE, Color.BLACK, studyFnd));
			}
			return cachedStudyTex;
		}
		return null;
	}

	public void reloadItemOls(){
		itemols = new AttrCache<>(this::info, info -> {
			ArrayList<GItem.InfoOverlay<?>> buf = new ArrayList<>();
			for(ItemInfo inf : info) {
				if(inf instanceof GItem.OverlayInfo)
					buf.add(GItem.InfoOverlay.create((GItem.OverlayInfo<?>)inf));
			}
			GItem.InfoOverlay<?>[] ret = buf.toArray(new GItem.InfoOverlay<?>[0]);
			return(() -> ret);
		});
		if (item != null && item.parent != null) {
			if (item.parent instanceof ItemStack) {
				ItemStack itemStack = (ItemStack) item.parent;
				if (itemStack.parent != null) {
					GItem stackItem = ((GItem.ContentsWindow) itemStack.parent).cont;
					if (stackItem != null) {
						stackItem.stackQualityTex = null;
						itemStack.stackQualityNeedsUpdate = true;
					}
				}
			}
		}
	}

	private void drawDurabilityBars(GOut g, Coord sz) {
		if(true) {
			Pair<Double, Color> wear = this.wear.get();
			if(wear != null) {
				int h = (int) (sz.y * wear.a);
				g.chcolor(Color.BLACK);
				g.frect(new Coord(UI.scale(1), 0), new Coord(UI.scale(5), sz.y));
				g.chcolor(wear.b);
				g.frect(new Coord(UI.scale(2), sz.y - h + UI.scale(1)), new Coord(UI.scale(3), h - UI.scale(2)));
				g.chcolor();
			}
		}
	}

	public final AttrCache<Color> olcol = new AttrCache<>(this::info, info -> {
		ArrayList<GItem.ColorInfo> ols = new ArrayList<>();
		for(ItemInfo inf : info) {
			if(inf instanceof GItem.ColorInfo)
				ols.add((GItem.ColorInfo)inf);
		}
		if(ols.size() == 0)
			return(() -> null);
		if(ols.size() == 1)
			return(ols.get(0)::olcol);
		ols.trimToSize();
		return(() -> {
			Color ret = null;
			for(GItem.ColorInfo ci : ols) {
				Color c = ci.olcol();
				if(c != null)
					ret = (ret == null) ? c : Utils.preblend(ret, c);
			}
			return(ret);
		});
	});

	private void drawwellmined(GOut g) {
		g.chcolor(new Color(203, 183, 94));
		g.fcircle(sz.x-UI.scale(4),sz.y-UI.scale(4), UI.scale(4),10);
		g.chcolor();
	}

	/**
	 * Seasoning icons, uploaded once each instead of once per item per frame.
	 *
	 * g.image(BufferedImage, ...) is not a cheap call: it builds a TexI, uploads it, draws it and
	 * disposes it, every time. There are only four of these icons in the whole client, so a
	 * handful of entries covers them for the session.
	 */
	private static final TexCache<String> adhocIcons = new TexCache<>(64, resname -> {
		Resource res = Resource.remote().load(resname).get();
		return new TexI(res.layer(Resource.imgc).img);
	});

	private void drawadhocicon(GOut g, String resname, int offsetX, int offsetY) {
		// Still inside the caller's try/catch: a resource that has not finished loading throws
		// Loading out of here, nothing is cached, and the next frame asks again.
		Tex tex = adhocIcons.get(resname);
		if(tex != null)
			g.image(tex, new Coord(UI.scale(offsetX), sz.y-UI.scale(16+offsetY)), new Coord(UI.scale(16),UI.scale(16)));
	}

	/**
	 * Stack-count textures, rendered once per distinct count rather than once per frame.
	 *
	 * quantityFoundry and quantityColor are static and the string is the whole of the input, so
	 * one texture per number serves every item in every open container. Rendering it inline -
	 * which is what this used to do - cost two BufferedImages and one undisposed GL texture per
	 * countable item per frame; with a cellar open that measured ~50 texture uploads a frame and
	 * took the client from 128 fps to 30. Note that the item.num < 0 branch below already went
	 * through a cache (heurnum); only this one did not.
	 */
	private static final TexCache<Integer> quantityTex =
		new TexCache<>(4096, num -> PUtils.strokeTex(
			quantityFoundry.renderstroked2(Integer.toString(num), quantityColor, Color.BLACK)));

	private void drawnum(GOut g, Coord sz) {
		Tex tex;
		if(item.num >= 0) {
			tex = quantityTex.get(item.num);
		} else {
			tex = chainattr(heurnum);
		}

		if(tex != null) {
			g.aimage(tex, TEXT_PADD_BOT.add(sz), 1, 1);
		}
	}

	@SafeVarargs //Ender: actually, method just assumes you'll feed it correctly typed var args
	private static Tex chainattr(AttrCache<Tex> ...attrs){
		for(AttrCache<Tex> attr : attrs){
			Tex tex = attr.get();
			if(tex != null){
				return tex;
			}
		}
		return null;
	}
}
