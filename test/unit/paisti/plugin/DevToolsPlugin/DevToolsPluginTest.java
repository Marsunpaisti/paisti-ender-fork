package paisti.plugin.DevToolsPlugin;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.client.PaistiServices;

import static org.junit.jupiter.api.Assertions.assertTrue;

class DevToolsPluginTest {
    @Test
    @Tag("unit")
    void startupRegistersWorldPersistenceOverlay() {
        PaistiServices services = new PaistiServices();
        DevToolsPlugin plugin = new DevToolsPlugin(services);

        plugin.startUp();

        try {
            assertTrue(
                services.overlayManager().mapOverlays().stream().anyMatch(overlay -> overlay instanceof DevToolsWorldPersistenceOverlay),
                "expected DevTools startup to register the world persistence overlay"
            );
        } finally {
            plugin.shutDown();
        }
    }
}
