package paisti.client.ui;

import com.formdev.flatlaf.FlatClientProperties;
import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatSystemProperties;
import haven.Warning;

import javax.swing.*;
import java.awt.*;

public final class PaistiLookAndFeel {
    private static boolean flatlafActive;

    private PaistiLookAndFeel() {
    }

    public static void setup() {
        if(flatlafEnabled(System.getProperty("paisti.flatlaf"))) {
            try {
                System.setProperty(FlatSystemProperties.USE_WINDOW_DECORATIONS, "true");
                System.setProperty(FlatSystemProperties.MENUBAR_EMBEDDED, "true");
                flatlafActive = FlatDarkLaf.setup();
                if(flatlafActive)
                    return;
            } catch(Exception e) {
                new Warning(e, "FlatLaf initialization failed; falling back to system look and feel").issue();
            }
        }
        flatlafActive = false;
        setupSystemLookAndFeel();
    }

    static boolean flatlafEnabled(String value) {
        return(value == null || !value.trim().equalsIgnoreCase("false"));
    }

    public static boolean isFlatlafActive() {
        return(flatlafActive);
    }

    public static void applyWindowChrome(JFrame frame) {
        if(!flatlafActive)
            return;
        JRootPane root = frame.getRootPane();
        root.putClientProperty(FlatClientProperties.USE_WINDOW_DECORATIONS, true);
        root.putClientProperty(FlatClientProperties.TITLE_BAR_HEIGHT, titleBarHeightForDpi(currentScreenDpi(frame)));
    }

    static int titleBarHeightForDpi(int dpi) {
        int effectiveDpi = dpi > 0 ? dpi : 96;
        return Math.max(32, (int)Math.round(32 * (effectiveDpi / 96.0)));
    }

    private static int currentScreenDpi(JFrame frame) {
        int dpi = 96;
        try {
            dpi = Toolkit.getDefaultToolkit().getScreenResolution();
        } catch(Exception e) {
            new Warning(e, "could not determine screen DPI for title bar scaling").issue();
        }
        GraphicsConfiguration gc = frame.getGraphicsConfiguration();
        if(gc != null) {
            double sx = gc.getDefaultTransform().getScaleX();
            double sy = gc.getDefaultTransform().getScaleY();
            dpi = Math.max(dpi, (int)Math.round(96 * Math.max(sx, sy)));
        }
        return dpi;
    }

    private static void setupSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            new Warning(e, "AWT initialization failed").issue();
        }
    }
}
