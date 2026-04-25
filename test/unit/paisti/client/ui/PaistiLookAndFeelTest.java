package paisti.client.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaistiLookAndFeelTest {
    @Test
    @Tag("unit")
    void flatlafIsEnabledByDefault() {
        assertTrue(PaistiLookAndFeel.flatlafEnabled(null));
    }

    @Test
    @Tag("unit")
    void flatlafCanBeDisabledWithFalseProperty() {
        assertFalse(PaistiLookAndFeel.flatlafEnabled("false"));
        assertFalse(PaistiLookAndFeel.flatlafEnabled("FALSE"));
        assertFalse(PaistiLookAndFeel.flatlafEnabled(" false "));
    }

    @Test
    @Tag("unit")
    void onlyFalseDisablesFlatlaf() {
        assertTrue(PaistiLookAndFeel.flatlafEnabled("true"));
        assertTrue(PaistiLookAndFeel.flatlafEnabled("0"));
        assertTrue(PaistiLookAndFeel.flatlafEnabled(""));
    }

    @Test
    @Tag("unit")
    void titleBarHeightScalesFromDpi() {
        assertEquals(32, PaistiLookAndFeel.titleBarHeightForDpi(96));
        assertEquals(48, PaistiLookAndFeel.titleBarHeightForDpi(144));
        assertEquals(64, PaistiLookAndFeel.titleBarHeightForDpi(192));
    }

    @Test
    @Tag("unit")
    void titleBarHeightHasSensibleLowerBoundForInvalidDpi() {
        assertEquals(32, PaistiLookAndFeel.titleBarHeightForDpi(0));
        assertEquals(32, PaistiLookAndFeel.titleBarHeightForDpi(-1));
    }
}
