package paisti.plugin.DevToolsPlugin;

import haven.Coord;
import haven.GOut;
import haven.render.BufPipe;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.awt.Color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DevToolsWorldPersistenceOverlayTest {
    @Test
    @Tag("unit")
    void colorResetRunsWhenRenderWorkThrows() {
        GOut g = new GOut(null, new BufPipe(), Coord.of(100, 100));

        RuntimeException error = assertThrows(RuntimeException.class, () -> DevToolsWorldPersistenceOverlay.withColorReset(g, () -> {
            g.chcolor(Color.BLUE);
            throw new RuntimeException("boom");
        }));

        assertEquals("boom", error.getMessage());
        assertEquals(Color.WHITE, g.getcolor());
    }
}
