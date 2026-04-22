package paisti.pluginv2.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.Gob;
import haven.Loading;
import haven.MCache;
import haven.MapFile;
import haven.MapView;
import haven.MiniMap;
import haven.Text;
import haven.Tex;
import haven.TexI;
import paisti.pluginv2.overlay.ScreenOverlay;
import paisti.pluginv2.overlay.ScreenOverlayContext;

import java.awt.Color;

/**
 * POC screen overlay: shows the local player's world position,
 * grid coordinate, grid ID, and map segment ID in the top-right
 * corner of the screen, always visible during gameplay.
 */
public class DevToolsPlayerCoordsOverlay implements ScreenOverlay {
    private static final int PADDING = 10;
    private static final int LINE_SPACING = 2;
    private static final Color FG = Color.WHITE;
    private static final Color BG = Color.BLACK;

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

        // Draw right-aligned, stacked from top-right corner
        Coord screenSz = ctx.size();
        int y = PADDING;
        y = drawLineRight(ctx, posTex, screenSz.x, y);
        y = drawLineRight(ctx, gridTex, screenSz.x, y);
        drawLineRight(ctx, segTex, screenSz.x, y);
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
            MapFile.GridInfo info = mmap.file.gridinfo.get(grid.id);
            if(info == null) {
                return "Seg: unknown";
            }
            return String.format("Seg: %s", Long.toHexString(info.seg));
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
        return new TexI(Text.renderstroked(newText, FG, BG).img);
    }

    private int drawLineRight(ScreenOverlayContext ctx, Tex tex, int screenWidth, int y) {
        if(tex == null) {
            return y;
        }
        Coord sz = tex.sz();
        ctx.g().image(tex, Coord.of(screenWidth - sz.x - PADDING, y));
        return y + sz.y + LINE_SPACING;
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
