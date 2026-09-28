package haven;

/**
 * Run-time switches for the render optimisations that first shipped in nova-2026.09.28, so each
 * can be turned off in a running client and the frame rate compared, without a rebuild or a
 * restart. {@code :perflog test} (LeakDbg) flips them one at a time; {@code :renderopt} sets them
 * by hand. All on by default, and not saved - a restart puts them back.
 *
 * Why they exist: a friend's client fell to 11 FPS some time after a hearth home, with the main
 * thread 96% idle waiting on the render thread and the GPU three frames behind, and a restart
 * cleared it. Nothing on the CPU side scaled with it, which leaves what the GPU is asked to do -
 * and these are the changes to that since the last release that played well.
 */
public final class RenderOpts {
    /* MapView.drawsmap: draw the shadow map every other frame while its camera holds still. */
    public static volatile boolean shadowSkip = true;
    /* Light.compile, Lighting.LightGrid and MapView.amblight: hand back the light state built last
     * time while the lights are unchanged, rather than a new one every frame. */
    public static volatile boolean lightCache = true;
    /* FrustumList.visbatch: leave out an instanced batch (one map grid's) whose box is off screen. */
    public static volatile boolean batchCull = true;

    private RenderOpts() {}

    public static String state() {
        return ("shadowSkip=" + shadowSkip + " lightCache=" + lightCache + " batchCull=" + batchCull);
    }

    /* Set one by name; false if there is no such switch. */
    public static boolean set(String name, boolean on) {
        switch (name.toLowerCase()) {
            case "shadowskip": shadowSkip = on; return (true);
            case "lightcache": lightCache = on; return (true);
            case "batchcull": batchCull = on; return (true);
            default: return (false);
        }
    }

    static {
        Console.setscmd("renderopt", (cons, args) -> {
            if ((args.length >= 3) && set(args[1], Utils.parsebool(args[2], true))) {
                cons.out.print("renderopt " + state() + "\n");
            } else {
                cons.out.print("renderopt " + state() + "  - usage: :renderopt <shadowSkip|lightCache|batchCull> <on|off>\n");
            }
            cons.out.flush();
        });
    }
}
