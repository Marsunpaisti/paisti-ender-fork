# Plugin Overlay System Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a plugin-owned overlay system for `PaistiPlugin` with both screen overlays and map-aware overlays, plus a small `DevToolsPlugin` example that proves both render lanes work.

**Architecture:** Add a `paisti.plugin.overlay` package containing the overlay contracts, thin render contexts, a central `OverlayManager`, and a manager-owned `MapOverlayBridge`. The manager lives in the shared `PaistiServices` container, not in one `UI`, so overlay registrations survive UI swaps while rendering and map attachment follow the currently bound UI via `services.ui()`. Logic is covered by Ant-driven JUnit tests under `test/unit`, while final visual behavior is verified manually in the running client.

**Tech Stack:** Java 11, Ant (`build.xml`), Haven `UI`, `MapView`, `PView.Render2D`, `RenderTree`, `Rendered`, shared `PaistiServices`, JUnit 5 under `test/unit` and `test/support`

---

## File Structure

- Create: `src/paisti/pluginv2/overlay/PluginOverlay.java`
  Responsibility: common overlay metadata and cleanup contract.

- Create: `src/paisti/pluginv2/overlay/ScreenOverlay.java`
  Responsibility: 2D post-UI overlay contract.

- Create: `src/paisti/pluginv2/overlay/MapOverlay.java`
  Responsibility: map overlay contract for world and projected screen drawing.

- Create: `src/paisti/pluginv2/overlay/OverlayRegistration.java`
  Responsibility: explicit registration handle for optional close-based unregister.

- Create: `src/paisti/pluginv2/overlay/ScreenOverlayContext.java`
  Responsibility: pass `UI`, `GOut`, mouse, and size to screen overlays.

- Create: `src/paisti/pluginv2/overlay/MapWorldOverlayContext.java`
  Responsibility: pass `UI`, `GameUI`, `MapView`, `Pipe`, and `Render` to world overlays.

- Create: `src/paisti/pluginv2/overlay/MapScreenOverlayContext.java`
  Responsibility: pass `UI`, `GameUI`, `MapView`, `GOut`, `Pipe`, and world-to-screen projection to screen overlays anchored to the map.

- Create: `src/paisti/pluginv2/overlay/OverlayManager.java`
  Responsibility: owner-aware registration, ordering, failure isolation, map attachment, shared-service lifecycle, and rendering entry points.

- Create: `src/paisti/pluginv2/overlay/MapOverlayBridge.java`
  Responsibility: manager-owned `RenderTree.Node`/`Rendered`/`PView.Render2D` bridge attached once per active map.

- Create: `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`
  Responsibility: JUnit coverage for overlay API presence, ordering, owner cleanup, failure disable behavior, and map bridge type contract.

- Modify: `test/unit/haven/PaistiServicesLifetimeTest.java`
  Responsibility: verify overlay manager follows shared-service lifetime semantics such as stop cleanup and UI-swap resilience.

- Modify: `src/haven/PaistiServices.java`
  Responsibility: construct and expose `OverlayManager`, stop it during shared-service shutdown.

- Modify: `src/paisti/pluginv2/PaistiPlugin.java`
  Responsibility: add the `overlayManager()` accessor.

- Modify: `src/haven/UI.java`
  Responsibility: invoke one screen overlay render pass after existing `afterdraws` complete.

- Create: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginSceneOverlay.java`
  Responsibility: minimal map overlay example.

- Create: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginScreenOverlay.java`
  Responsibility: minimal screen overlay example.

- Modify: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`
  Responsibility: register and unregister the example overlays.

## Task 1: Add Overlay API Surface And Shared-Service Accessors

**Files:**
- Create: `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`
- Create: `src/paisti/pluginv2/overlay/PluginOverlay.java`
- Create: `src/paisti/pluginv2/overlay/ScreenOverlay.java`
- Create: `src/paisti/pluginv2/overlay/MapOverlay.java`
- Create: `src/paisti/pluginv2/overlay/OverlayRegistration.java`
- Create: `src/paisti/pluginv2/overlay/ScreenOverlayContext.java`
- Create: `src/paisti/pluginv2/overlay/MapWorldOverlayContext.java`
- Create: `src/paisti/pluginv2/overlay/MapScreenOverlayContext.java`
- Create: `src/paisti/pluginv2/overlay/OverlayManager.java`
- Modify: `src/haven/PaistiServices.java`
- Modify: `src/paisti/pluginv2/PaistiPlugin.java`

- [ ] **Step 1: Write the failing JUnit API test**

Create `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`.

```java
package paisti.plugin.overlay;

import haven.PaistiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.plugin.PaistiPlugin;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class OverlayManagerTest {
    @Test
    @Tag("unit")
    void overlayApiExists() throws Exception {
	assertNotNull(Class.forName("paisti.plugin.overlay.PluginOverlay"));
	assertNotNull(Class.forName("paisti.plugin.overlay.ScreenOverlay"));
	assertNotNull(Class.forName("paisti.plugin.overlay.MapOverlay"));
	assertNotNull(Class.forName("paisti.plugin.overlay.OverlayRegistration"));
	assertNotNull(Class.forName("paisti.plugin.overlay.ScreenOverlayContext"));
	assertNotNull(Class.forName("paisti.plugin.overlay.MapWorldOverlayContext"));
	assertNotNull(Class.forName("paisti.plugin.overlay.MapScreenOverlayContext"));
	assertNotNull(Class.forName("paisti.plugin.overlay.OverlayManager"));
    }

    @Test
    @Tag("unit")
    void servicesExposeOverlayManager() throws Exception {
	Method method = PaistiServices.class.getMethod("overlayManager");
	assertEquals("paisti.plugin.overlay.OverlayManager", method.getReturnType().getName());
    }

    @Test
    @Tag("unit")
    void pluginBaseExposesOverlayManagerAccessor() throws Exception {
	Method method = PaistiPlugin.class.getDeclaredMethod("overlayManager");
	assertEquals("paisti.plugin.overlay.OverlayManager", method.getReturnType().getName());
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: `OverlayManagerTest.overlayApiExists()` fails with `ClassNotFoundException` for the first missing overlay class.

- [ ] **Step 3: Add the overlay contracts and contexts**

Create `src/paisti/pluginv2/overlay/PluginOverlay.java`.

```java
package paisti.plugin.overlay;

public interface PluginOverlay {
    default String id() {
	return getClass().getName();
    }

    default int priority() {
	return 0;
    }

    default boolean enabled() {
	return true;
    }

    default void dispose() {
    }
}
```

Create `src/paisti/pluginv2/overlay/ScreenOverlay.java`.

```java
package paisti.plugin.overlay;

public interface ScreenOverlay extends PluginOverlay {
    void render(ScreenOverlayContext ctx);
}
```

Create `src/paisti/pluginv2/overlay/MapOverlay.java`.

```java
package paisti.plugin.overlay;

public interface MapOverlay extends PluginOverlay {
    default void renderWorld(MapWorldOverlayContext ctx) {
    }

    default void renderScreen(MapScreenOverlayContext ctx) {
    }
}
```

Create `src/paisti/pluginv2/overlay/OverlayRegistration.java`.

```java
package paisti.plugin.overlay;

public final class OverlayRegistration implements AutoCloseable {
    private final OverlayManager manager;
    private final PluginOverlay overlay;

    OverlayRegistration(OverlayManager manager, PluginOverlay overlay) {
	this.manager = manager;
	this.overlay = overlay;
    }

    public PluginOverlay overlay() {
	return overlay;
    }

    @Override
    public void close() {
	manager.unregister(overlay);
    }
}
```

Create `src/paisti/pluginv2/overlay/ScreenOverlayContext.java`.

```java
package paisti.plugin.overlay;

import haven.Coord;
import haven.GOut;
import haven.UI;

public final class ScreenOverlayContext {
    private final UI ui;
    private final GOut g;

    public ScreenOverlayContext(UI ui, GOut g) {
	this.ui = ui;
	this.g = g;
    }

    public UI ui() {return ui;}

    public GOut g() {return g;}

    public Coord mouse() {return (ui == null) ? Coord.z : ui.mc;}

    public Coord size() {return (g == null) ? Coord.z : g.sz();}
}
```

Create `src/paisti/pluginv2/overlay/MapWorldOverlayContext.java`.

```java
package paisti.plugin.overlay;

import haven.GameUI;
import haven.MapView;
import haven.UI;
import haven.render.Pipe;
import haven.render.Render;

public final class MapWorldOverlayContext {
    private final UI ui;
    private final GameUI gui;
    private final MapView map;
    private final Pipe state;
    private final Render out;

    public MapWorldOverlayContext(UI ui, GameUI gui, MapView map, Pipe state, Render out) {
	this.ui = ui;
	this.gui = gui;
	this.map = map;
	this.state = state;
	this.out = out;
    }

    public UI ui() {return ui;}

    public GameUI gui() {return gui;}

    public MapView map() {return map;}

    public Pipe state() {return state;}

    public Render out() {return out;}
}
```

Create `src/paisti/pluginv2/overlay/MapScreenOverlayContext.java`.

```java
package paisti.plugin.overlay;

import haven.Coord;
import haven.Coord3f;
import haven.GOut;
import haven.GameUI;
import haven.MapView;
import haven.UI;
import haven.render.Pipe;

public final class MapScreenOverlayContext {
    private final UI ui;
    private final GameUI gui;
    private final MapView map;
    private final GOut g;
    private final Pipe state;

    public MapScreenOverlayContext(UI ui, GameUI gui, MapView map, GOut g, Pipe state) {
	this.ui = ui;
	this.gui = gui;
	this.map = map;
	this.g = g;
	this.state = state;
    }

    public UI ui() {return ui;}

    public GameUI gui() {return gui;}

    public MapView map() {return map;}

    public GOut g() {return g;}

    public Pipe state() {return state;}

    public Coord worldToScreen(Coord3f world) {
	if(map == null || world == null) {
	    return null;
	}
	return map.screenxf(world).round2();
    }
}
```

- [ ] **Step 4: Add the minimal manager/service accessors around the current shared-service shape**

Create `src/paisti/pluginv2/overlay/OverlayManager.java`.

```java
package paisti.plugin.overlay;

import haven.PaistiServices;
import paisti.plugin.PaistiPlugin;

import java.util.Collection;
import java.util.Collections;

public class OverlayManager {
    private final PaistiServices services;

    public OverlayManager(PaistiServices services) {
	this.services = services;
    }

    public OverlayRegistration register(PaistiPlugin owner, PluginOverlay overlay) {
	return new OverlayRegistration(this, overlay);
    }

    public void unregister(PluginOverlay overlay) {
    }

    public void unregisterAll(PaistiPlugin owner) {
    }

    public Collection<ScreenOverlay> screenOverlays() {
	return Collections.emptyList();
    }

    public Collection<MapOverlay> mapOverlays() {
	return Collections.emptyList();
    }
}
```

Update `src/haven/PaistiServices.java`.

```java
package haven;

import paisti.hooks.EventBus;
import paisti.plugin.PluginService;
import paisti.plugin.overlay.OverlayManager;

public class PaistiServices {
    private volatile UI ui;
    private final EventBus eventBus;
    private final PluginService pluginService;
    private final OverlayManager overlayManager;
    private boolean started;

    public PaistiServices() {
	this.eventBus = new EventBus();
	this.pluginService = new PluginService(this);
	this.overlayManager = new OverlayManager(this);
    }

    public OverlayManager overlayManager() {
	return overlayManager;
    }
}
```

Update `src/paisti/pluginv2/PaistiPlugin.java`.

```java
package paisti.plugin;

import paisti.plugin.overlay.OverlayManager;

public abstract class PaistiPlugin {
    protected OverlayManager overlayManager() {
	return services().overlayManager();
    }
}
```

- [ ] **Step 5: Run tests to verify they pass**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: `BUILD SUCCESSFUL` and `OverlayManagerTest` passes.

- [ ] **Step 6: Commit**

```bash
git add src/haven/PaistiServices.java src/paisti/pluginv2/PaistiPlugin.java src/paisti/pluginv2/overlay test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java
git commit -m "feat: add plugin overlay api"
```

## Task 2: Implement Ordering, Owner Cleanup, And Failure Isolation

**Files:**
- Modify: `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`
- Modify: `src/paisti/pluginv2/overlay/OverlayManager.java`

- [ ] **Step 1: Expand the JUnit coverage for manager behavior**

Update `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`.

```java
package paisti.plugin.overlay;

import haven.GOut;
import haven.PaistiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.plugin.PaistiPlugin;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayManagerTest {
    @Test
    @Tag("unit")
    void unregisterAllDisposesOwnerOverlays() {
	PaistiServices services = new PaistiServices();
	OverlayManager manager = new OverlayManager(services);
	TestPlugin owner = new TestPlugin(services);
	TrackingScreenOverlay overlay = new TrackingScreenOverlay("owner", 0);

	manager.register(owner, overlay);
	manager.unregisterAll(owner);

	assertTrue(overlay.disposed);
	assertTrue(manager.screenOverlays().isEmpty());
    }

    @Test
    @Tag("unit")
    void screenOverlaysRenderInPriorityThenRegistrationOrder() {
	PaistiServices services = new PaistiServices();
	OverlayManager manager = new OverlayManager(services);
	TestPlugin owner = new TestPlugin(services);
	List<String> trace = new ArrayList<>();

	manager.register(owner, new TrackingScreenOverlay("late", 10, trace));
	manager.register(owner, new TrackingScreenOverlay("early-a", 0, trace));
	manager.register(owner, new TrackingScreenOverlay("early-b", 0, trace));

	manager.renderScreenOverlays((GOut) null);

	assertEquals(Arrays.asList("early-a", "early-b", "late"), trace);
    }

    @Test
    @Tag("unit")
    void repeatedScreenFailuresDisableOnlyTheBrokenOverlay() {
	PaistiServices services = new PaistiServices();
	OverlayManager manager = new OverlayManager(services);
	TestPlugin owner = new TestPlugin(services);
	TrackingScreenOverlay healthy = new TrackingScreenOverlay("healthy", 0);
	ThrowingScreenOverlay broken = new ThrowingScreenOverlay();

	manager.register(owner, broken);
	manager.register(owner, healthy);

	for (int i = 0; i < 6; i++) {
	    manager.renderScreenOverlays((GOut) null);
	}

	assertEquals(6, healthy.renders);
	assertEquals(5, broken.renders);
    }

    private static final class TestPlugin extends PaistiPlugin {
	private TestPlugin(PaistiServices services) {
	    super(services);
	}

	@Override
	public String getName() {
	    return "OverlayManagerTestPlugin";
	}

	public void startUp() {
	}

	public void shutDown() {
	}
    }

    private static class TrackingScreenOverlay implements ScreenOverlay {
	private final String name;
	private final int priority;
	private final List<String> trace;
	private boolean disposed;
	private int renders;

	private TrackingScreenOverlay(String name, int priority) {
	    this(name, priority, null);
	}

	private TrackingScreenOverlay(String name, int priority, List<String> trace) {
	    this.name = name;
	    this.priority = priority;
	    this.trace = trace;
	}

	public int priority() {return priority;}

	public void render(ScreenOverlayContext ctx) {
	    renders++;
	    if(trace != null) {
		trace.add(name);
	    }
	}

	public void dispose() {
	    disposed = true;
	}
    }

    private static final class ThrowingScreenOverlay extends TrackingScreenOverlay {
	private ThrowingScreenOverlay() {
	    super("broken", 0);
	}

	@Override
	public void render(ScreenOverlayContext ctx) {
	    renders++;
	    throw new RuntimeException("boom");
	}
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: `OverlayManagerTest` fails because registration, owner cleanup, ordering, and repeated-failure disable logic are not implemented yet.

- [ ] **Step 3: Implement manager state, ordering, and failure handling**

Update `src/paisti/pluginv2/overlay/OverlayManager.java`.

```java
package paisti.plugin.overlay;

import haven.GOut;
import haven.PaistiServices;
import paisti.plugin.PaistiPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class OverlayManager {
    private static final int MAX_CONSECUTIVE_FAILURES = 5;

    private final PaistiServices services;
    private final CopyOnWriteArrayList<RegisteredOverlay> overlays = new CopyOnWriteArrayList<>();
    private long nextOrder = 0;

    public OverlayManager(PaistiServices services) {
	this.services = services;
    }

    public OverlayRegistration register(PaistiPlugin owner, PluginOverlay overlay) {
	if(owner == null || overlay == null) {
	    throw new IllegalArgumentException("overlay owner and overlay must not be null");
	}
	overlays.add(new RegisteredOverlay(owner, overlay, nextOrder++));
	return new OverlayRegistration(this, overlay);
    }

    public void unregister(PluginOverlay overlay) {
	for (RegisteredOverlay registered : overlays) {
	    if(registered.overlay == overlay) {
		overlays.remove(registered);
		disposeQuietly(registered);
	    }
	}
    }

    public void unregisterAll(PaistiPlugin owner) {
	for (RegisteredOverlay registered : overlays) {
	    if(registered.owner == owner) {
		overlays.remove(registered);
		disposeQuietly(registered);
	    }
	}
    }

    public Collection<ScreenOverlay> screenOverlays() {
	List<ScreenOverlay> result = new ArrayList<>();
	for (RegisteredOverlay registered : sorted()) {
	    if((registered.overlay instanceof ScreenOverlay) && !registered.disabled) {
		result.add((ScreenOverlay) registered.overlay);
	    }
	}
	return result;
    }

    public Collection<MapOverlay> mapOverlays() {
	List<MapOverlay> result = new ArrayList<>();
	for (RegisteredOverlay registered : sorted()) {
	    if((registered.overlay instanceof MapOverlay) && !registered.disabled) {
		result.add((MapOverlay) registered.overlay);
	    }
	}
	return result;
    }

    public void renderScreenOverlays(GOut g) {
	ScreenOverlayContext ctx = new ScreenOverlayContext(services.ui(), g);
	for (RegisteredOverlay registered : sorted()) {
	    if(!(registered.overlay instanceof ScreenOverlay)) {
		continue;
	    }
	    if(registered.disabled || !registered.overlay.enabled()) {
		continue;
	    }
	    try {
		((ScreenOverlay) registered.overlay).render(ctx);
		registered.failures = 0;
	    } catch (Throwable t) {
		handleFailure(registered, t);
	    }
	}
    }

    public void stop() {
	for (RegisteredOverlay registered : overlays) {
	    overlays.remove(registered);
	    disposeQuietly(registered);
	}
    }

    private List<RegisteredOverlay> sorted() {
	List<RegisteredOverlay> ordered = new ArrayList<>(overlays);
	ordered.sort(Comparator.comparingInt((RegisteredOverlay registered) -> registered.overlay.priority())
	    .thenComparingLong(registered -> registered.order));
	return ordered;
    }

    private void handleFailure(RegisteredOverlay registered, Throwable t) {
	registered.failures++;
	System.err.println("Overlay failure in plugin " + registered.owner.getName() + " overlay " + registered.overlay.id());
	t.printStackTrace(System.err);
	if(registered.failures >= MAX_CONSECUTIVE_FAILURES) {
	    registered.disabled = true;
	    System.err.println("Overlay disabled after repeated failures: " + registered.overlay.id());
	}
    }

    private void disposeQuietly(RegisteredOverlay registered) {
	try {
	    registered.overlay.dispose();
	} catch (Throwable t) {
	    System.err.println("Overlay dispose failure in plugin " + registered.owner.getName() + " overlay " + registered.overlay.id());
	    t.printStackTrace(System.err);
	}
    }

    private static final class RegisteredOverlay {
	private final PaistiPlugin owner;
	private final PluginOverlay overlay;
	private final long order;
	private int failures;
	private boolean disabled;

	private RegisteredOverlay(PaistiPlugin owner, PluginOverlay overlay, long order) {
	    this.owner = owner;
	    this.overlay = overlay;
	    this.order = order;
	}
    }
}
```

- [ ] **Step 4: Run tests to verify they pass**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: `BUILD SUCCESSFUL` and `OverlayManagerTest` passes.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/pluginv2/overlay/OverlayManager.java test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java
git commit -m "feat: add overlay manager lifecycle and ordering"
```

## Task 3: Integrate Overlay Manager With Shared `PaistiServices` And `UI`

**Files:**
- Modify: `test/unit/haven/PaistiServicesLifetimeTest.java`
- Modify: `src/haven/PaistiServices.java`
- Modify: `src/haven/UI.java`

- [ ] **Step 1: Add failing lifecycle tests to the existing `PaistiServicesLifetimeTest`**

Update `test/unit/haven/PaistiServicesLifetimeTest.java`.

```java
package haven;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.overlay.ScreenOverlay;
import paisti.plugin.overlay.ScreenOverlayContext;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaistiServicesLifetimeTest {
    @Test
    @Tag("unit")
    void stopDisposesRegisteredOverlays() {
	PaistiServices services = new PaistiServices();
	TestPlugin plugin = new TestPlugin(services);
	TrackingOverlay overlay = new TrackingOverlay();

	services.overlayManager().register(plugin, overlay);
	services.start();
	services.stop();

	assertTrue(overlay.disposed);
	assertTrue(services.overlayManager().screenOverlays().isEmpty());
    }

    @Test
    @Tag("unit")
    void uiSwapDoesNotClearOverlayRegistrationsWhileServicesStayStarted() {
	PaistiServices services = new PaistiServices();
	TestPlugin plugin = new TestPlugin(services);
	TrackingOverlay overlay = new TrackingOverlay();

	services.overlayManager().register(plugin, overlay);
	services.bindUi(allocate(UI.class));
	services.start();
	services.bindUi(allocate(UI.class));

	assertFalse(services.overlayManager().screenOverlays().isEmpty());
    }

    private static final class TestPlugin extends PaistiPlugin {
	private TestPlugin(PaistiServices services) {super(services);}

	public void startUp() {}

	public void shutDown() {}
    }

    private static final class TrackingOverlay implements ScreenOverlay {
	private boolean disposed;

	public void render(ScreenOverlayContext ctx) {}

	public void dispose() {
	    disposed = true;
	}
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: `stopDisposesRegisteredOverlays()` fails because `PaistiServices.stop()` does not yet stop the overlay manager.

- [ ] **Step 3: Stop overlays during shared-service shutdown and render screen overlays from `UI.draw(...)`**

Update `src/haven/PaistiServices.java`.

```java
public synchronized void stop() {
    if(!started)
        return;
    started = false;
    pluginService.stopAll();
    overlayManager.stop();
}
```

Update `src/haven/UI.java`.

```java
public void draw(GOut g) {
    root.draw(g);
    synchronized(afterdraws) {
        for(AfterDraw ad : afterdraws)
            ad.draw(g);
        afterdraws.clear();
    }
    paistiServices.overlayManager().renderScreenOverlays(g);
}
```

- [ ] **Step 4: Run tests and build**

Run:

```bash
ant test-unit -buildfile build.xml
ant bin -buildfile build.xml
```

Expected: both Ant targets report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/haven/PaistiServices.java src/haven/UI.java test/unit/haven/PaistiServicesLifetimeTest.java
git commit -m "feat: wire overlay manager into shared services"
```

## Task 4: Add Map Overlay Bridge And Dispatch

**Files:**
- Modify: `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`
- Create: `src/paisti/pluginv2/overlay/MapOverlayBridge.java`
- Modify: `src/paisti/pluginv2/overlay/OverlayManager.java`

- [ ] **Step 1: Add failing map overlay tests**

Update `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`.

```java
import haven.PView;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;
import haven.render.Rendered;

@Test
@Tag("unit")
void mapOverlaysRenderInPriorityThenRegistrationOrder() {
    PaistiServices services = new PaistiServices();
    OverlayManager manager = new OverlayManager(services);
    TestPlugin owner = new TestPlugin(services);
    List<String> trace = new ArrayList<>();

    manager.register(owner, new TrackingMapOverlay("late", 10, trace));
    manager.register(owner, new TrackingMapOverlay("early-a", 0, trace));
    manager.register(owner, new TrackingMapOverlay("early-b", 0, trace));

    manager.renderMapWorldOverlays((Pipe) null, (Render) null);
    manager.renderMapScreenOverlays((GOut) null, (Pipe) null);

    assertEquals(Arrays.asList("world:early-a", "world:early-b", "world:late", "screen:early-a", "screen:early-b", "screen:late"), trace);
}

@Test
@Tag("unit")
void mapOverlayBridgeImplementsExpectedRenderInterfaces() throws Exception {
    Class<?> type = Class.forName("paisti.plugin.overlay.MapOverlayBridge");
    assertTrue(RenderTree.Node.class.isAssignableFrom(type));
    assertTrue(Rendered.class.isAssignableFrom(type));
    assertTrue(PView.Render2D.class.isAssignableFrom(type));
}

private static final class TrackingMapOverlay implements MapOverlay {
    private final String name;
    private final int priority;
    private final List<String> trace;

    private TrackingMapOverlay(String name, int priority, List<String> trace) {
	this.name = name;
	this.priority = priority;
	this.trace = trace;
    }

    public int priority() {return priority;}

    public void renderWorld(MapWorldOverlayContext ctx) {
	trace.add("world:" + name);
    }

    public void renderScreen(MapScreenOverlayContext ctx) {
	trace.add("screen:" + name);
    }
}
```

- [ ] **Step 2: Run tests to verify they fail**

Run:

```bash
ant test-unit -buildfile build.xml
```

Expected: the test run fails because `MapOverlayBridge` and the map dispatch methods do not exist yet.

- [ ] **Step 3: Add the map bridge and map dispatch in `OverlayManager`**

Create `src/paisti/pluginv2/overlay/MapOverlayBridge.java`.

```java
package paisti.plugin.overlay;

import haven.GOut;
import haven.PView;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;
import haven.render.Rendered;

final class MapOverlayBridge implements RenderTree.Node, Rendered, PView.Render2D {
    private final OverlayManager manager;

    MapOverlayBridge(OverlayManager manager) {
	this.manager = manager;
    }

    @Override
    public void draw(Pipe state, Render out) {
	manager.renderMapWorldOverlays(state, out);
    }

    @Override
    public void draw(GOut g, Pipe state) {
	manager.renderMapScreenOverlays(g, state);
    }
}
```

Update `src/paisti/pluginv2/overlay/OverlayManager.java` by adding map attachment and dispatch.

```java
package paisti.plugin.overlay;

import haven.GOut;
import haven.GameUI;
import haven.MapView;
import haven.render.Pipe;
import haven.render.Render;
import haven.render.RenderTree;

public class OverlayManager {
    private final MapOverlayBridge mapBridge = new MapOverlayBridge(this);
    private MapView attachedMap;
    private RenderTree.Slot mapSlot;

    public void renderMapWorldOverlays(Pipe state, Render out) {
	MapView map = currentMap();
	GameUI gui = (services.ui() == null) ? null : services.ui().gui;
	MapWorldOverlayContext ctx = new MapWorldOverlayContext(services.ui(), gui, map, state, out);
	for (RegisteredOverlay registered : sorted()) {
	    if((registered.overlay instanceof MapOverlay) && !registered.disabled && registered.overlay.enabled()) {
		try {
		    ((MapOverlay) registered.overlay).renderWorld(ctx);
		    registered.failures = 0;
		} catch (Throwable t) {
		    handleFailure(registered, t);
		}
	    }
	}
    }

    public void renderMapScreenOverlays(GOut g, Pipe state) {
	MapView map = currentMap();
	GameUI gui = (services.ui() == null) ? null : services.ui().gui;
	MapScreenOverlayContext ctx = new MapScreenOverlayContext(services.ui(), gui, map, g, state);
	for (RegisteredOverlay registered : sorted()) {
	    if((registered.overlay instanceof MapOverlay) && !registered.disabled && registered.overlay.enabled()) {
		try {
		    ((MapOverlay) registered.overlay).renderScreen(ctx);
		    registered.failures = 0;
		} catch (Throwable t) {
		    handleFailure(registered, t);
		}
	    }
	}
    }

    public void syncMapOverlayAttachment() {
	MapView map = currentMap();
	if(map == attachedMap) {
	    return;
	}
	if(mapSlot != null) {
	    mapSlot.remove();
	    mapSlot = null;
	}
	attachedMap = map;
	if(attachedMap != null) {
	    mapSlot = attachedMap.drawadd(mapBridge);
	}
    }

    public void renderScreenOverlays(GOut g) {
	syncMapOverlayAttachment();
	ScreenOverlayContext ctx = new ScreenOverlayContext(services.ui(), g);
	for (RegisteredOverlay registered : sorted()) {
	    if((registered.overlay instanceof ScreenOverlay) && !registered.disabled && registered.overlay.enabled()) {
		try {
		    ((ScreenOverlay) registered.overlay).render(ctx);
		    registered.failures = 0;
		} catch (Throwable t) {
		    handleFailure(registered, t);
		}
	    }
	}
    }

    public void stop() {
	if(mapSlot != null) {
	    mapSlot.remove();
	    mapSlot = null;
	}
	attachedMap = null;
	for (RegisteredOverlay registered : overlays) {
	    overlays.remove(registered);
	    disposeQuietly(registered);
	}
    }

    private MapView currentMap() {
	return (services.ui() == null || services.ui().gui == null) ? null : services.ui().gui.map;
    }
}
```

- [ ] **Step 4: Run tests and build**

Run:

```bash
ant test-unit -buildfile build.xml
ant bin -buildfile build.xml
```

Expected: both Ant targets report `BUILD SUCCESSFUL`.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/pluginv2/overlay/MapOverlayBridge.java src/paisti/pluginv2/overlay/OverlayManager.java test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java
git commit -m "feat: add map overlay bridge"
```

## Task 5: Add Minimal DevTools Overlay Examples And Verify Manually

**Files:**
- Create: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginSceneOverlay.java`
- Create: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginScreenOverlay.java`
- Modify: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`

- [ ] **Step 1: Add the screen overlay example**

Create `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginScreenOverlay.java`.

```java
package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Text;
import haven.Tex;
import haven.TexI;
import paisti.plugin.overlay.ScreenOverlay;
import paisti.plugin.overlay.ScreenOverlayContext;

import java.awt.Color;

public class DevToolsPluginScreenOverlay implements ScreenOverlay {
    private Tex label;

    @Override
    public int priority() {
	return 100;
    }

    @Override
    public void render(ScreenOverlayContext ctx) {
	if(ctx.ui() == null || !ctx.ui().modctrl || !ctx.ui().modshift) {
	    return;
	}
	if(label == null) {
	    label = new TexI(Text.renderstroked("DEV overlay active", Color.WHITE, Color.BLACK).img);
	}
	ctx.g().image(label, Coord.of(10, 10));
    }

    @Override
    public void dispose() {
	if(label != null) {
	    label.dispose();
	    label = null;
	}
    }
}
```

- [ ] **Step 2: Add the map overlay example**

Create `src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginSceneOverlay.java`.

```java
package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.Coord3f;
import haven.Gob;
import haven.Text;
import haven.Tex;
import haven.TexI;
import paisti.plugin.overlay.MapOverlay;
import paisti.plugin.overlay.MapScreenOverlayContext;

public class DevToolsPluginSceneOverlay implements MapOverlay {
    private Tex label;

    @Override
    public int priority() {
	return 100;
    }

    @Override
    public void renderScreen(MapScreenOverlayContext ctx) {
	if(ctx.ui() == null || ctx.map() == null || !ctx.ui().modctrl || !ctx.ui().modshift) {
	    return;
	}
	Gob player = ctx.map().player();
	if(player == null) {
	    return;
	}
	if(label == null) {
	    label = new TexI(Text.renderstroked("DEV player", java.awt.Color.WHITE, java.awt.Color.BLACK).img);
	}
	Coord3f playerLabel = new Coord3f(player.getc().x, player.getc().y, player.getc().z + 30f);
	Coord sc = ctx.worldToScreen(playerLabel);
	if(sc == null || !sc.isect(Coord.z, ctx.g().sz())) {
	    return;
	}
	ctx.g().aimage(label, sc, 0.5, 1.0);
    }

    @Override
    public void dispose() {
	if(label != null) {
	    label.dispose();
	    label = null;
	}
    }
}
```

- [ ] **Step 3: Register both example overlays in `DevToolsPlugin`**

Update `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`.

```java
package paisti.plugin.DevToolsPlugin;

import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.overlay.OverlayRegistration;

public class DevToolsPlugin extends PaistiPlugin {
    private final DevToolsPluginSceneOverlay sceneOverlay = new DevToolsPluginSceneOverlay();
    private final DevToolsPluginScreenOverlay screenOverlay = new DevToolsPluginScreenOverlay();
    private OverlayRegistration sceneOverlayRegistration;
    private OverlayRegistration screenOverlayRegistration;
    EventBus.Subscriber outgoingWidgetMessageSubscriber;

    @Override
    public void startUp() {
	outgoingWidgetMessageSubscriber = eventBus().register(BeforeOutgoingWidgetMessage.class, this::logOutgoingWidgetMessage, 0);
	sceneOverlayRegistration = overlayManager().register(this, sceneOverlay);
	screenOverlayRegistration = overlayManager().register(this, screenOverlay);
    }

    @Override
    public void shutDown() {
	eventBus().unregister(outgoingWidgetMessageSubscriber);
	if(sceneOverlayRegistration != null) {
	    sceneOverlayRegistration.close();
	    sceneOverlayRegistration = null;
	}
	if(screenOverlayRegistration != null) {
	    screenOverlayRegistration.close();
	    screenOverlayRegistration = null;
	}
	overlayManager().unregisterAll(this);
    }
}
```

- [ ] **Step 4: Run automated checks, build, and manual verification**

Run:

```bash
ant test-unit -buildfile build.xml
ant bin -buildfile build.xml
java -jar bin/hafen.jar
```

Expected: both Ant targets report `BUILD SUCCESSFUL`, then the client launches.

Manual checks:

```text
1. Ensure the Developer tools plugin is enabled in Options -> Plugins.
2. Enter the game world.
3. Hold Ctrl+Shift.
4. Confirm a top-left label appears: DEV overlay active.
5. Confirm a DEV player label appears above the player.
6. Release Ctrl+Shift and confirm both labels disappear.
7. Relog or recreate the session and confirm both labels still appear when Ctrl+Shift is held.
8. Disable the plugin and confirm neither label appears.
```

- [ ] **Step 5: Commit**

```bash
git add src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginSceneOverlay.java src/paisti/pluginv2/DevToolsPlugin/DevToolsPluginScreenOverlay.java
git commit -m "feat: add devtools plugin overlay examples"
```

## Self-Review

### Spec coverage

- `OverlayManager` under `PaistiServices`: covered by Task 1.
- `PaistiPlugin.overlayManager()` accessor: covered by Task 1.
- Stable ordering and failure isolation: covered by Task 2.
- Shared-service lifetime and UI-swap behavior: covered by Task 3.
- Manager-owned map bridge and map dispatch: covered by Task 4.
- DevTools overlay example plus manual verification: covered by Task 5.

### Placeholder scan

- No `TODO`, `TBD`, or deferred implementation markers remain.
- Every code-changing step includes concrete code.
- Every verification step includes an exact command or explicit manual checklist.

### Type consistency

- The plan uses one consistent overlay package: `paisti.plugin.overlay`.
- The manager API is consistent across tasks: `register`, `unregister`, `unregisterAll`, `renderScreenOverlays`, `renderMapWorldOverlays`, `renderMapScreenOverlays`, `syncMapOverlayAttachment`, `stop`.
- Service lifetime assumptions match current master: `new PaistiServices()`, `services.ui()`, shared services across UI swaps.
