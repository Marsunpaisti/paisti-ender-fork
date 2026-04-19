package me.ender.plugin;

import haven.OptWnd;

public class PluginOptionSection {
    private final String pluginId;
    private final String title;
    private final Renderer renderer;

    private PluginOptionSection(String pluginId, String title, Renderer renderer) {
        this.pluginId = require(pluginId, "plugin id");
        this.title = require(title, "title");
        this.renderer = require(renderer, "renderer");
    }

    public static PluginOptionSection of(String pluginId, String title, Renderer renderer) {
        return new PluginOptionSection(pluginId, title, renderer);
    }

    public String pluginId() {
        return pluginId;
    }

    public String title() {
        return title;
    }

    int render(OptWnd wnd, OptWnd.Panel panel, int y) {
        return renderer.render(wnd, panel, y);
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

    @FunctionalInterface
    public interface Renderer {
        int render(OptWnd wnd, OptWnd.Panel panel, int y);
    }
}
