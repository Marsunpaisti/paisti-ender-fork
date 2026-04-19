package me.ender.plugin;

import me.ender.plugin.plugins.DemoPlugin;

import java.util.List;

public final class BuiltinPlugins {
    private static final List<ClientPlugin> PLUGINS = List.of(new DemoPlugin());

    private BuiltinPlugins() {
    }

    public static List<ClientPlugin> plugins() {
        return PLUGINS;
    }
}
