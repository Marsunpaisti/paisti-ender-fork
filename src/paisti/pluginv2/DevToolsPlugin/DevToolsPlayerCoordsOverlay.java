package paisti.pluginv2.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.MiniMap;
import haven.Text;
import haven.Tex;
import haven.TexI;
import haven.UI;

import java.util.concurrent.locks.Lock;
import paisti.pluginv2.overlay.ScreenOverlay;
import paisti.pluginv2.overlay.ScreenOverlayContext;

import java.awt.Color;
import java.awt.Font;

/**
 * POC screen overlay: shows the local player's world position,
 * grid coordinate, grid ID, and map segment ID in the top-right
 * corner of the screen, always visible during gameplay.
 */
public class DevToolsPlayerCoordsOverlay implements ScreenOverlay {
    private static final int MARGIN = UI.scale(8);
    private static final int PAD = UI.scale(4);
    private static final int LINE_SPACING = UI.scale(2);
    private static final Color TEXT_COLOR = Color.WHITE;
    private static final Color STROKE_COLOR = Color.BLACK;
    private static final Color BG_COLOR = new Color(0, 0, 0, 160);
    private static final Text.Foundry FND = new Text.Foundry(Text.sans.deriveFont(Font.BOLD), 12).aa(true);

    private String lastPosText;
    private String lastGridText;
    private String lastSegText;
    private Tex posTex;
    private Tex gridTex;
    private Tex segTex;

    @Override
    public String id() {
        return "devtools-player-coords";
    }

    @Override
    public int priority() {
        return 50;
    }

    @Override
    public void render(ScreenOverlayContext ctx) {
        if(ctx.ui() == null || ctx.ui().gui == null) {
            return;
        }
        MapView map = ctx.ui().gui.map;
        if(map == null) {
            return;
        }
        Gob player = map.player();
        if(player == null) {
            return;
        }
        Coord2d rc = player.rc;
        Coord3f pos = player.getc();
        if(rc == null || pos == null) {
            return;
        }

        // Line 1: world position
        String posText = String.format("Pos: %.1f, %.1f", pos.x, pos.y);
        posTex = updateTex(posTex, lastPosText, posText);
        lastPosText = posText;

        // Line 2: grid coordinate + grid ID
        String gridText = resolveGridInfo(ctx, rc);
        gridTex = updateTex(gridTex, lastGridText, gridText);
        lastGridText = gridText;

        // Line 3: segment ID
        String segText = resolveSegInfo(ctx, rc);
        segTex = updateTex(segTex, lastSegText, segText);
        lastSegText = segText;

        // Compute total bounding box for the background
        int maxW = maxWidth(posTex, gridTex, segTex);
        int totalH = totalHeight(posTex, gridTex, segTex);

        Coord screenSz = ctx.size();
        int bgX = screenSz.x - maxW - PAD * 2 - MARGIN;
        int bgY = MARGIN;
        Coord bgUl = Coord.of(bgX, bgY);
        Coord bgSz = Coord.of(maxW + PAD * 2, totalH + PAD * 2);

        // Draw semi-transparent dark background
        GOut g = ctx.g();
        g.chcolor(BG_COLOR);
        g.frect(bgUl, bgSz);
        g.chcolor();

        // Draw text lines right-aligned within the background
        int contentRight = screenSz.x - MARGIN - PAD;
        int y = bgY + PAD;
        y = drawLineRight(g, posTex, contentRight, y);
        y = drawLineRight(g, gridTex, contentRight, y);
        drawLineRight(g, segTex, contentRight, y);
    }

    private String resolveGridInfo(ScreenOverlayContext ctx, Coord2d rc) {
        try {
            Coord tc = rc.floor(MCache.tilesz);
            Coord gc = tc.div(MCache.cmaps);
            MCache mcache = ctx.ui().sess.glob.map;
            MCache.Grid grid = mcache.getgrid(gc);
            return String.format("Grid: (%d, %d)  ID: %d", gc.x, gc.y, grid.id);
        } catch(Loading l) {
            return "Grid: loading...";
        }
    }

    private String resolveSegInfo(ScreenOverlayContext ctx, Coord2d rc) {
        try {
            Coord tc = rc.floor(MCache.tilesz);
            Coord gc = tc.div(MCache.cmaps);
            MCache mcache = ctx.ui().sess.glob.map;
            MCache.Grid grid = mcache.getgrid(gc);

            MiniMap mmap = ctx.ui().gui.mmap;
            if(mmap == null || mmap.file == null) {
                return "Seg: n/a";
            }
            MapFile mapFile = mmap.file;
            Lock readLock = mapFile.lock.readLock();
            if(!readLock.tryLock()) {
                return "Seg: ...";
            }
            try {
                MapFile.GridInfo info = mapFile.gridinfo.get(grid.id);
                if(info == null) {
                    return "Seg: unknown";
                }
                return String.format("Seg: %s", Long.toHexString(info.seg));
            } finally {
                readLock.unlock();
            }
        } catch(Loading l) {
            return "Seg: loading...";
        }
    }

    private Tex updateTex(Tex current, String oldText, String newText) {
        if(newText.equals(oldText) && current != null) {
            return current;
        }
        if(current != null) {
            current.dispose();
        }
        return new TexI(FND.renderstroked(newText, TEXT_COLOR, STROKE_COLOR).img);
    }

    private int drawLineRight(GOut g, Tex tex, int rightEdge, int y) {
        if(tex == null) {
            return y;
        }
        Coord sz = tex.sz();
        g.image(tex, Coord.of(rightEdge - sz.x, y));
        return y + sz.y + LINE_SPACING;
    }

    private int maxWidth(Tex... texes) {
        int max = 0;
        for(Tex t : texes) {
            if(t != null) {
                max = Math.max(max, t.sz().x);
            }
        }
        return max;
    }

    private int totalHeight(Tex... texes) {
        int total = 0;
        boolean first = true;
        for(Tex t : texes) {
            if(t != null) {
                if(!first) {
                    total += LINE_SPACING;
                }
                total += t.sz().y;
                first = false;
            }
        }
        return total;
    }

    @Override
    public void dispose() {
        disposeTex(posTex);
        disposeTex(gridTex);
        disposeTex(segTex);
        posTex = null;
        gridTex = null;
        segTex = null;
        lastPosText = null;
        lastGridText = null;
        lastSegText = null;
    }

    private void disposeTex(Tex tex) {
        if(tex != null) {
            tex.dispose();
        }
    }
}
