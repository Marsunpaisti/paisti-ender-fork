package paisti.plugin;

import com.google.common.collect.ImmutableList;
import haven.Config;
import paisti.client.PaistiServices;
import paisti.plugin.DevToolsPlugin.DevToolsPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

public class PluginService {
    private static final ImmutableList<Class<? extends PaistiPlugin>> BUILT_IN_PLUGINS = ImmutableList.of(
	DevToolsPlugin.class
    );
    private final PaistiServices services;
    private final List<PaistiPlugin> loadedPlugins = new CopyOnWriteArrayList<PaistiPlugin>();
    private final List<PaistiPlugin> activePlugins = new CopyOnWriteArrayList<>();

    public PluginService(PaistiServices services) {
	this.services = services;
    }

    private static void ensureMainConfigInitialized() {
	// PluginConfig reads CFG-backed state from config.json; touching the file here forces the
	// main config path to initialize before those reads happen in a fresh process.
	Config.getFile("config.json");
    }

    public void loadPlugins(Collection<Class<? extends PaistiPlugin>> pluginClasses) {
	ensureMainConfigInitialized();
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

	    // Check for duplicates
	    boolean alreadyLoaded = loadedPlugins.stream().anyMatch(p -> p.getClass().equals(pluginClass));
	    if(alreadyLoaded) {
		System.out.println("Plugin " + pluginClass.getName() + " is already loaded, skipping duplicate.");
		continue;
	    }

	    try {
		PluginConfig.enabled(description.configName(), description.enabledByDefault());
	    } catch (IllegalArgumentException e) {
		System.err.println("Plugin class " + pluginClass.getName() + " has invalid configName: " + description.configName() + ", skipping.");
		System.err.println(e.getMessage());
		continue;
	    }

	    boolean duplicateConfigName = loadedPlugins.stream().anyMatch(p -> {
		PluginDescription existing = p.getClass().getAnnotation(PluginDescription.class);
		return (existing != null) && existing.configName().equals(description.configName());
	    });
	    if(duplicateConfigName) {
		System.err.println("Plugin class " + pluginClass.getName() + " uses duplicate configName '" + description.configName() + "', skipping.");
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

    public void loadBuiltInPlugins() {
	loadPlugins(BUILT_IN_PLUGINS);
    }

    public void syncActivePlugins() {
	for (PaistiPlugin plugin : getLoadedPlugins()) {
	    boolean enabled = isPluginEnabledInConfig(plugin);
	    boolean isActive = activePlugins.contains(plugin);
	    if(enabled && !isActive) {
		startPlugin(plugin);
	    } else if(!enabled && isActive) {
		stopPlugin(plugin);
	    }
	}
    }

    public Collection<? extends PaistiPlugin> getLoadedPlugins() {
	return loadedPlugins;
    }

    public List<PaistiPlugin> getConfigurablePlugins() {
	List<PaistiPlugin> plugins = new ArrayList<>();
	for (PaistiPlugin plugin : loadedPlugins) {
	    PluginDescription description = plugin.getClass().getAnnotation(PluginDescription.class);
	    if((description != null) && !description.hidden()) {
		plugins.add(plugin);
	    }
	}
	plugins.sort(Comparator.comparing(PaistiPlugin::getName));
	return plugins;
    }

    /**
     * Test if a plugin is enabled, which causes the client to attempt to start it on boot
     *
     * @param plugin
     * @return
     */
    public boolean isPluginEnabledInConfig(PaistiPlugin plugin) {
	final PluginDescription pluginDescriptor = plugin.getClass().getAnnotation(PluginDescription.class);
	ensureMainConfigInitialized();
	return PluginConfig.enabled(pluginDescriptor.configName(), pluginDescriptor.enabledByDefault()).get();
    }

    public void startPlugin(PaistiPlugin plugin) {
	if(!loadedPlugins.contains(plugin)) {
	    System.err.println("Plugin " + plugin.getClass().getName() + " is not loaded, cannot start.");
	    return;
	}

	if(activePlugins.contains(plugin)) return;
	try {
	    plugin.startUp();
	    activePlugins.add(plugin);
	    System.out.println("Started plugin: " + plugin.getClass().getName());
	} catch (Throwable e) {
	    System.err.println("Error while starting plugin: " + plugin.getClass().getName());
	    System.err.println(e);
	    try {
		plugin.shutDown();
	    } catch (Throwable e2) {
		System.err.println("Error while shutting down plugin after failed start: " + plugin.getClass().getName());
		System.err.println(e2);
	    }
	    activePlugins.remove(plugin);
	}
    }

    public void stopPlugin(PaistiPlugin plugin) {
	if(!loadedPlugins.contains(plugin)) {
	    System.err.println("Plugin " + plugin.getClass().getName() + " is not loaded, cannot stop.");
	    return;
	}
	if(!activePlugins.contains(plugin)) return;
	activePlugins.remove(plugin);
	try {
	    plugin.shutDown();
	    System.out.println("Stopped plugin: " + plugin.getClass().getName());
	} catch (Throwable e) {
	    System.err.println("Error while shutting down plugin: " + plugin.getClass().getName());
	    System.err.println(e);
	}
    }

    public void stopAll() {
	for (PaistiPlugin plugin : activePlugins) {
	    stopPlugin(plugin);
	}
    }
}
