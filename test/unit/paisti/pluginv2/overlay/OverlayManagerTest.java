package paisti.pluginv2.overlay;

import haven.PaistiServices;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.pluginv2.PaistiPlugin;

import java.lang.reflect.Method;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class OverlayManagerTest {
    private static final class TestPlugin extends PaistiPlugin {
        private TestPlugin(PaistiServices services) {
            super(services);
        }

        @Override
        public void startUp() {
        }

        @Override
        public void shutDown() {
        }

        private OverlayManager exposedOverlayManager() {
            return overlayManager();
        }
    }

    @Test
    @Tag("unit")
    void overlayApiExists() throws Exception {
        assertNotNull(Class.forName("paisti.pluginv2.overlay.PluginOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.ScreenOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapOverlay"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.OverlayRegistration"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.ScreenOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapWorldOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.MapScreenOverlayContext"));
        assertNotNull(Class.forName("paisti.pluginv2.overlay.OverlayManager"));
    }

    @Test
    @Tag("unit")
    void servicesExposeStableSharedOverlayManager() throws Exception {
        Method method = PaistiServices.class.getMethod("overlayManager");
        assertEquals("paisti.pluginv2.overlay.OverlayManager", method.getReturnType().getName());

        PaistiServices services = new PaistiServices();
        OverlayManager manager = services.overlayManager();

        assertNotNull(manager);
        assertSame(manager, services.overlayManager());
    }

    @Test
    @Tag("unit")
    void pluginBaseDelegatesToServicesOverlayManager() throws Exception {
        Method method = PaistiPlugin.class.getDeclaredMethod("overlayManager");
        assertEquals("paisti.pluginv2.overlay.OverlayManager", method.getReturnType().getName());

        PaistiServices services = new PaistiServices();
        TestPlugin plugin = new TestPlugin(services);

        assertSame(services.overlayManager(), plugin.exposedOverlayManager());
    }

    @Test
    @Tag("unit")
    void overlayManagerExposesOrderedOverlayLists() throws Exception {
        assertSame(List.class, OverlayManager.class.getMethod("screenOverlays").getReturnType());
        assertSame(List.class, OverlayManager.class.getMethod("mapOverlays").getReturnType());
    }
}
