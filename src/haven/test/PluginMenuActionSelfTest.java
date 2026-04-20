package haven.test;

import haven.Window;
import paisti.plugin.BuiltinPlugins;
import paisti.plugin.PluginManager;
import paisti.plugin.PluginWindowEvent;
import paisti.plugin.plugins.OutgoingWdgmsgPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class PluginMenuActionSelfTest {
    public static void main(String[] args) {
        require(BuiltinPlugins.plugins() != null, "built-in plugin list must not be null");
        require(!BuiltinPlugins.plugins().isEmpty(), "built-in plugin list must not be empty");
        require(BuiltinPlugins.plugins().stream().anyMatch(OutgoingWdgmsgPlugin.class::isInstance),
            "built-in plugins should include the outgoing wdgmsg plugin");

        PluginManager.install(BuiltinPlugins.plugins());
        require(PluginManager.get().actions().stream().anyMatch(action -> "paginae/add/plugins/demo_toggle".equals(action.resourcePath())),
            "expected outgoing logger action resource path to be registered");
        require(PluginManager.get().optionSections().stream().anyMatch(section -> "Outgoing Wdgmsg Logger".equals(section.title())),
            "expected outgoing logger option section to be registered");
        require(PluginWindowEvent.from(Window.ON_SHOW).orElseThrow(() -> new AssertionError("missing show mapping")) == PluginWindowEvent.SHOW,
            "show should map to SHOW");

        try {
            String source = Files.readString(Paths.get("src", "me", "ender", "plugin", "plugins", "OutgoingWdgmsgPlugin.java"));
            require(source.contains("context.onOutgoingWidgetMessage"), "tooling plugin should subscribe to outgoing widget messages");
            require(!source.contains("context.onWindow"), "tooling plugin should no longer use the old demo window hook");
            require(source.contains("Outgoing Wdgmsg Logger"),
                "tooling plugin source should expose outgoing logger section wording");
            require(source.contains("Enable outgoing wdgmsg logger"),
                "tooling plugin source should expose outgoing logger toggle wording");
            require(source.contains("System.out.println"),
                "tooling plugin should mirror log lines to stdout for IDE console visibility");
        } catch (IOException e) {
            throw new AssertionError("failed to read tooling plugin source", e);
        }

        try {
            String actionData = Files.readString(Paths.get("resources", "src", "local", "paginae", "add", "plugins", "demo_toggle.res", "action", "action_0.data"));
            require(actionData.contains("Toggle Outgoing Wdgmsg Logger"), "action label should be updated to logger wording");
        } catch (IOException e) {
            throw new AssertionError("failed to read plugin action resource", e);
        }

        try {
            String paginaData = Files.readString(Paths.get("resources", "src", "local", "paginae", "add", "plugins", "demo_toggle.res", "pagina", "pagina_0.data"));
            require(paginaData.contains("logs all outgoing widget messages to chat"),
                "pagina help should describe outgoing widget message logging");
            require(paginaData.contains("compact summaries"),
                "pagina help should mention compact summaries");
            require(paginaData.toLowerCase().contains("typed/raw strings"),
                "pagina help should warn that typed/raw strings can be logged");
        } catch (IOException e) {
            throw new AssertionError("failed to read plugin pagina resource", e);
        }

        System.out.println("PluginMenuActionSelfTest OK");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
