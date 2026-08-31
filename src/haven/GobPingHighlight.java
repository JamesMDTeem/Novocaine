package haven;

import haven.render.MixColor;
import haven.render.Pipe;

import java.awt.*;

public class GobPingHighlight extends GAttrib implements Gob.SetupMod {
    private final Color c;
    private static final long cycle = 350;
    private static final long duration = 2100;
    private long start = 0;

    public GobPingHighlight(Gob g, Color c) {
        super(g);
        this.c = c;
    }

    public void start() {
        start = System.currentTimeMillis();
        gob.markStateDirty();
    }

    /* The pulse below is a function of the clock, so its gobstate() answer differs on
     * every frame and nothing but the clock changes to say so. Keep the gob dirty for
     * as long as the pulse is running -- one frame past the end, so the final state
     * (null, i.e. no tint) is the one that gets pushed. */
    @Override
    public void ctick(double dt) {
        if(System.currentTimeMillis() - start <= duration + 100)
            gob.markStateDirty();
    }

    public Pipe.Op gobstate() {
        long active = System.currentTimeMillis() - start;
        if(active > duration) {
            return null;
        } else {
            float k = (float) Math.abs(Math.sin(Math.PI * active / cycle));
            return new MixColor(c.getRed(), c.getGreen(), c.getBlue(), (int) (c.getAlpha() * k));
        }
    }
}
