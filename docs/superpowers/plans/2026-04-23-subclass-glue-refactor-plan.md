# Subclass-Based Glue Code Refactor — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Move all paisti service initialization and behavioral hooks out of vanilla haven classes into subclasses under `src/paisti/client/`, reducing vanilla modifications to 3 one-line instantiation swaps.

**Architecture:** Create `PUI extends UI`, `PGameUI extends GameUI`, `PMapView extends MapView` in `src/paisti/client/`. PUI owns `PaistiServices` (fresh per instance). Screen overlays render via vanilla `AfterDraw` mechanism instead of a mid-method hook. Vanilla code only instantiates the subclasses at factory points.

**Tech Stack:** Java, Ant build system. No new dependencies.

**Build command:** `ant clean-code bin -buildfile build.xml`
**Test command:** `ant test -buildfile build.xml`

---

## File Structure

### New files
- `src/paisti/client/PUI.java` — extends `haven.UI`, owns `PaistiServices`, overrides `tick/wdgmsg/setGUI/clearGUI/destroy`
- `src/paisti/client/PGameUI.java` — extends `haven.GameUI`, minimal for now
- `src/paisti/client/PMapView.java` — extends `haven.MapView`, minimal for now
- `src/paisti/client/ScreenOverlayAfterDraw.java` — implements `UI.AfterDraw`, bridges overlay rendering
- `test/unit/paisti/client/PUILifecycleTest.java` — replaces `PaistiServicesLifetimeTest.java`

### Moved files
- `src/haven/PaistiServices.java` → `src/paisti/client/PaistiServices.java` (package change)

### Modified files (vanilla cleanup)
- `src/haven/UI.java` — remove all paisti fields, constructors, accessors, hooks
- `src/haven/GLPanel.java` — remove PaistiServices ownership, simplify makeui/newui/teardown, add PUI import
- `src/haven/OwnerContext.java` — remove dead PaistiServices context registration
- `src/haven/GameUI.java` — factory swap to PGameUI
- `src/haven/MapView.java` — factory swap to PMapView, update `ui.services()` calls

### Modified files (import updates)
- `src/paisti/plugin/PaistiPlugin.java` — update import `haven.PaistiServices` → `paisti.client.PaistiServices`
- `src/paisti/plugin/PluginService.java` — update import
- `src/paisti/plugin/overlay/OverlayManager.java` — update import
- `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java` — update import
- `src/haven/OptWnd.java` — update `ui.pluginService()` → `PUI.of(ui).pluginService()`
- `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java` — update import

### Deleted files
- `src/haven/PaistiServices.java` — replaced by `src/paisti/client/PaistiServices.java`
- `test/unit/haven/PaistiServicesLifetimeTest.java` — replaced by `test/unit/paisti/client/PUILifecycleTest.java`

---

### Task 1: Move PaistiServices to paisti.client package

**Files:**
- Create: `src/paisti/client/PaistiServices.java`
- Delete: `src/haven/PaistiServices.java`
- Modify: `src/paisti/plugin/PaistiPlugin.java` (import)
- Modify: `src/paisti/plugin/PluginService.java` (import)
- Modify: `src/paisti/plugin/overlay/OverlayManager.java` (import)
- Modify: `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java` (import)

- [ ] **Step 1: Create `src/paisti/client/PaistiServices.java`**

Copy `src/haven/PaistiServices.java` to the new location with updated package declaration. The class itself is unchanged except for the package and import of `haven.UI`:

```java
package paisti.client;

import haven.UI;
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

    public UI ui() {
	return ui;
    }

    public void bindUi(UI ui) {
	this.ui = ui;
	overlayManager.syncMapOverlayAttachment();
    }

    public void clearUi(UI ui) {
	if(this.ui == ui) {
	    this.ui = null;
	    overlayManager.syncMapOverlayAttachment();
	}
    }

    public EventBus eventBus() {
	return eventBus;
    }

    public PluginService pluginService() {
	return pluginService;
    }

    public OverlayManager overlayManager() {
	return overlayManager;
    }

    public synchronized void start() {
	if(started)
	    return;
	started = true;
	overlayManager.syncMapOverlayAttachment();
	pluginService.loadBuiltInPlugins();
	pluginService.syncActivePlugins();
    }

    public synchronized void stop() {
	if(started) {
	    started = false;
	    pluginService.stopAll();
	}
	overlayManager.stop();
    }
}
```

- [ ] **Step 2: Update imports in paisti plugin classes**

In each of these 4 files, change `import haven.PaistiServices;` to `import paisti.client.PaistiServices;`:

- `src/paisti/plugin/PaistiPlugin.java` (line 3)
- `src/paisti/plugin/PluginService.java` (line 5)
- `src/paisti/plugin/overlay/OverlayManager.java` (line 7)
- `src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java` (line 3)

- [ ] **Step 3: Delete `src/haven/PaistiServices.java`**

Delete the old file.

- [ ] **Step 4: Build to verify imports resolve**

Run: `ant clean-code bin -buildfile build.xml`
Expected: Build succeeds (even though haven classes still reference `PaistiServices` via the old fully-qualified paths that currently exist as field types — the haven package reference from UI.java etc. will break until Task 4 cleans them up). **Note: this step may produce compile errors because `haven/UI.java` still references `PaistiServices` by simple name. That's expected — we fix it in Task 4.**

Actually, because `haven/UI.java` still has `private final PaistiServices paistiServices;` and it used to resolve via same-package, it will fail. So we should NOT try to build yet. Move on.

- [ ] **Step 5: Commit**

```
git add src/paisti/client/PaistiServices.java src/paisti/plugin/PaistiPlugin.java src/paisti/plugin/PluginService.java src/paisti/plugin/overlay/OverlayManager.java src/paisti/plugin/DevToolsPlugin/DevToolsPlugin.java
git rm src/haven/PaistiServices.java
git commit -m "refactor: move PaistiServices from haven to paisti.client package"
```

---

### Task 2: Create ScreenOverlayAfterDraw

**Files:**
- Create: `src/paisti/client/ScreenOverlayAfterDraw.java`

- [ ] **Step 1: Create the AfterDraw bridge class**

```java
package paisti.client;

import haven.GOut;
import haven.UI;

public class ScreenOverlayAfterDraw implements UI.AfterDraw {
    private final PaistiServices services;

    public ScreenOverlayAfterDraw(PaistiServices services) {
	this.services = services;
    }

    @Override
    public void draw(GOut g) {
	services.overlayManager().renderScreenOverlays(g);
    }
}
```

- [ ] **Step 2: Commit**

```
git add src/paisti/client/ScreenOverlayAfterDraw.java
git commit -m "feat: add ScreenOverlayAfterDraw to bridge overlay rendering via vanilla AfterDraw"
```

---

### Task 3: Create PUI, PGameUI, PMapView subclasses

**Files:**
- Create: `src/paisti/client/PUI.java`
- Create: `src/paisti/client/PGameUI.java`
- Create: `src/paisti/client/PMapView.java`

- [ ] **Step 1: Create `src/paisti/client/PUI.java`**

```java
package paisti.client;

import haven.*;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;

public class PUI extends UI {
    private final PaistiServices paistiServices;
    private final ScreenOverlayAfterDraw screenOverlayAfterDraw;

    public PUI(Context uictx, Coord sz, Runner fun) {
	super(uictx, sz, fun);
	this.paistiServices = new PaistiServices();
	this.paistiServices.bindUi(this);
	this.screenOverlayAfterDraw = new ScreenOverlayAfterDraw(paistiServices);
	this.paistiServices.start();
    }

    public static PUI of(UI ui) {
	return (PUI) ui;
    }

    public PaistiServices services() {
	return paistiServices;
    }

    public paisti.hooks.EventBus eventBus() {
	return paistiServices.eventBus();
    }

    public paisti.plugin.PluginService pluginService() {
	return paistiServices.pluginService();
    }

    public paisti.plugin.overlay.OverlayManager overlayManager() {
	return paistiServices.overlayManager();
    }

    @Override
    public void tick() {
	super.tick();
	drawafter(screenOverlayAfterDraw);
    }

    @Override
    public void wdgmsg(Widget sender, String msg, Object... args) {
	int id = widgetid(sender);
	if(id >= 0) {
	    eventBus().post(new BeforeOutgoingWidgetMessage(this, sender, id, msg, args));
	}
	super.wdgmsg(sender, msg, args);
    }

    @Override
    public void setGUI(GameUI gui) {
	super.setGUI(gui);
	paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void clearGUI(GameUI gui) {
	super.clearGUI(gui);
	paistiServices.overlayManager().syncMapOverlayAttachment();
    }

    @Override
    public void destroy() {
	paistiServices.stop();
	paistiServices.clearUi(this);
	super.destroy();
    }
}
```

**Important notes on `wdgmsg` override:** The vanilla `wdgmsg` has a guard `if(id < 0)` that returns early (widget not registered). We replicate this guard for the EventBus post but still call `super.wdgmsg()` unconditionally so the vanilla warning/early-return logic is preserved. The EventBus post only fires when the sender is a valid registered widget.

**Important note on `tick`:** Vanilla `UI.tick()` takes no parameters (line 442 of UI.java): `public void tick()`. It is NOT `tick(double dt)`. We match this signature.

- [ ] **Step 2: Create `src/paisti/client/PGameUI.java`**

```java
package paisti.client;

import haven.GameUI;

public class PGameUI extends GameUI {
    public PGameUI(String chrid, long plid, String genus) {
	super(chrid, plid, genus);
    }
}
```

- [ ] **Step 3: Create `src/paisti/client/PMapView.java`**

```java
package paisti.client;

import haven.*;

public class PMapView extends MapView {
    public PMapView(Coord sz, Glob glob, Coord2d cc, long plgob) {
	super(sz, glob, cc, plgob);
    }
}
```

**Note:** The `Bot.cancelCurrent()` call in vanilla MapView's private inner class `Click.hit()` (line 2284) cannot be moved to PMapView because `Click` is a private inner class — it's not overridable from a subclass. This `auto.Bot` import stays in vanilla MapView for now. It's an Ender fork concern, not a paisti services concern, and removing it would require adding a protected hook method to vanilla MapView. We can address this separately.

- [ ] **Step 4: Commit**

```
git add src/paisti/client/PUI.java src/paisti/client/PGameUI.java src/paisti/client/PMapView.java
git commit -m "feat: add PUI, PGameUI, PMapView subclasses owning paisti service lifecycle"
```

---

### Task 4: Clean up vanilla UI.java

**Files:**
- Modify: `src/haven/UI.java`

This is the biggest cleanup — remove ~25 lines of paisti-specific code.

- [ ] **Step 1: Remove paisti imports (lines 49–51)**

Remove these three lines:
```java
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;
import paisti.plugin.PluginService;
```

- [ ] **Step 2: Remove `paistiServices` field (line 80)**

Remove:
```java
    private final PaistiServices paistiServices;
```

- [ ] **Step 3: Revert constructor 1 (lines 234–249)**

Remove the PaistiServices creation from the 3-arg constructor. Change:
```java
    public UI(Context uictx, Coord sz, Runner fun) {
	this.uictx = uictx;
	this.paistiServices = new PaistiServices();
	this.paistiServices.bindUi(this);
	root = createRoot(sz);
```
To:
```java
    public UI(Context uictx, Coord sz, Runner fun) {
	this.uictx = uictx;
	root = createRoot(sz);
```

- [ ] **Step 4: Remove constructor 2 (lines 251–265)**

Delete the entire 4-arg constructor `UI(Context, Coord, Runner, PaistiServices)`. It was only used by GLPanel's `makeui()` which we're changing.

- [ ] **Step 5: Remove service accessors (lines 271–281)**

Remove these methods entirely:
```java
	public PaistiServices services() {
	    return paistiServices;
	}

	public EventBus eventBus() {
	    return paistiServices.eventBus();
	}

	public PluginService pluginService() {
	    return paistiServices.pluginService();
	}
```

- [ ] **Step 6: Remove overlay rendering from `draw()` (line 464)**

Change:
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
To:
```java
    public void draw(GOut g) {
	root.draw(g);
	synchronized(afterdraws) {
	    for(AfterDraw ad : afterdraws)
		ad.draw(g);
	    afterdraws.clear();
	}
    }
```

- [ ] **Step 7: Remove EventBus post from `wdgmsg()` (line 748)**

Change:
```java
    public void wdgmsg(Widget sender, String msg, Object... args) {
	int id = widgetid(sender);
	if(id < 0) {
	    new Warning("wdgmsg sender (%s) is not in rwidgets, message is %s", sender.getClass().getName(), msg).issue();
	    return;
	}
	eventBus().post(new BeforeOutgoingWidgetMessage(this, sender, id, msg, args));
	if(rcvr != null)
	    rcvr.rcvmsg(id, msg, args);
    }
```
To:
```java
    public void wdgmsg(Widget sender, String msg, Object... args) {
	int id = widgetid(sender);
	if(id < 0) {
	    new Warning("wdgmsg sender (%s) is not in rwidgets, message is %s", sender.getClass().getName(), msg).issue();
	    return;
	}
	if(rcvr != null)
	    rcvr.rcvmsg(id, msg, args);
    }
```

- [ ] **Step 8: Remove clearUi from `destroy()` (line 1060)**

Change:
```java
    public void destroy() {
	paistiServices.clearUi(this);
	root.destroy();
	audio.clear();
    }
```
To:
```java
    public void destroy() {
	root.destroy();
	audio.clear();
    }
```

- [ ] **Step 9: Remove overlay sync from `setGUI()` (line 1203)**

Change:
```java
    public void setGUI(GameUI gui) {
	synchronized (guiLock) {
	    this.gui = gui;
	}
	paistiServices.overlayManager().syncMapOverlayAttachment();
    }
```
To:
```java
    public void setGUI(GameUI gui) {
	synchronized (guiLock) {
	    this.gui = gui;
	}
    }
```

- [ ] **Step 10: Remove overlay sync from `clearGUI()` (lines 1214–1216)**

Change:
```java
    public void clearGUI(GameUI gui) {
	boolean cleared = false;
	synchronized (guiLock) {
	    if(this.gui == gui) {
		this.gui = null;
		cleared = true;
	    }
	}
	if(cleared) {
	    paistiServices.overlayManager().syncMapOverlayAttachment();
	}
    }
```
To:
```java
    public void clearGUI(GameUI gui) {
	synchronized (guiLock) {
	    if(this.gui == gui) {
		this.gui = null;
	    }
	}
    }
```

- [ ] **Step 11: Remove PaistiServices import if present**

If there's an `import haven.PaistiServices;` or just `PaistiServices` usage — since PaistiServices was in the same package, it didn't need an import. Just verify no remaining references to `PaistiServices` or `paistiServices` exist in UI.java.

- [ ] **Step 12: Commit**

```
git add src/haven/UI.java
git commit -m "refactor: remove all paisti service code from vanilla UI.java"
```

---

### Task 5: Clean up vanilla GLPanel.java

**Files:**
- Modify: `src/haven/GLPanel.java`

- [ ] **Step 1: Add PUI import and remove PaistiServices field**

Add at the top of the file (with other imports):
```java
import paisti.client.PUI;
```

Remove line 47:
```java
	private final PaistiServices paistiServices = new PaistiServices();
```

- [ ] **Step 2: Simplify `makeui()` (line 65–67)**

Change:
```java
	protected UI makeui(UI.Runner fun, PaistiServices services) {
	    return(new UI(p, new Coord(p.getSize()), fun, services));
	}
```
To:
```java
	protected UI makeui(UI.Runner fun) {
	    return(new PUI(p, new Coord(p.getSize()), fun));
	}
```

- [ ] **Step 3: Remove `onUiSwapped()` (lines 69–72)**

Delete entirely:
```java
	private void onUiSwapped(UI newui) {
	    paistiServices.bindUi(newui);
	    paistiServices.start();
	}
```

- [ ] **Step 4: Simplify `onLoopTeardown()` (lines 74–82)**

Change:
```java
	private void onLoopTeardown() {
	    UI lastui = this.ui;
	    paistiServices.stop();
	    if(lastui != null) {
		synchronized(lastui) {
		    lastui.destroy();
		}
	    }
	}
```
To:
```java
	private void onLoopTeardown() {
	    UI lastui = this.ui;
	    if(lastui != null) {
		synchronized(lastui) {
		    lastui.destroy();
		}
	    }
	}
```

- [ ] **Step 5: Simplify `newui()` (lines 482–512)**

Change:
```java
	public UI newui(UI.Runner fun) {
	    UI prevui, newui = makeui(fun, paistiServices);
```
To:
```java
	public UI newui(UI.Runner fun) {
	    UI prevui, newui = makeui(fun);
```

And remove the `onUiSwapped(newui);` call at line 505. The full `newui()` method becomes:
```java
	public UI newui(UI.Runner fun) {
	    UI prevui, newui = makeui(fun);
	    newui.env = p.env();
	    if(p.getParent() instanceof Console.Directory)
		newui.cons.add((Console.Directory)p.getParent());
	    if(p instanceof Console.Directory)
		newui.cons.add((Console.Directory)p);
	    newui.cons.add(this);
	    synchronized(uilock) {
		prevui = this.ui;
		ui = newui;
		ui.root.guprof = uprof;
		ui.root.grprof = rprof;
		ui.root.ggprof = gprof;
		while((this.lockedui != null) && (this.lockedui == prevui)) {
		    try {
			uilock.wait();
		    } catch(InterruptedException e) {
			Thread.currentThread().interrupt();
			break;
		    }
		}
	    }
	    if(prevui != null) {
		synchronized(prevui) {
		    prevui.destroy();
		}
	    }
	    return(newui);
	}
```

- [ ] **Step 6: Commit**

```
git add src/haven/GLPanel.java
git commit -m "refactor: remove PaistiServices ownership from GLPanel, delegate to PUI"
```

---

### Task 6: Clean up OwnerContext.java

**Files:**
- Modify: `src/haven/OwnerContext.java`

- [ ] **Step 1: Remove PaistiServices from uictx (line 127)**

Change:
```java
    public static final ClassResolver<UI> uictx = new ClassResolver<UI>()
	.add(UI.class, ui -> ui)
	.add(Glob.class, ui -> ui.sess.glob)
	.add(Session.class, ui -> ui.sess)
	.add(PaistiServices.class, UI::services);
```
To:
```java
    public static final ClassResolver<UI> uictx = new ClassResolver<UI>()
	.add(UI.class, ui -> ui)
	.add(Glob.class, ui -> ui.sess.glob)
	.add(Session.class, ui -> ui.sess);
```

- [ ] **Step 2: Commit**

```
git add src/haven/OwnerContext.java
git commit -m "refactor: remove dead PaistiServices registration from OwnerContext"
```

---

### Task 7: Update vanilla factory swaps (GameUI + MapView)

**Files:**
- Modify: `src/haven/GameUI.java`
- Modify: `src/haven/MapView.java`
- Modify: `src/haven/OptWnd.java`

- [ ] **Step 1: Update GameUI factory (lines 285–297)**

Add import at top of GameUI.java:
```java
import paisti.client.PGameUI;
```

Change the factory (line 293):
```java
	    GameUI gui = new GameUI(chrid, plid, genus);
```
To:
```java
	    GameUI gui = new PGameUI(chrid, plid, genus);
```

- [ ] **Step 2: Update MapView factory (lines 662–672)**

Add import at top of MapView.java:
```java
import paisti.client.PMapView;
```

Change the factory (line 670):
```java
	    return(new MapView(sz, ui.sess.glob, mc, pgob));
```
To:
```java
	    return(new PMapView(sz, ui.sess.glob, mc, pgob));
```

- [ ] **Step 3: Update MapView `attached()` (lines 700–706)**

Add import:
```java
import paisti.client.PUI;
```

Change:
```java
	@Override
	protected void attached() {
	    super.attached();
	    if((ui != null) && (ui.services() != null)) {
		ui.services().overlayManager().syncMapOverlayAttachment();
	    }
	}
```
To:
```java
	@Override
	protected void attached() {
	    super.attached();
	    if(ui instanceof PUI) {
		PUI.of(ui).overlayManager().syncMapOverlayAttachment();
	    }
	}
```

- [ ] **Step 4: Update OptWnd.java plugin access (lines 884, 887)**

Add import at top of OptWnd.java:
```java
import paisti.client.PUI;
```

Change line 884:
```java
	for(PaistiPlugin plugin : ui.pluginService().getConfigurablePlugins()) {
```
To:
```java
	for(PaistiPlugin plugin : PUI.of(ui).pluginService().getConfigurablePlugins()) {
```

Change line 887:
```java
	    toggle.set(v -> ui.pluginService().syncActivePlugins());
```
To:
```java
	    toggle.set(v -> PUI.of(ui).pluginService().syncActivePlugins());
```

- [ ] **Step 5: Commit**

```
git add src/haven/GameUI.java src/haven/MapView.java src/haven/OptWnd.java
git commit -m "refactor: swap vanilla factories to PUI/PGameUI/PMapView, update service accessors"
```

---

### Task 8: Update tests

**Files:**
- Delete: `test/unit/haven/PaistiServicesLifetimeTest.java`
- Create: `test/unit/paisti/client/PUILifecycleTest.java`
- Modify: `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`

- [ ] **Step 1: Update OverlayManagerTest import**

In `test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java`, change line 3:
```java
import haven.PaistiServices;
```
To:
```java
import paisti.client.PaistiServices;
```

Search the rest of this file for any other references to `haven.PaistiServices` and update them. Also search for references to `ui.services()` or the 4-arg UI constructor `new UI(ctx, sz, fun, services)` and update to use PUI where needed. If the test creates UI instances with the 4-arg constructor, replace with direct `PaistiServices` construction (since tests that just test `OverlayManager` don't need a real PUI).

- [ ] **Step 2: Delete old test file**

Delete `test/unit/haven/PaistiServicesLifetimeTest.java`.

- [ ] **Step 3: Create `test/unit/paisti/client/PUILifecycleTest.java`**

This test replaces the old `PaistiServicesLifetimeTest` with tests that verify the new per-PUI-instance service lifecycle. The old tests assumed shared services across UI swaps — the new tests verify fresh services per PUI.

Key test cases:
1. `puiConstructorCreatesFreshServices` — PUI constructor creates and starts its own PaistiServices
2. `puiDestroyStopsServicesAndClearsUi` — PUI.destroy() stops services before destroying widget tree
3. `puiWdgmsgPostsEventBusEvent` — PUI.wdgmsg() posts BeforeOutgoingWidgetMessage before delegating
4. `puiSetGuiSyncsOverlayAttachment` — PUI.setGUI() calls syncMapOverlayAttachment after super
5. `puiClearGuiSyncsOverlayAttachment` — PUI.clearGUI() calls syncMapOverlayAttachment after super
6. `puiTickRegistersAfterDraw` — PUI.tick() registers the ScreenOverlayAfterDraw each frame
7. `eachPuiGetsFreshServices` — Two PUI instances have different PaistiServices
8. `screenOverlaysRenderedViaAfterDraw` — Screen overlays render during the afterdraw pass

The exact test code should be adapted from the old test's test infrastructure (fakeUi helpers, DummyPanel, TestRootWidget, TestAudioRoot, TestPlugin, TrackingScreenOverlay, etc.) but targeting PUI instead of UI.

- [ ] **Step 4: Run tests**

Run: `ant test -buildfile build.xml`
Expected: All tests pass.

- [ ] **Step 5: Commit**

```
git add test/unit/paisti/client/PUILifecycleTest.java test/unit/paisti/pluginv2/overlay/OverlayManagerTest.java
git rm test/unit/haven/PaistiServicesLifetimeTest.java
git commit -m "test: replace PaistiServicesLifetimeTest with PUILifecycleTest for per-instance services"
```

---

### Task 9: Build verification and cleanup

**Files:**
- Possibly any remaining compilation issues

- [ ] **Step 1: Full clean build**

Run: `ant clean-code bin -buildfile build.xml`
Expected: Build succeeds with no errors.

- [ ] **Step 2: Run all tests**

Run: `ant test -buildfile build.xml`
Expected: All tests pass.

- [ ] **Step 3: Verify no remaining haven.PaistiServices references**

Search across the entire codebase for any remaining `haven.PaistiServices` imports or `import haven.PaistiServices` strings. There should be none.

Also verify no remaining `ui.services()`, `ui.eventBus()`, or `ui.pluginService()` calls on plain `UI` references exist (these methods no longer exist on `UI`).

- [ ] **Step 4: Fix any remaining issues found in steps 1–3**

If compilation fails or references remain, fix them. Common issues:
- Test files with old imports
- OverlayManagerTest accessing `ui.services()` on a non-PUI
- Any haven classes we missed that reference the removed methods

- [ ] **Step 5: Final commit if any fixes were needed**

```
git add -A
git commit -m "fix: resolve remaining compilation issues from subclass refactor"
```
