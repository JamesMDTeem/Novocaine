package haven.automated.lp;

import haven.Coord;
import haven.Drawable;
import haven.Gob;
import haven.ResDrawable;
import haven.TexI;
import haven.UI;


import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Shared icon composition for the LP feature's two displays - the always-on harvest overlay and
 * the LP-discovery marker - so both compose, tint, and cache icons identically and never drift
 * apart. Split out of nurgling2's NObjHarvestOl so LpExplorer can depend on this without a
 * circular dependency on the overlay widget itself.
 */
public class LpIcons {
    // Light tint (icon stays recognizable) marking a still-undiscovered LP product's icon.
    public static final Color LP_UNDISCOVERED_TINT = new Color(0, 255, 0, 110);

    // #252B29 @ 50% - the framed-label background (nurgling2's TooltipStyle.COLOR_OVERLAY_BG).
    public static final Color OVERLAY_BG = new Color(37, 43, 41, 128);

    private static final Map<String, TexI> LABEL_CACHE = new ConcurrentHashMap<>();

    private LpIcons() {}

    public static void clearLabelCache() {
        LABEL_CACHE.clear();
    }

    public static TexI computeLabel(Gob gob, HarvestSpec spec) {
        if (gob == null || spec == null) return null;
        Drawable dr = gob.getattr(Drawable.class);
        ResDrawable d = (dr instanceof ResDrawable) ? (ResDrawable) dr : null;
        if (d == null) return null;
        if (!LpConfig.on(spec.masterToggle()))
            return null;

        List<HarvestSpec.Part> parts = spec.parts(gob, d);
        if (parts.isEmpty())
            return null;

        // Season is part of the key because a species' seed icon itself changes with it (the
        // "Yesteryear's " variant, see HarvestState.getIcon) while every other component of this
        // key stays identical - without it, labels composed before a season boundary would keep
        // rendering the wrong season's fruit until something else happened to clear the cache.
        StringBuilder key = new StringBuilder();
        key.append(d.getres().name).append('_');
        key.append(HarvestState.isYesteryearSeason() ? "y_" : "n_");
        for (HarvestSpec.Part part : parts)
            key.append(part.id).append(part.undiscovered ? "_u_" : "_n_");
        String keyStr = key.toString();

        TexI cached = LABEL_CACHE.get(keyStr);
        if (cached != null)
            return cached;

        TexI tex = compose(spec.horizontal(), parts);
        if (tex != null)
            LABEL_CACHE.put(keyStr, tex);
        return tex;
    }

    // Tints each still-undiscovered part and lays them all out per the given orientation - the
    // shared "turn a list of harvest icons into one presentation-ready image" step.
    public static TexI compose(boolean horizontal, List<HarvestSpec.Part> parts) {
        if (parts.isEmpty())
            return null;
        BufferedImage[] imgs = new BufferedImage[parts.size()];
        for (int i = 0; i < imgs.length; i++) {
            HarvestSpec.Part part = parts.get(i);
            imgs[i] = part.undiscovered ? tint(part.icon, LP_UNDISCOVERED_TINT) : part.icon;
        }
        BufferedImage combined = catimgshCentered(horizontal, 1, imgs);
        return combined != null ? new TexI(combined) : null;
    }

    // Vertical (top-to-bottom) layout - the original/default orientation.
    public static BufferedImage catimgshCentered(int margin, BufferedImage... imgs) {
        return catimgshCentered(false, margin, imgs);
    }

    // horizontal=true lays icons left-to-right (centered vertically) instead of top-to-bottom
    // (centered horizontally) - e.g. a log's Board+Block read better side by side than stacked.
    public static BufferedImage catimgshCentered(boolean horizontal, int margin, BufferedImage... imgs) {
        int cross = 0, along = -margin;
        int n = 0;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            n++;
            int imgCross = horizontal ? img.getHeight() : img.getWidth();
            int imgAlong = horizontal ? img.getWidth() : img.getHeight();
            if (imgCross > cross) cross = imgCross;
            along += imgAlong + margin;
        }
        if (n == 0) return null;
        int pad = UI.scale(3);
        int w = horizontal ? along : cross;
        int h = horizontal ? cross : along;
        BufferedImage ret = TexI.mkbuf(new Coord(w + pad * 2, h + pad * 2));
        Graphics g = ret.getGraphics();
        g.setColor(OVERLAY_BG);
        g.fillRect(0, 0, w + pad * 2, h + pad * 2);
        int pos = pad;
        for (BufferedImage img : imgs) {
            if (img == null) continue;
            int imgCross = horizontal ? img.getHeight() : img.getWidth();
            int imgAlong = horizontal ? img.getWidth() : img.getHeight();
            int crossOff = pad + (cross - imgCross) / 2;
            int x = horizontal ? pos : crossOff;
            int y = horizontal ? crossOff : pos;
            g.drawImage(img, x, y, null);
            pos += imgAlong + margin;
        }
        g.dispose();
        return ret;
    }

    // Tint that preserves the source image's own alpha/silhouette, matching the math
    // haven.ColorMask applies for 3D-rendered sprites (result = base*(1-a) + tint*a).
    public static BufferedImage tint(BufferedImage src, Color color) {
        BufferedImage out = TexI.mkbuf(new Coord(src.getWidth(), src.getHeight()));
        Graphics2D g = (Graphics2D) out.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.setComposite(AlphaComposite.SrcAtop);
        g.setColor(color);
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        g.dispose();
        return out;
    }
}
