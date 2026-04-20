package me.ender.plugin;

import me.ender.plugin.plugins.OutgoingWdgmsgPlugin;

import java.util.List;

public final class BuiltinPlugins {
    private static final List<ClientPlugin> PLUGINS = List.of(new OutgoingWdgmsgPlugin());

    private BuiltinPlugins() {
    }

    public static List<ClientPlugin> plugins() {
        return PLUGINS;
    }
}
