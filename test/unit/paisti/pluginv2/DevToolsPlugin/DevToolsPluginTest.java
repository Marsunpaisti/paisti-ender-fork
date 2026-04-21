package paisti.pluginv2.DevToolsPlugin;

import haven.PaistiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.pluginv2.overlay.MapOverlay;
import paisti.pluginv2.overlay.ScreenOverlay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsPluginTest {
    @Test
    @Tag("unit")
    void devToolsOverlayExamplesImplementExpectedContracts() throws Exception {
        assertTrue(ScreenOverlay.class.isAssignableFrom(Class.forName("paisti.pluginv2.DevToolsPlugin.DevToolsPluginScreenOverlay")));
        assertTrue(MapOverlay.class.isAssignableFrom(Class.forName("paisti.pluginv2.DevToolsPlugin.DevToolsPluginSceneOverlay")));
    }

    @Test
    @Tag("unit")
    void startupRegistersAndShutdownUnregistersDevToolsOverlays() {
        PaistiServices services = new PaistiServices();
        DevToolsPlugin plugin = new DevToolsPlugin(services);

        plugin.startUp();

        assertEquals(1, services.overlayManager().screenOverlays().size(), "expected startup to register the devtools screen overlay example");
        assertEquals(1, services.overlayManager().mapOverlays().size(), "expected startup to register the devtools map overlay example");

        plugin.shutDown();

        assertTrue(services.overlayManager().screenOverlays().isEmpty(), "expected shutdown to unregister the devtools screen overlay example");
        assertTrue(services.overlayManager().mapOverlays().isEmpty(), "expected shutdown to unregister the devtools map overlay example");
    }
}
