package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
import haven.GOut;
import haven.Gob;
import haven.MCache;
import haven.render.Model;
import paisti.client.PGameUI;
import paisti.plugin.overlay.MapOverlay;
import paisti.plugin.overlay.MapScreenOverlayContext;
import paisti.world.WorldPersistence;

import java.awt.Color;
import java.util.List;

final class DevToolsWorldPersistenceOverlay implements MapOverlay {
    private static final int VIEW_TILE_DIAMETER = 50;
    private static final float Z_OFFSET = 2.0f;

    @Override
    public int priority() {
        return 100;
    }

    @Override
    public void renderScreen(MapScreenOverlayContext ctx) {
        if((ctx.ui() == null) || (ctx.map() == null) || !ctx.ui().modctrl || !ctx.ui().modshift)
            return;
        if(!(ctx.gui() instanceof PGameUI))
            return;
        Gob player = ctx.map().player();
        if(player == null)
            return;
        WorldPersistence persistence = ((PGameUI) ctx.gui()).worldPersistence();
        if(persistence == null)
            return;

        withColorReset(ctx.g(), () -> {
            Coord centerTile = new Coord2d(player.getc()).floor(MCache.tilesz);
            Coord startTile = centerTile.sub(VIEW_TILE_DIAMETER / 2, VIEW_TILE_DIAMETER / 2);
            for(int tileY = 0; tileY < VIEW_TILE_DIAMETER; tileY++) {
                for(int tileX = 0; tileX < VIEW_TILE_DIAMETER; tileX++) {
                    renderTile(ctx, persistence, startTile.add(tileX, tileY));
                }
            }
        });
    }

    static void withColorReset(GOut g, Runnable work) {
        try {
            work.run();
        } finally {
            g.chcolor();
        }
    }

    private void renderTile(MapScreenOverlayContext ctx, WorldPersistence persistence, Coord tile) {
        List<DevToolsWorldPersistenceCells.CellSample> cells = DevToolsWorldPersistenceCells.cellsForTile(tile);
        MCache.Grid grid = ctx.map().glob.map.getgrid(cells.get(0).gridCoord);
        for(DevToolsWorldPersistenceCells.CellSample cell : cells) {
            int flags = persistence.worldMap().getCellFlags(grid.id, cell.localCellX, cell.localCellY);
            if(!DevToolsWorldPersistenceCells.isInterestingFlags(flags))
                continue;
            Color color = DevToolsWorldPersistenceCells.colorForFlags(flags);
            if(color != null)
                drawCell(ctx, cell, color);
        }
    }

    private void drawCell(MapScreenOverlayContext ctx, DevToolsWorldPersistenceCells.CellSample cell, Color color) {
        Coord2d size = DevToolsWorldPersistenceCells.cellSize();
        Coord[] projected = new Coord[] {
            project(ctx, cell.worldOrigin),
            project(ctx, cell.worldOrigin.add(size.x, 0)),
            project(ctx, cell.worldOrigin.add(size.x, size.y)),
            project(ctx, cell.worldOrigin.add(0, size.y))
        };
        for(Coord corner : projected) {
            if(corner == null)
                return;
        }
        ctx.g().chcolor(color);
        ctx.g().drawp(Model.Mode.TRIANGLE_FAN, new float[] {
            projected[0].x, projected[0].y,
            projected[1].x, projected[1].y,
            projected[2].x, projected[2].y,
            projected[3].x, projected[3].y
        });
    }

    private Coord project(MapScreenOverlayContext ctx, Coord2d world) {
        Coord3f terrain = ctx.map().glob.map.getzp(world);
        Coord3f raised = Coord3f.of(terrain.x, terrain.y, terrain.z + Z_OFFSET);
        return ctx.worldToScreen(raised);
    }
}
