package me.ender.plugin;

import haven.GameUI;
import haven.UI;
import haven.Widget;
import haven.Window;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
public class PluginContext {
    private final List<ClientStartHook> clientStartHooks = new ArrayList<>();
    private final List<ClientShutdownHook> clientShutdownHooks = new ArrayList<>();
    private final List<GameUiReadyHook> gameUiReadyHooks = new ArrayList<>();
    private final List<WindowHook> windowHooks = new ArrayList<>();
    private final List<WidgetHook> widgetHooks = new ArrayList<>();
    private final List<OutgoingWidgetMessageHook> outgoingWidgetMessageHooks = new ArrayList<>();
    private final List<PluginAction> actions = new ArrayList<>();
    private final List<PluginOptionSection> optionSections = new ArrayList<>();
    private boolean frozen;

    public void onClientStart(ClientStartHook hook) {
        ensureMutable();
        clientStartHooks.add(require(hook, "client start hook"));
    }

    public void onClientShutdown(ClientShutdownHook hook) {
        ensureMutable();
        clientShutdownHooks.add(require(hook, "client shutdown hook"));
    }

    public void onGameUiReady(GameUiReadyHook hook) {
        ensureMutable();
        gameUiReadyHooks.add(require(hook, "game-ui-ready hook"));
    }

    public void onWindow(WindowHook hook) {
        ensureMutable();
        windowHooks.add(require(hook, "window hook"));
    }

    public void onWidgetAdded(WidgetHook hook) {
        ensureMutable();
        widgetHooks.add(require(hook, "widget hook"));
    }

    public void onOutgoingWidgetMessage(OutgoingWidgetMessageHook hook) {
        ensureMutable();
        outgoingWidgetMessageHooks.add(require(hook, "outgoing widget message hook"));
    }

    public void addAction(PluginAction action) {
        ensureMutable();
        actions.add(require(action, "plugin action"));
    }

    public void addOptionSection(PluginOptionSection section) {
        ensureMutable();
        optionSections.add(require(section, "plugin option section"));
    }

    Snapshot freeze() {
        frozen = true;
        return new Snapshot(
            List.copyOf(clientStartHooks),
            List.copyOf(clientShutdownHooks),
            List.copyOf(gameUiReadyHooks),
            List.copyOf(windowHooks),
            List.copyOf(widgetHooks),
            List.copyOf(outgoingWidgetMessageHooks),
            List.copyOf(actions),
            List.copyOf(optionSections)
        );
    }

    private void ensureMutable() {
        if (frozen) {
            throw new IllegalStateException("plugin context is frozen");
        }
    }

    private static <T> T require(T value, String label) {
        if (value == null) {
            throw new IllegalArgumentException(label + " must not be null");
        }
        return value;
    }

    static class Snapshot {
        final List<ClientStartHook> clientStartHooks;
        final List<ClientShutdownHook> clientShutdownHooks;
        final List<GameUiReadyHook> gameUiReadyHooks;
        final List<WindowHook> windowHooks;
        final List<WidgetHook> widgetHooks;
        final List<OutgoingWidgetMessageHook> outgoingWidgetMessageHooks;
        final List<PluginAction> actions;
        final List<PluginOptionSection> optionSections;

        private Snapshot(List<ClientStartHook> clientStartHooks,
                         List<ClientShutdownHook> clientShutdownHooks,
                         List<GameUiReadyHook> gameUiReadyHooks,
                         List<WindowHook> windowHooks,
                         List<WidgetHook> widgetHooks,
                         List<OutgoingWidgetMessageHook> outgoingWidgetMessageHooks,
                         List<PluginAction> actions,
                         List<PluginOptionSection> optionSections) {
            this.clientStartHooks = Collections.unmodifiableList(clientStartHooks);
            this.clientShutdownHooks = Collections.unmodifiableList(clientShutdownHooks);
            this.gameUiReadyHooks = Collections.unmodifiableList(gameUiReadyHooks);
            this.windowHooks = Collections.unmodifiableList(windowHooks);
            this.widgetHooks = Collections.unmodifiableList(widgetHooks);
            this.outgoingWidgetMessageHooks = Collections.unmodifiableList(outgoingWidgetMessageHooks);
            this.actions = Collections.unmodifiableList(actions);
            this.optionSections = Collections.unmodifiableList(optionSections);
        }
    }

    @FunctionalInterface
    public interface ClientStartHook {
        void run();
    }

    @FunctionalInterface
    public interface ClientShutdownHook {
        void run();
    }

    @FunctionalInterface
    public interface GameUiReadyHook {
        void accept(GameUI gui);
    }

    @FunctionalInterface
    public interface WindowHook {
        void accept(Window window, PluginWindowEvent event);
    }

    @FunctionalInterface
    public interface WidgetHook {
        void accept(UI ui, Widget child, Widget parent);
    }

    @FunctionalInterface
    public interface OutgoingWidgetMessageHook {
        void accept(UI ui, Widget sender, int widgetId, String msg, Object[] args);
    }
}
