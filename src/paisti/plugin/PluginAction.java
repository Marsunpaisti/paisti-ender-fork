package paisti.plugin;

import haven.MenuGrid;
import haven.OwnerContext;
import me.ender.CustomPaginaAction;

import java.util.function.Supplier;

public class PluginAction {
    private final String pluginId;
    private final String id;
    private final String resourcePath;
    private final CustomPaginaAction action;
    private final Supplier<Boolean> toggleState;

    private PluginAction(String pluginId, String id, String resourcePath, CustomPaginaAction action, Supplier<Boolean> toggleState) {
        this.pluginId = require(pluginId, "plugin id");
        this.id = require(id, "action id");
        this.resourcePath = require(resourcePath, "resource path");
        this.action = require(action, "action");
        this.toggleState = toggleState;
    }

    public static PluginAction of(String pluginId, String id, String resourcePath, CustomPaginaAction action) {
        return new PluginAction(pluginId, id, resourcePath, action, null);
    }

    public static PluginAction of(String pluginId, String id, String resourcePath, CustomPaginaAction action, Supplier<Boolean> toggleState) {
        return new PluginAction(pluginId, id, resourcePath, action, require(toggleState, "toggle state"));
    }

    public String pluginId() {
        return pluginId;
    }

    public String id() {
        return id;
    }

    public String resourcePath() {
        return resourcePath;
    }

    public boolean hasToggleState() {
        return toggleState != null;
    }

    boolean toggleState() {
        return toggleState.get();
    }

    boolean perform(OwnerContext ctx, MenuGrid.Interaction interaction) {
        return action.perform(ctx, interaction);
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
}
