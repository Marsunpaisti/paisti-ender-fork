package paisti.plugin;

import haven.GameUI;
import haven.MenuGrid;
import haven.OptWnd;
import haven.OwnerContext;
import haven.UI;
import haven.Widget;
import haven.Window;
import paisti.hooks.EventBus;
import paisti.hooks.events.BeforeOutgoingWidgetMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PluginManager {
    private static final PluginManager INSTANCE = new PluginManager();

    // Install swaps boot-time plugin state in-place so callers holding PluginManager.get()
    // always observe the same singleton object across setup and self-tests.
    private volatile State state = State.empty();

    private PluginManager() {
    }

    public static PluginManager install(List<? extends ClientPlugin> plugins) {
        INSTANCE.replaceState(buildState(plugins));
        return INSTANCE;
    }

    public static PluginManager get() {
        return INSTANCE;
    }

    public List<PluginAction> actions() {
        State snapshot = state;
        List<PluginAction> actions = new ArrayList<>();
        for (Entry entry : snapshot.entries) {
            if (!entry.quarantined) {
                actions.addAll(entry.snapshot.actions);
            }
        }
        return Collections.unmodifiableList(actions);
    }

    public List<PluginOptionSection> optionSections() {
        State snapshot = state;
        List<PluginOptionSection> sections = new ArrayList<>();
        for (Entry entry : snapshot.entries) {
            if (!entry.quarantined) {
                sections.addAll(entry.snapshot.optionSections);
            }
        }
        return Collections.unmodifiableList(sections);
    }

    public void dispatchClientStart() {
        State snapshot = state;
        for (Entry entry : snapshot.entries) {
            dispatchClientStart(entry);
        }
    }

    public void dispatchClientShutdown() {
        State snapshot = state;
        for (Entry entry : snapshot.entries) {
            dispatchClientShutdown(entry);
        }
    }

    public void dispatchGameUiReady(GameUI gui) {
        State snapshot = state;
        for (Entry entry : snapshot.entries) {
            dispatchGameUiReady(entry, gui);
        }
    }

    public void dispatchWindow(Window window, PluginWindowEvent event) {
        State snapshot = state;
        for (Entry entry : snapshot.entries) {
            dispatchWindow(entry, window, event);
        }
    }

    public void dispatchWidgetAdded(UI ui, Widget child, Widget parent) {
        State snapshot = state;
        for (Entry entry : snapshot.entries) {
            dispatchWidgetAdded(entry, ui, child, parent);
        }
    }

    public void dispatchOutgoingWidgetMessage(UI ui, Widget sender, int widgetId, String msg, Object[] args) {
        State snapshot = state;
        Object[] safeArgs = copyOutgoingArgs(args);
        for (Entry entry : snapshot.entries) {
            dispatchOutgoingWidgetMessage(entry, ui, sender, widgetId, msg, safeArgs);
        }
    }

    public boolean perform(PluginAction action, OwnerContext ctx, MenuGrid.Interaction interaction) {
        return performResult(action, ctx, interaction) == PerformResult.PERFORMED_TRUE;
    }

    public PerformResult performResult(PluginAction action, OwnerContext ctx, MenuGrid.Interaction interaction) {
        require(action, "plugin action");
        State snapshot = state;
        Entry entry = snapshot.actionOwners.get(action);
        if ((entry == null) || entry.quarantined) {
            return PerformResult.UNAVAILABLE;
        }
        try {
            return action.perform(ctx, interaction) ? PerformResult.PERFORMED_TRUE : PerformResult.PERFORMED_FALSE;
        } catch (Throwable error) {
            rethrowFatal(error);
            quarantine(entry, "action failed: " + action.id(), error);
            return PerformResult.UNAVAILABLE_AFTER_FAILURE;
        }
    }

    public boolean isAvailable(PluginAction action) {
        require(action, "plugin action");
        State snapshot = state;
        Entry entry = snapshot.actionOwners.get(action);
        return (entry != null) && !entry.quarantined;
    }

    public java.util.Optional<Boolean> toggleState(PluginAction action) {
        require(action, "plugin action");
        State snapshot = state;
        Entry entry = snapshot.actionOwners.get(action);
        if ((entry == null) || entry.quarantined || !action.hasToggleState()) {
            return java.util.Optional.empty();
        }
        try {
            return java.util.Optional.of(action.toggleState());
        } catch (Throwable error) {
            rethrowFatal(error);
            quarantine(entry, "toggle state failed: " + action.id(), error);
            return java.util.Optional.empty();
        }
    }

    public void populateOptions(OptWnd wnd, OptWnd.Panel panel) {
        State snapshot = state;
        int y = 0;
        for (Entry entry : snapshot.entries) {
            if (entry.quarantined) {
                continue;
            }
            for (PluginOptionSection section : entry.snapshot.optionSections) {
                if (entry.quarantined) {
                    break;
                }
                try {
                    OptWnd.Panel sectionPanel = createSectionPanel(wnd, panel);
                    sectionPanel.visible = true;
                    int nextY = section.render(wnd, sectionPanel, 0);
                    if (nextY < 0) {
                        throw new IllegalArgumentException("invalid option cursor: " + nextY);
                    }
                    sectionPanel.pack();
                    panel.add(sectionPanel, 0, y);
                    y += Math.max(nextY, sectionPanel.sz.y);
                } catch (Throwable error) {
                    rethrowFatal(error);
                    quarantine(entry, "option section failed: " + section.title(), error);
                }
            }
        }
    }

    private synchronized void replaceState(State newState) {
        state = newState;
    }

    private static State buildState(List<? extends ClientPlugin> plugins) {
        require(plugins, "plugin list");
        Map<String, ClientPlugin> unique = new LinkedHashMap<>();
        for (ClientPlugin plugin : plugins) {
            requirePlugin(plugin);
            String id = require(plugin.id(), "plugin id");
            ClientPlugin previous = unique.putIfAbsent(id, plugin);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate plugin id: " + id);
            }
        }

        List<Entry> entries = new ArrayList<>();
        Map<PluginAction, Entry> actionOwners = new IdentityHashMap<>();
        Set<String> actionIds = new HashSet<>();
        Set<String> actionResourcePaths = new HashSet<>();
        Set<String> optionSectionKeys = new HashSet<>();
        for (ClientPlugin plugin : unique.values()) {
            PluginContext context = new PluginContext();
            try {
                plugin.register(context);
                PluginContext.Snapshot snapshot = context.freeze();
                Map<PluginAction, Entry> pluginActionOwners = new IdentityHashMap<>();
                Set<String> pluginActionIds = new HashSet<>();
                Set<String> pluginActionResourcePaths = new HashSet<>();
                Set<String> pluginOptionSectionKeys = new HashSet<>();
                Entry entry = new Entry(plugin, snapshot);
                validateOwnership(plugin.id(), entry, snapshot, actionOwners, pluginActionOwners);
                validateKeys(snapshot, actionIds, actionResourcePaths, optionSectionKeys,
                    pluginActionIds, pluginActionResourcePaths, pluginOptionSectionKeys);
                entries.add(entry);
                actionOwners.putAll(pluginActionOwners);
                actionIds.addAll(pluginActionIds);
                actionResourcePaths.addAll(pluginActionResourcePaths);
                optionSectionKeys.addAll(pluginOptionSectionKeys);
            } catch (IllegalArgumentException e) {
                log(plugin.id(), "registration failed", e);
            } catch (Throwable error) {
                rethrowFatal(error);
                log(plugin.id(), "registration failed", error);
            }
        }
        return new State(List.copyOf(entries), actionOwners);
    }

    private static void requirePlugin(ClientPlugin plugin) {
        if (plugin == null) {
            throw new IllegalArgumentException("plugin must not be null");
        }
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        if ((value instanceof String) && ((String) value).trim().isEmpty()) {
            throw new IllegalArgumentException(label + " must not be blank");
        }
        return value;
    }

    private static void validateOwnership(String pluginId, Entry entry, PluginContext.Snapshot snapshot,
                                          Map<PluginAction, Entry> actionOwners, Map<PluginAction, Entry> pluginActionOwners) {
        for (PluginAction action : snapshot.actions) {
            if (!pluginId.equals(action.pluginId())) {
                throw new IllegalArgumentException("plugin action owner mismatch: " + action.id());
            }
            if (actionOwners.containsKey(action) || pluginActionOwners.containsKey(action)) {
                throw new IllegalArgumentException("plugin action instance reused: " + action.id());
            }
            pluginActionOwners.put(action, entry);
        }
        for (PluginOptionSection section : snapshot.optionSections) {
            if (!pluginId.equals(section.pluginId())) {
                throw new IllegalArgumentException("plugin option section owner mismatch: " + section.title());
            }
        }
    }

    private static void validateKeys(PluginContext.Snapshot snapshot,
                                     Set<String> actionIds, Set<String> actionResourcePaths, Set<String> optionSectionKeys,
                                     Set<String> pluginActionIds, Set<String> pluginActionResourcePaths, Set<String> pluginOptionSectionKeys) {
        for (PluginAction action : snapshot.actions) {
            if (actionIds.contains(action.id()) || !pluginActionIds.add(action.id())) {
                throw new IllegalArgumentException("duplicate plugin action id: " + action.id());
            }
            if (actionResourcePaths.contains(action.resourcePath()) || !pluginActionResourcePaths.add(action.resourcePath())) {
                throw new IllegalArgumentException("duplicate plugin action resource path: " + action.resourcePath());
            }
        }
        for (PluginOptionSection section : snapshot.optionSections) {
            String key = optionSectionKey(section);
            if (optionSectionKeys.contains(key) || !pluginOptionSectionKeys.add(key)) {
                throw new IllegalArgumentException("duplicate plugin option section key: " + key);
            }
        }
    }

    private static String optionSectionKey(PluginOptionSection section) {
        return section.pluginId() + ":" + section.title();
    }

    private void dispatchClientStart(Entry entry) {
        if (entry.quarantined) {
            return;
        }
        for (PluginContext.ClientStartHook hook : entry.snapshot.clientStartHooks) {
            if (entry.quarantined) {
                break;
            }
            try {
                hook.run();
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "client start hook failed", error);
            }
        }
    }

    private void dispatchClientShutdown(Entry entry) {
        for (PluginContext.ClientShutdownHook hook : entry.snapshot.clientShutdownHooks) {
            try {
                hook.run();
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "client shutdown hook failed", error);
            }
        }
    }

    private void dispatchGameUiReady(Entry entry, GameUI gui) {
        if (entry.quarantined) {
            return;
        }
        for (PluginContext.GameUiReadyHook hook : entry.snapshot.gameUiReadyHooks) {
            if (entry.quarantined) {
                break;
            }
            try {
                hook.accept(gui);
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "game-ui-ready hook failed", error);
            }
        }
    }

    private void dispatchWindow(Entry entry, Window window, PluginWindowEvent event) {
        if (entry.quarantined) {
            return;
        }
        for (PluginContext.WindowHook hook : entry.snapshot.windowHooks) {
            if (entry.quarantined) {
                break;
            }
            try {
                hook.accept(window, event);
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "window hook failed", error);
            }
        }
    }

    private void dispatchWidgetAdded(Entry entry, UI ui, Widget child, Widget parent) {
        if (entry.quarantined) {
            return;
        }
        for (PluginContext.WidgetHook hook : entry.snapshot.widgetHooks) {
            if (entry.quarantined) {
                break;
            }
            try {
                hook.accept(ui, child, parent);
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "widget hook failed", error);
            }
        }
    }

    private void dispatchOutgoingWidgetMessage(Entry entry, UI ui, Widget sender, int widgetId, String msg, Object[] args) {
        if (entry.quarantined) {
            return;
        }
        for (PluginContext.OutgoingWidgetMessageHook hook : entry.snapshot.outgoingWidgetMessageHooks) {
            if (entry.quarantined) {
                break;
            }
            try {
                hook.accept(ui, sender, widgetId, msg, copyOutgoingArgs(args));
            } catch (Throwable error) {
                rethrowFatal(error);
                quarantine(entry, "outgoing widget message hook failed", error);
            }
        }
    }

    private static Object[] copyOutgoingArgs(Object[] args) {
        return (args == null) ? new Object[0] : (Object[]) copyOutgoingValue(args);
    }

    private static Object copyOutgoingValue(Object value) {
        if (value == null) {
            return null;
        }
        Class<?> type = value.getClass();
        if (!type.isArray()) {
            return value;
        }
        if (value instanceof Object[]) {
            Object[] source = (Object[]) value;
            Object[] copy = new Object[source.length];
            for (int i = 0; i < source.length; i++) {
                copy[i] = copyOutgoingValue(source[i]);
            }
            return copy;
        }
        if (value instanceof byte[]) {
            return ((byte[]) value).clone();
        }
        if (value instanceof short[]) {
            return ((short[]) value).clone();
        }
        if (value instanceof int[]) {
            return ((int[]) value).clone();
        }
        if (value instanceof long[]) {
            return ((long[]) value).clone();
        }
        if (value instanceof float[]) {
            return ((float[]) value).clone();
        }
        if (value instanceof double[]) {
            return ((double[]) value).clone();
        }
        if (value instanceof char[]) {
            return ((char[]) value).clone();
        }
        if (value instanceof boolean[]) {
            return ((boolean[]) value).clone();
        }
        if (value instanceof Object) {
            return ((Object[]) value).clone();
        }
        return value;
    }

    private void quarantine(Entry entry, String message, Throwable error) {
        if (entry.tryQuarantine()) {
            log(entry.plugin.id(), message, error);
        }
    }

    private static void log(String id, String message, Throwable error) {
        System.err.println("[plugin:" + id + "] " + message + ": " + error);
        error.printStackTrace(System.err);
    }

    private static void rethrowFatal(Throwable error) {
        if (error instanceof ThreadDeath) {
            throw (ThreadDeath) error;
        }
        if (error instanceof VirtualMachineError) {
            throw (VirtualMachineError) error;
        }
    }

    private static OptWnd.Panel createSectionPanel(OptWnd wnd, OptWnd.Panel outer) {
        if (wnd != null) {
            return wnd.new Panel();
        }
        try {
            java.lang.reflect.Constructor<? extends OptWnd.Panel> ctor = outer.getClass().getDeclaredConstructor(OptWnd.class);
            ctor.setAccessible(true);
            return ctor.newInstance(new Object[] {null});
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("failed to create plugin option staging panel", e);
        }
    }

    public enum PerformResult {
        PERFORMED_TRUE,
        PERFORMED_FALSE,
        UNAVAILABLE,
        UNAVAILABLE_AFTER_FAILURE
    }

    private static class Entry {
        private final ClientPlugin plugin;
        private final PluginContext.Snapshot snapshot;
        private volatile boolean quarantined;

        private Entry(ClientPlugin plugin, PluginContext.Snapshot snapshot) {
            this.plugin = plugin;
            this.snapshot = snapshot;
        }

        private synchronized boolean tryQuarantine() {
            if (quarantined) {
                return false;
            }
            quarantined = true;
            return true;
        }
    }

    private static class State {
        private final List<Entry> entries;
        private final Map<PluginAction, Entry> actionOwners;

        private State(List<Entry> entries, Map<PluginAction, Entry> actionOwners) {
            this.entries = entries;
            this.actionOwners = Collections.unmodifiableMap(new IdentityHashMap<>(actionOwners));
        }

        private static State empty() {
            return new State(Collections.emptyList(), Collections.emptyMap());
        }
    }
}
