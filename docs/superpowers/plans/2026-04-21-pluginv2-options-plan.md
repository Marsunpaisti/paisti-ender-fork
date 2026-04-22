# PluginV2 Options Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add persisted pluginv2 enable or disable checkboxes to Options, sync plugin runtime immediately on toggle, and then remove the legacy plugin framework once the new flow is verified.

**Architecture:** Add a tiny `haven`-side plugin config helper on top of `CFG`, make `PluginService` the single source of truth for metadata validation and persisted enablement, and have `OptWnd` render toggles from `PluginService`. After the new path works, remove the old `paisti.plugin` runtime, its boot hooks, UI hooks, tests, and dead resources.

**Tech Stack:** Java 11, Ant, Haven `CFG`, `OptWnd`, `PluginService`, self-test `main(...)` classes under `src/haven/test`

---

## File Structure

- Create: `src/haven/PluginConfig.java`
  - Tiny helper that constructs plugin-scoped `CFG<Boolean>` entries under `pluginv2.<configName>.enabled`

- Create: `src/haven/test/PluginV2ServiceSelfTest.java`
  - Self-test for plugin config persistence, `PluginService` metadata validation, visible-plugin filtering, and `DevToolsPlugin` metadata

- Create: `src/haven/test/PluginV2LegacyRemovalSelfTest.java`
  - Self-test that asserts the legacy plugin framework classes and dead assets are gone after cleanup

- Modify: `src/paisti/pluginv2/PluginService.java`
  - Validate `PluginDescription` metadata up front, expose configurable plugins, and read persisted enablement from `PluginConfig`

- Modify: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`
  - Make the built-in plugin configurable by giving it a non-blank `configName` and making it visible in Options

- Modify: `src/haven/OptWnd.java`
  - Render the `Plugin V2` section, create checkboxes, persist values, and call `syncActivePlugins()` immediately

- Modify then remove legacy references from: `src/haven/MainFrame.java`, `src/haven/UI.java`, `src/haven/Window.java`, `src/haven/GameUI.java`, `src/haven/MenuGrid.java`
  - Delete boot hooks and event dispatches that only exist for `PluginManager`

- Delete: `src/paisti/plugin/BuiltinPlugins.java`
- Delete: `src/paisti/plugin/ClientPlugin.java`
- Delete: `src/paisti/plugin/PluginAction.java`
- Delete: `src/paisti/plugin/PluginContext.java`
- Delete: `src/paisti/plugin/PluginManager.java`
- Delete: `src/paisti/plugin/PluginOptionSection.java`
- Delete: `src/paisti/plugin/PluginWindowEvent.java`
- Delete: `src/paisti/plugin/plugins/OutgoingWdgmsgPlugin.java`
- Delete: `src/haven/test/PluginRuntimeSelfTest.java`
- Delete: `src/haven/test/PluginMenuActionSelfTest.java`
- Delete: `resources/src/local/paginae/add/plugins/demo_toggle.res/`

## Task 1: Add PluginConfig Helper And First Failing Self-Test

**Files:**
- Create: `src/haven/PluginConfig.java`
- Create: `src/haven/test/PluginV2ServiceSelfTest.java`
- Test: `src/haven/test/PluginV2ServiceSelfTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/haven/test/PluginV2ServiceSelfTest.java` with a first test that uses reflection so it compiles before `PluginConfig` exists but fails at runtime.

```java
package haven.test;

import haven.CFG;

import java.lang.reflect.Method;

public class PluginV2ServiceSelfTest {
    public static void main(String[] args) throws Exception {
	pluginConfigPersistsEnabledFlag();
	System.out.println("PluginV2ServiceSelfTest OK");
    }

    private static void pluginConfigPersistsEnabledFlag() throws Exception {
	Class<?> type = Class.forName("haven.PluginConfig");
	Method enabled = type.getMethod("enabled", String.class, boolean.class);
	@SuppressWarnings("unchecked")
	CFG<Boolean> cfg = (CFG<Boolean>) enabled.invoke(null, "pluginv2-selftest-helper", true);

	cfg.set(false);
	require(!cfg.get(), "expected helper-backed enabled flag to persist false");
    }

    private static void require(boolean condition, String message) {
	if(!condition) {
	    throw new AssertionError(message);
	}
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: the build succeeds, and the test fails at runtime with `ClassNotFoundException: haven.PluginConfig`.

- [ ] **Step 3: Write minimal implementation**

Create `src/haven/PluginConfig.java`.

```java
package haven;

public final class PluginConfig {
    private PluginConfig() {
    }

    public static CFG<Boolean> enabled(String configName, boolean defval) {
        if ((configName == null) || configName.trim().isEmpty()) {
            throw new IllegalArgumentException("plugin configName must not be blank");
        }
        return new CFG<>("pluginv2." + configName + ".enabled", defval);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: `PluginV2ServiceSelfTest OK`.

- [ ] **Step 5: Commit**

```bash
git add src/haven/PluginConfig.java src/haven/test/PluginV2ServiceSelfTest.java
git commit -m "feat: add pluginv2 config helper"
```

## Task 2: Validate Plugin Metadata And Read Persisted Enablement

**Files:**
- Modify: `src/paisti/pluginv2/PluginService.java`
- Modify: `src/haven/test/PluginV2ServiceSelfTest.java`
- Test: `src/haven/test/PluginV2ServiceSelfTest.java`

- [ ] **Step 1: Write the failing test**

Expand `src/haven/test/PluginV2ServiceSelfTest.java` with real `PluginService` coverage.

```java
package haven.test;

import haven.PaistiServices;
import haven.PluginConfig;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.PluginDescription;
import paisti.plugin.PluginService;

import java.util.Collection;
import java.util.List;
import java.util.stream.Collectors;

public class PluginV2ServiceSelfTest {
    public static void main(String[] args) throws Exception {
	pluginConfigPersistsEnabledFlag();
	pluginServiceUsesDefaultEnablementWhenUnset();
	pluginServiceReadsPersistedEnablement();
	pluginServiceRejectsBlankConfigName();
	pluginServiceRejectsDuplicateConfigNames();
	pluginServiceOnlyReturnsVisiblePluginsSortedByName();
	System.out.println("PluginV2ServiceSelfTest OK");
    }

    private static void pluginServiceUsesDefaultEnablementWhenUnset() {
	PluginService service = new PluginService(new PaistiServices(null));
	service.loadPlugins(List.of(DefaultOnPlugin.class));

	PaistiPlugin plugin = service.getLoadedPlugins().iterator().next();
	require(service.isPluginEnabledInConfig(plugin), "default-on plugin should use enabledByDefault when no persisted value exists");
    }

    private static void pluginServiceReadsPersistedEnablement() {
	PluginConfig.enabled("pluginv2-selftest-default-off", false).set(true);
	PluginService service = new PluginService(new PaistiServices(null));
	service.loadPlugins(List.of(DefaultOffPlugin.class));

	PaistiPlugin plugin = service.getLoadedPlugins().iterator().next();
	require(service.isPluginEnabledInConfig(plugin), "persisted true should override default-off plugin");
    }

    private static void pluginServiceRejectsBlankConfigName() {
	PluginService service = new PluginService(new PaistiServices(null));
	service.loadPlugins(List.of(BlankConfigPlugin.class));
	require(service.getLoadedPlugins().isEmpty(), "blank configName should reject plugin load");
    }

    private static void pluginServiceRejectsDuplicateConfigNames() {
	PluginService service = new PluginService(new PaistiServices(null));
	service.loadPlugins(List.of(DefaultOnPlugin.class, DuplicateConfigPlugin.class));
	require(service.getLoadedPlugins().size() == 1, "duplicate configName should reject second plugin");
    }

    private static void pluginServiceOnlyReturnsVisiblePluginsSortedByName() {
	PluginService service = new PluginService(new PaistiServices(null));
	service.loadPlugins(List.of(VisibleZPlugin.class, HiddenPlugin.class, VisibleAPlugin.class));

	Collection<? extends PaistiPlugin> visible = service.getConfigurablePlugins();
	List<String> names = visible.stream().map(PaistiPlugin::getName).collect(Collectors.toList());
	require(names.equals(List.of("Alpha visible", "Zulu visible")), "visible plugin list mismatch: " + names);
    }

    @PluginDescription(name = "Default on", configName = "pluginv2-selftest-default-on", enabledByDefault = true)
    public static class DefaultOnPlugin extends NoOpPlugin {
	public DefaultOnPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Default off", configName = "pluginv2-selftest-default-off", enabledByDefault = false)
    public static class DefaultOffPlugin extends NoOpPlugin {
	public DefaultOffPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Blank config", configName = "")
    public static class BlankConfigPlugin extends NoOpPlugin {
	public BlankConfigPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Duplicate config", configName = "pluginv2-selftest-default-on")
    public static class DuplicateConfigPlugin extends NoOpPlugin {
	public DuplicateConfigPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Zulu visible", configName = "pluginv2-selftest-zulu")
    public static class VisibleZPlugin extends NoOpPlugin {
	public VisibleZPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Alpha visible", configName = "pluginv2-selftest-alpha")
    public static class VisibleAPlugin extends NoOpPlugin {
	public VisibleAPlugin(PaistiServices services) {super(services);}
    }

    @PluginDescription(name = "Hidden plugin", configName = "pluginv2-selftest-hidden", hidden = true)
    public static class HiddenPlugin extends NoOpPlugin {
	public HiddenPlugin(PaistiServices services) {super(services);}
    }

    public abstract static class NoOpPlugin extends PaistiPlugin {
	protected NoOpPlugin(PaistiServices services) {super(services);}

	public void startUp() {}

	public void shutDown() {}
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: the test fails because `PluginService` still accepts blank `configName`, does not reject duplicate `configName`, and does not expose `getConfigurablePlugins()`.

- [ ] **Step 3: Write minimal implementation**

Update `src/paisti/pluginv2/PluginService.java` by replacing `loadPlugins(...)`, adding `getConfigurablePlugins()`, and replacing `isPluginEnabledInConfig(...)`.

```java
package paisti.plugin;

import haven.PaistiServices;
import haven.PluginConfig;

import java.util.Collection;
import java.util.Comparator;
import java.util.stream.Collectors;

public class PluginService {
    public void loadPlugins(Collection<Class<? extends PaistiPlugin>> pluginClasses) {
	for (Class<? extends PaistiPlugin> pluginClass : pluginClasses) {
	    PluginDescription description = pluginClass.getAnnotation(PluginDescription.class);
	    if(description == null) {
		if(pluginClass.getSuperclass() == PaistiPlugin.class) {
		    System.err.println("Plugin class " + pluginClass.getName() + " extends PaistiPlugin but is missing @PluginDescription annotation, skipping.");
		}
		continue;
	    }
	    if(pluginClass.getSuperclass() != PaistiPlugin.class) {
		System.err.println("Plugin class " + pluginClass.getName() + " does not extend PaistiPlugin, skipping.");
		continue;
	    }

	    String configName = description.configName();
	    if((configName == null) || configName.trim().isEmpty()) {
		System.err.println("Plugin class " + pluginClass.getName() + " has blank configName, skipping.");
		continue;
	    }

	    boolean duplicateClass = loadedPlugins.stream().anyMatch(p -> p.getClass().equals(pluginClass));
	    if(duplicateClass) {
		System.out.println("Plugin " + pluginClass.getName() + " is already loaded, skipping duplicate.");
		continue;
	    }

	    boolean duplicateConfig = loadedPlugins.stream()
		.map(p -> p.getClass().getAnnotation(PluginDescription.class))
		.anyMatch(other -> other.configName().equals(configName));
	    if(duplicateConfig) {
		System.err.println("Plugin class " + pluginClass.getName() + " reuses configName '" + configName + "', skipping.");
		continue;
	    }

	    try {
		PaistiPlugin pluginInstance = pluginClass.getConstructor(PaistiServices.class).newInstance(services);
		loadedPlugins.add(pluginInstance);
		System.out.println("Loaded plugin: " + pluginClass.getName());
	    } catch (Exception e) {
		System.err.println("Failed to load plugin: " + pluginClass.getName());
		System.err.println(e);
	    }
	}
    }

    public Collection<? extends PaistiPlugin> getConfigurablePlugins() {
	return loadedPlugins.stream()
	    .filter(plugin -> !plugin.getClass().getAnnotation(PluginDescription.class).hidden())
	    .sorted(Comparator.comparing(PaistiPlugin::getName))
	    .collect(Collectors.toList());
    }

    public boolean isPluginEnabledInConfig(PaistiPlugin plugin) {
	PluginDescription pluginDescriptor = plugin.getClass().getAnnotation(PluginDescription.class);
	return PluginConfig.enabled(pluginDescriptor.configName(), pluginDescriptor.enabledByDefault()).get();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: `PluginV2ServiceSelfTest OK`.

- [ ] **Step 5: Commit**

```bash
git add src/paisti/pluginv2/PluginService.java src/haven/test/PluginV2ServiceSelfTest.java
git commit -m "feat: validate pluginv2 metadata and persisted state"
```

## Task 3: Render PluginV2 Options And Make DevTools Configurable

**Files:**
- Modify: `src/haven/OptWnd.java`
- Modify: `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`
- Modify: `src/haven/test/PluginV2ServiceSelfTest.java`
- Test: `src/haven/test/PluginV2ServiceSelfTest.java`

- [ ] **Step 1: Write the failing test**

Add a metadata regression test so the built-in plugin that currently implements outgoing widget logging actually appears in the new Options UI.

```java
package haven.test;

import paisti.plugin.DevToolsPlugin.DevToolsPlugin;
import paisti.plugin.PluginDescription;

public class PluginV2ServiceSelfTest {
    public static void main(String[] args) throws Exception {
	pluginConfigPersistsEnabledFlag();
	pluginServiceUsesDefaultEnablementWhenUnset();
	pluginServiceReadsPersistedEnablement();
	pluginServiceRejectsBlankConfigName();
	pluginServiceRejectsDuplicateConfigNames();
	pluginServiceOnlyReturnsVisiblePluginsSortedByName();
	devToolsPluginIsConfigurable();
	System.out.println("PluginV2ServiceSelfTest OK");
    }

    private static void devToolsPluginIsConfigurable() {
	PluginDescription description = DevToolsPlugin.class.getAnnotation(PluginDescription.class);
	require(description != null, "DevToolsPlugin must have PluginDescription");
	require("devtools".equals(description.configName()), "DevToolsPlugin must expose stable configName");
	require(!description.hidden(), "DevToolsPlugin must be visible so the options panel has a real plugin toggle");
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: the test fails because `DevToolsPlugin` has no `configName` and is still hidden.

- [ ] **Step 3: Write minimal implementation**

First update `src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java`.

```java
@PluginDescription(
    name = "Developer tools",
    configName = "devtools",
    description = "Allows the option of enabling various developer tools",
    enabledByDefault = true,
    hidden = false
)
public class DevToolsPlugin extends PaistiPlugin {
    // keep current implementation
}
```

Then update `src/haven/OptWnd.java` so the `Plugins` panel renders a new `Plugin V2` section after the legacy one during migration.

```java
private void initPluginOptions() {
    if(pluginOptionsInitialized)
        return;
    PluginManager.get().populateOptions(this, plugins);
    int y = plugins.contentsz().y;
    if(y > 0)
        y += UI.scale(15);

    Label header = plugins.add(new Label("Plugin V2"), 0, y);
    y = header.c.y + header.sz.y + UI.scale(5);
    for(PaistiPlugin plugin : ui.pluginService().getConfigurablePlugins()) {
        PluginDescription description = plugin.getClass().getAnnotation(PluginDescription.class);
        CFGBox toggle = plugins.add(new CFGBox(plugin.getName(), PluginConfig.enabled(description.configName(), description.enabledByDefault())), 0, y);
        toggle.set(val -> ui.pluginService().syncActivePlugins());
        y = toggle.c.y + toggle.sz.y + UI.scale(2);
        if(!description.description().trim().isEmpty()) {
            Label help = plugins.add(new Label(description.description()), UI.scale(20), y);
            y = help.c.y + help.sz.y + UI.scale(8);
        } else {
            y += UI.scale(8);
        }
    }
    finishPanel(plugins);
    pluginOptionsInitialized = true;
    if(current == plugins)
        cresize(plugins);
    else
        main.pack();
}
```

Add imports as needed:

```java


```

- [ ] **Step 4: Run test to verify it passes**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
```

Expected: `PluginV2ServiceSelfTest OK`.

- [ ] **Step 5: Manual verification and build**

Run:

```bash
ant bin -buildfile build.xml
java -jar bin/hafen.jar
```

Expected:

- Options -> Plugins shows the legacy plugin section first during migration
- a `Plugin V2` header appears below it
- `Developer tools` is visible as a checkbox
- clicking the checkbox immediately starts or stops the plugin and persists across restart

- [ ] **Step 6: Commit**

```bash
git add src/haven/OptWnd.java src/paisti/pluginv2/DevToolsPlugin/DevToolsPlugin.java src/haven/test/PluginV2ServiceSelfTest.java
git commit -m "feat: add pluginv2 options toggles"
```

## Task 4: Remove The Legacy Plugin Framework After New Flow Works

**Files:**
- Create: `src/haven/test/PluginV2LegacyRemovalSelfTest.java`
- Modify: `src/haven/MainFrame.java`
- Modify: `src/haven/UI.java`
- Modify: `src/haven/Window.java`
- Modify: `src/haven/GameUI.java`
- Modify: `src/haven/MenuGrid.java`
- Modify: `src/haven/OptWnd.java`
- Delete: `src/paisti/plugin/BuiltinPlugins.java`
- Delete: `src/paisti/plugin/ClientPlugin.java`
- Delete: `src/paisti/plugin/PluginAction.java`
- Delete: `src/paisti/plugin/PluginContext.java`
- Delete: `src/paisti/plugin/PluginManager.java`
- Delete: `src/paisti/plugin/PluginOptionSection.java`
- Delete: `src/paisti/plugin/PluginWindowEvent.java`
- Delete: `src/paisti/plugin/plugins/OutgoingWdgmsgPlugin.java`
- Delete: `src/haven/test/PluginRuntimeSelfTest.java`
- Delete: `src/haven/test/PluginMenuActionSelfTest.java`
- Delete: `resources/src/local/paginae/add/plugins/demo_toggle.res/`
- Test: `src/haven/test/PluginV2LegacyRemovalSelfTest.java`

- [ ] **Step 1: Write the failing test**

Create `src/haven/test/PluginV2LegacyRemovalSelfTest.java` using reflection and filesystem checks so it compiles before the old framework is removed.

```java
package haven.test;

import java.nio.file.Files;
import java.nio.file.Paths;

public class PluginV2LegacyRemovalSelfTest {
    public static void main(String[] args) throws Exception {
        requireMissing("paisti.plugin.PluginManager");
        requireMissing("paisti.plugin.PluginContext");
        requireMissing("paisti.plugin.PluginAction");
        require(Files.notExists(Paths.get("resources", "src", "local", "paginae", "add", "plugins", "demo_toggle.res", "meta")),
            "legacy plugin action resource directory should be removed");
        System.out.println("PluginV2LegacyRemovalSelfTest OK");
    }

    private static void requireMissing(String className) throws Exception {
        try {
            Class.forName(className);
            throw new AssertionError(className + " should be removed");
        } catch (ClassNotFoundException expected) {
            // expected
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run:

```bash
ant jar -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2LegacyRemovalSelfTest
```

Expected: the test fails because `paisti.plugin.PluginManager` still exists and the legacy resource directory is still present.

- [ ] **Step 3: Remove old boot and event-dispatch integration**

Update `src/haven/MainFrame.java` to delete the legacy runtime bootstrap and shutdown calls.

```java
setupres();
UI.Runner fun = null;
if(Bootstrap.replay.get() != null) {
    try {
        Transport.Playback player = new Transport.Playback(Files.newBufferedReader(Bootstrap.replay.get(), Utils.utf8));
        fun = new RemoteUI(new Session(player, new Session.User("Playback")));
        player.start();
    } catch(IOException e) {
        System.err.println("hafen: " + e.getMessage());
        System.exit(1);
    }
} else if(Bootstrap.servargs.get() != null) {
    try {
        fun = new RemoteUI(connect(Bootstrap.servargs.get()));
    } catch(ConnectionError e) {
        System.err.println("hafen: " + e.getMessage());
        System.exit(1);
    }
}
MainFrame f = new MainFrame(null);
status("visible");
if(initfullscreen.get())
    f.setfs();
f.run(fun);
```

Update `src/haven/UI.java`, `src/haven/Window.java`, and `src/haven/GameUI.java` to remove `PluginManager` dispatches.

```java
// UI.AddWidget.run()
pwdg.addchild(wdg, pargs);
WindowDetector.process(wdg, pwdg);

// UI.wdgmsg(...)
eventBus().post(new BeforeOutgoingWidgetMessage(this, sender, id, msg, args));
if(rcvr != null)
    rcvr.rcvmsg(id, msg, args);

// Window.report(...)
Reactor.WINDOW.onNext(new Pair<>(this, event));

// GameUI.attach(...)
ui.setGUI(this);
Config.initAutomapper(ui);
Timer.start(this);
super.attach(ui);
```

- [ ] **Step 4: Remove old menu and options integration**

Update `src/haven/MenuGrid.java` and `src/haven/OptWnd.java` to stop using the legacy plugin framework.

```java
// MenuGrid constructor/setup
initCustomPaginae();

// delete initPluginPaginae()

// OptWnd.initPluginOptions()
private void initPluginOptions() {
    if(pluginOptionsInitialized)
        return;
    int y = 0;
    Label header = plugins.add(new Label("Plugin V2"), 0, y);
    y = header.c.y + header.sz.y + UI.scale(5);
    for(PaistiPlugin plugin : ui.pluginService().getConfigurablePlugins()) {
        PluginDescription description = plugin.getClass().getAnnotation(PluginDescription.class);
        CFGBox toggle = plugins.add(new CFGBox(plugin.getName(), PluginConfig.enabled(description.configName(), description.enabledByDefault())), 0, y);
        toggle.set(val -> ui.pluginService().syncActivePlugins());
        y = toggle.c.y + toggle.sz.y + UI.scale(2);
        if(!description.description().trim().isEmpty()) {
            Label help = plugins.add(new Label(description.description()), UI.scale(20), y);
            y = help.c.y + help.sz.y + UI.scale(8);
        } else {
            y += UI.scale(8);
        }
    }
    finishPanel(plugins);
    pluginOptionsInitialized = true;
    if(current == plugins)
        cresize(plugins);
    else
        main.pack();
}
```

- [ ] **Step 5: Delete legacy code, tests, and assets**

Delete these files and directories.

```text
src/paisti/plugin/BuiltinPlugins.java
src/paisti/plugin/ClientPlugin.java
src/paisti/plugin/PluginAction.java
src/paisti/plugin/PluginContext.java
src/paisti/plugin/PluginManager.java
src/paisti/plugin/PluginOptionSection.java
src/paisti/plugin/PluginWindowEvent.java
src/paisti/plugin/plugins/OutgoingWdgmsgPlugin.java
src/haven/test/PluginRuntimeSelfTest.java
src/haven/test/PluginMenuActionSelfTest.java
resources/src/local/paginae/add/plugins/demo_toggle.res/
```

- [ ] **Step 6: Run tests and build to verify cleanup**

Run:

```bash
ant bin -buildfile build.xml
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
java -cp build/hafen.jar haven.test.PluginV2LegacyRemovalSelfTest
```

Expected:

- build succeeds
- `PluginV2ServiceSelfTest OK`
- `PluginV2LegacyRemovalSelfTest OK`
- Options -> Plugins shows only the `Plugin V2` section

- [ ] **Step 7: Commit**

```bash
git add src/haven/MainFrame.java src/haven/UI.java src/haven/Window.java src/haven/GameUI.java src/haven/MenuGrid.java src/haven/OptWnd.java src/haven/test/PluginV2LegacyRemovalSelfTest.java src/haven/test/PluginV2ServiceSelfTest.java
git add -u src/paisti/plugin src/haven/test resources/src/local/paginae/add/plugins
git commit -m "refactor: remove legacy plugin framework"
```

## Task 5: Final Verification

**Files:**
- Modify: none
- Test: `src/haven/test/PluginV2ServiceSelfTest.java`
- Test: `src/haven/test/PluginV2LegacyRemovalSelfTest.java`

- [ ] **Step 1: Run final build verification**

Run:

```bash
ant bin -buildfile build.xml
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run final self-tests**

Run:

```bash
java -cp build/hafen.jar haven.test.PluginV2ServiceSelfTest
java -cp build/hafen.jar haven.test.PluginV2LegacyRemovalSelfTest
```

Expected:

- `PluginV2ServiceSelfTest OK`
- `PluginV2LegacyRemovalSelfTest OK`

- [ ] **Step 3: Run final manual smoke test**

Run:

```bash
java -jar bin/hafen.jar
```

Expected:

- Options -> Plugins shows `Developer tools`
- toggling it immediately enables or disables outgoing widget-message logging
- closing and reopening the client preserves the checkbox state
- no legacy plugin actions or legacy plugin options remain

- [ ] **Step 4: Commit**

```bash
git status
git log -3 --oneline
```

Expected: clean working tree and the four feature commits from this plan.
