package me.ender.plugin;

public interface ClientPlugin {
    String id();

    void register(PluginContext context);
}
