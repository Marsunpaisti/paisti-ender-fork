# World Persistence Debug Overlay Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Ctrl+Shift-gated DevTools overlay that visualizes persisted world-persistence terrain half-cells around the player.

**Architecture:** Put testable coordinate and flag-selection logic in a small package-private helper, then keep the overlay itself as thin rendering glue. Register the overlay from `DevToolsPlugin` through the existing Paisti overlay manager.

**Tech Stack:** Java, JUnit 5, existing `paisti.plugin.overlay.MapOverlay`, existing `WorldPersistence`/`WorldMap`, Ant unit/build targets.

**Commit Note:** This repository requires explicit user approval before creating commits. Do not run `git commit` while executing this plan unless the user explicitly asks.

---

## File Structure

- Create `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCells.java`: package-private helper for mapping world tiles to persisted half-cells and choosing debug colors.
- Create `test/unit/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCellsTest.java`: unit tests for half-cell mapping and interesting-flag filtering.
- Create `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceOverlay.java`: DevTools map overlay that queries persisted flags and draws projected translucent quads.
- Modify `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java`: instantiate and register the new overlay.
- Create `test/unit/paisti/plugin/DevToolsPlugin/DevToolsPluginTest.java`: unit test proving DevTools startup registers the new map overlay.

---

### Task 1: Half-Cell Mapping Helper

**Files:**
- Create: `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCells.java`
- Create: `test/unit/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCellsTest.java`

- [ ] **Step 1: Write the failing helper tests**

Create `test/unit/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCellsTest.java` with this content:

```java
package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.world.WorldMapConstants;

import java.awt.Color;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsWorldPersistenceCellsTest {
    @Test
    @Tag("unit")
    void gridOriginTileMapsToFirstFourHalfCells() {
        Coord gridCoord = Coord.of(3, -2);
        Coord tile = gridCoord.mul(MCache.cmaps);

        List<DevToolsWorldPersistenceCells.CellSample> cells = DevToolsWorldPersistenceCells.cellsForTile(tile);

        assertEquals(4, cells.size());
        assertCell(cells.get(0), gridCoord, 0, 0, tile.mul(MCache.tilesz));
        assertCell(cells.get(1), gridCoord, 1, 0, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, 0));
        assertCell(cells.get(2), gridCoord, 0, 1, tile.mul(MCache.tilesz).add(0, MCache.tilesz.y / 2.0));
        assertCell(cells.get(3), gridCoord, 1, 1, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0));
    }

    @Test
    @Tag("unit")
    void lastTileInGridMapsToLastFourHalfCells() {
        Coord gridCoord = Coord.of(-2, 4);
        Coord tile = gridCoord.mul(MCache.cmaps).add(99, 99);

        List<DevToolsWorldPersistenceCells.CellSample> cells = DevToolsWorldPersistenceCells.cellsForTile(tile);

        assertCell(cells.get(0), gridCoord, 198, 198, tile.mul(MCache.tilesz));
        assertCell(cells.get(1), gridCoord, 199, 198, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, 0));
        assertCell(cells.get(2), gridCoord, 198, 199, tile.mul(MCache.tilesz).add(0, MCache.tilesz.y / 2.0));
        assertCell(cells.get(3), gridCoord, 199, 199, tile.mul(MCache.tilesz).add(MCache.tilesz.x / 2.0, MCache.tilesz.y / 2.0));
    }

    @Test
    @Tag("unit")
    void interestingFlagsExcludePassableAndInvalidSentinel() {
        assertFalse(DevToolsWorldPersistenceCells.isInterestingFlags(0));
        assertFalse(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.INVALID_CELL_FLAGS));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_BLOCKED_TERRAIN));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_DEEP_WATER));
        assertTrue(DevToolsWorldPersistenceCells.isInterestingFlags(WorldMapConstants.CELL_OBSERVED));
    }

    @Test
    @Tag("unit")
    void colorPriorityUsesDeepWaterBeforeBlockedBeforeObserved() {
        assertEquals(new Color(40, 110, 255, 100), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_BLOCKED_TERRAIN | WorldMapConstants.CELL_DEEP_WATER));
        assertEquals(new Color(255, 50, 50, 100), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_BLOCKED_TERRAIN));
        assertEquals(new Color(255, 190, 30, 90), DevToolsWorldPersistenceCells.colorForFlags(
            WorldMapConstants.CELL_OBSERVED));
    }

    private static void assertCell(DevToolsWorldPersistenceCells.CellSample cell, Coord gridCoord, int cellX, int cellY, Coord2d worldOrigin) {
        assertEquals(gridCoord, cell.gridCoord);
        assertEquals(cellX, cell.localCellX);
        assertEquals(cellY, cell.localCellY);
        assertEquals(worldOrigin, cell.worldOrigin);
    }
}
```

- [ ] **Step 2: Run the helper test and verify RED**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected result:

```text
BUILD FAILED
```

Expected failure reason: compilation fails because `DevToolsWorldPersistenceCells` does not exist.

- [ ] **Step 3: Implement the minimal helper**

Create `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceCells.java` with this content:

```java
package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.MCache;
import paisti.world.WorldMapConstants;

import java.awt.Color;
import java.util.ArrayList;
import java.util.List;

final class DevToolsWorldPersistenceCells {
    static final Color DEEP_WATER_COLOR = new Color(40, 110, 255, 100);
    static final Color BLOCKED_TERRAIN_COLOR = new Color(255, 50, 50, 100);
    static final Color OBSERVED_COLOR = new Color(255, 190, 30, 90);

    private DevToolsWorldPersistenceCells() {
    }

    static List<CellSample> cellsForTile(Coord tile) {
        Coord gridCoord = tile.div(MCache.cmaps);
        Coord localTile = tile.sub(gridCoord.mul(MCache.cmaps));
        List<CellSample> cells = new ArrayList<>(4);
        cells.add(cell(gridCoord, localTile, tile, 0, 0));
        cells.add(cell(gridCoord, localTile, tile, 1, 0));
        cells.add(cell(gridCoord, localTile, tile, 0, 1));
        cells.add(cell(gridCoord, localTile, tile, 1, 1));
        return cells;
    }

    static boolean isInterestingFlags(int flags) {
        return (flags != 0) && (flags != WorldMapConstants.INVALID_CELL_FLAGS);
    }

    static Color colorForFlags(int flags) {
        if((flags & WorldMapConstants.CELL_DEEP_WATER) != 0)
            return DEEP_WATER_COLOR;
        if((flags & WorldMapConstants.CELL_BLOCKED_TERRAIN) != 0)
            return BLOCKED_TERRAIN_COLOR;
        if((flags & WorldMapConstants.CELL_OBSERVED) != 0)
            return OBSERVED_COLOR;
        return null;
    }

    static Coord2d cellSize() {
        return MCache.tilesz.div(2.0);
    }

    private static CellSample cell(Coord gridCoord, Coord localTile, Coord tile, int halfX, int halfY) {
        Coord2d tileOrigin = tile.mul(MCache.tilesz);
        Coord2d cellOrigin = tileOrigin.add(MCache.tilesz.x * 0.5 * halfX, MCache.tilesz.y * 0.5 * halfY);
        return new CellSample(gridCoord, (localTile.x * 2) + halfX, (localTile.y * 2) + halfY, cellOrigin);
    }

    static final class CellSample {
        final Coord gridCoord;
        final int localCellX;
        final int localCellY;
        final Coord2d worldOrigin;

        private CellSample(Coord gridCoord, int localCellX, int localCellY, Coord2d worldOrigin) {
            this.gridCoord = gridCoord;
            this.localCellX = localCellX;
            this.localCellY = localCellY;
            this.worldOrigin = worldOrigin;
        }
    }
}
```

- [ ] **Step 4: Run the helper test and verify GREEN**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 5: Check worktree without committing**

Run:

```powershell
rtk git status --short
```

Expected result includes the new helper and test files. Do not commit unless the user explicitly asks.

---

### Task 2: DevTools Overlay Registration And Rendering

**Files:**
- Create: `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceOverlay.java`
- Modify: `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java`
- Create: `test/unit/paisti/plugin/DevToolsPlugin/DevToolsPluginTest.java`

- [ ] **Step 1: Write the failing registration test**

Create `test/unit/paisti/plugin/DevToolsPlugin/DevToolsPluginTest.java` with this content:

```java
package paisti.plugin.DevToolsPlugin;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.client.PaistiServices;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsPluginTest {
    @Test
    @Tag("unit")
    void startupRegistersWorldPersistenceOverlay() {
        PaistiServices services = new PaistiServices();
        DevToolsPlugin plugin = new DevToolsPlugin(services);

        plugin.startUp();

        try {
            assertTrue(
                services.overlayManager().mapOverlays().stream().anyMatch(overlay -> overlay instanceof DevToolsWorldPersistenceOverlay),
                "expected DevTools startup to register the world persistence overlay"
            );
        } finally {
            plugin.shutDown();
        }
    }
}
```

- [ ] **Step 2: Run the registration test and verify RED**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected result:

```text
BUILD FAILED
```

Expected failure reason: compilation fails because `DevToolsWorldPersistenceOverlay` does not exist.

- [ ] **Step 3: Implement the overlay class**

Create `src/paisti/plugin/DevToolsPlugin/DevToolsWorldPersistenceOverlay.java` with this content:

```java
package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord2d;
import haven.Coord3f;
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

        Coord centerTile = new Coord2d(player.getc()).floor(MCache.tilesz);
        Coord startTile = centerTile.sub(VIEW_TILE_DIAMETER / 2, VIEW_TILE_DIAMETER / 2);
        for(int tileY = 0; tileY < VIEW_TILE_DIAMETER; tileY++) {
            for(int tileX = 0; tileX < VIEW_TILE_DIAMETER; tileX++) {
                renderTile(ctx, persistence, startTile.add(tileX, tileY));
            }
        }
        ctx.g().chcolor();
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
```

- [ ] **Step 4: Register the overlay from DevToolsPlugin**

Modify `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java` so the fields and `startUp()` method include the new overlay:

```java
public class DevToolsPlugin extends PaistiPlugin {
    private final DevToolsPluginSceneOverlay sceneOverlay = new DevToolsPluginSceneOverlay();
    private final DevToolsPluginScreenOverlay screenOverlay = new DevToolsPluginScreenOverlay();
    private final DevToolsPlayerCoordsOverlay coordsOverlay = new DevToolsPlayerCoordsOverlay();
    private final DevToolsWorldPersistenceOverlay worldPersistenceOverlay = new DevToolsWorldPersistenceOverlay();
    EventBus.Subscriber outgoingWidgetMessageSubscriber;
```

```java
    @Override
    public void startUp() {
        outgoingWidgetMessageSubscriber = eventBus().register(BeforeOutgoingWidgetMessage.class, this::logOutgoingWidgetMessage, 0);
        overlayManager().register(this, sceneOverlay);
        overlayManager().register(this, screenOverlay);
        overlayManager().register(this, coordsOverlay);
        overlayManager().register(this, worldPersistenceOverlay);
    }
```

- [ ] **Step 5: Run the registration test and verify GREEN**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 6: Build the distributable client**

Run:

```powershell
ant bin -buildfile build.xml
```

Expected result:

```text
BUILD SUCCESSFUL
```

- [ ] **Step 7: Check worktree without committing**

Run:

```powershell
rtk git status --short
```

Expected result includes the overlay, helper, tests, `DevToolsPlugin.java`, and the design/plan docs. Do not commit unless the user explicitly asks.

---

## Manual Verification

- Enable the DevTools plugin.
- Enter the game with world persistence initialized.
- Hold `Ctrl+Shift` near recorded terrain.
- Confirm non-passable persisted cells render around the player.
- Confirm normal play without `Ctrl+Shift` does not show the overlay.
- Confirm passable terrain cells are not drawn.
- Confirm drawn cells sit above terrain rather than clipping into it.

## Self-Review Notes

- Spec coverage: coordinate mapping, flag filtering, Ctrl+Shift gating, DevTools registration, projected quads, and build verification all have explicit tasks.
- Placeholder scan: no `TBD`, no incomplete sections, and no generic edge-case instructions remain.
- Type consistency: helper names used in tests and overlay match the implementation snippets: `DevToolsWorldPersistenceCells`, `CellSample`, `cellsForTile`, `isInterestingFlags`, `colorForFlags`, and `cellSize`.
