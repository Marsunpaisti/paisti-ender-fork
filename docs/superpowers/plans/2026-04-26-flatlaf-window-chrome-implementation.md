# FlatLaf Window Chrome Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add RuneLite-like dark FlatLaf window chrome to the main client window while preserving current renderer, fullscreen, and fallback behavior.

**Architecture:** Install FlatLaf through a Paisti-owned initializer, migrate `haven.MainFrame` from AWT `Frame` to Swing `JFrame`, and enable FlatLaf root-pane window decorations only when FlatLaf is active. Keep the change limited to the main window chrome and leave the Paisti tab bar/in-game UI unchanged.

**Tech Stack:** Java 11 source target, Ant, Swing/AWT, FlatLaf 3.7, JUnit 5.

---

## File Structure

- Create: `src/paisti/client/ui/PaistiLookAndFeel.java`
  - Owns FlatLaf setup, fallback setup, and the `paisti.flatlaf` system-property escape hatch.
- Create: `test/unit/paisti/client/ui/PaistiLookAndFeelTest.java`
  - Covers property parsing and avoids creating visible native windows.
- Modify: `src/haven/MainFrame.java`
  - Extends `JFrame`, delegates look-and-feel setup, uses Swing content pane APIs, and applies custom chrome properties.
- Modify: `build.xml`
  - Adds `lib/flatlaf-3.7.jar` to compile/test paths, manifest classpath, and `bin`/`bin-dev` packaging.
- Add binary dependency: `lib/flatlaf-3.7.jar`
  - Download from Maven Central: `https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7/flatlaf-3.7.jar`.

---

### Task 1: Add FlatLaf Dependency To Ant Build

**Files:**
- Modify: `build.xml:87-100`
- Modify: `build.xml:203-213`
- Modify: `build.xml:228-279`
- Add: `lib/flatlaf-3.7.jar`

- [ ] **Step 1: Download the FlatLaf jar**

Run:

```powershell
Invoke-WebRequest -Uri "https://repo1.maven.org/maven2/com/formdev/flatlaf/3.7/flatlaf-3.7.jar" -OutFile "lib/flatlaf-3.7.jar"
```

Expected: `lib/flatlaf-3.7.jar` exists and is non-empty.

- [ ] **Step 2: Add the jar to the client classpath**

Patch `build.xml` inside `<path id="hafen-client.classpath">`:

```xml
    <pathelement path="lib/flatlaf-3.7.jar" />
```

Place it after the existing `lib/jsr305-3.0.2.jar` entry.

- [ ] **Step 3: Package FlatLaf as an external runtime jar**

Patch the `jar` manifest `Class-Path` in `build.xml` to include:

```text
flatlaf-3.7.jar
```

Also copy `lib/flatlaf-3.7.jar` into `build`, `bin`, and `bin-dev` beside `hafen.jar`.

Expected: the packaged client loads FlatLaf from the external jar and preserves FlatLaf's multi-release jar metadata.

- [ ] **Step 4: Compile to verify the dependency is visible**

Run:

```powershell
ant clean-code hafen-client -buildfile build.xml
```

Expected: compile succeeds, or fails only because FlatLaf is not referenced yet. There should be no missing-jar classpath error.

---

### Task 2: Add Paisti Look And Feel Initializer

**Files:**
- Create: `src/paisti/client/ui/PaistiLookAndFeel.java`
- Create: `test/unit/paisti/client/ui/PaistiLookAndFeelTest.java`

- [ ] **Step 1: Write tests for the property escape hatch**

Create `test/unit/paisti/client/ui/PaistiLookAndFeelTest.java`:

```java
package paisti.client.ui;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

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
}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected: compile fails because `paisti.client.ui.PaistiLookAndFeel` does not exist yet.

- [ ] **Step 3: Add the initializer implementation**

Create `src/paisti/client/ui/PaistiLookAndFeel.java`:

```java
package paisti.client.ui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.FlatSystemProperties;

import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.UIManager;

public final class PaistiLookAndFeel {
    private static boolean flatlafActive;

    private PaistiLookAndFeel() {
    }

    public static void setup() {
        if(flatlafEnabled(System.getProperty("paisti.flatlaf"))) {
            try {
                System.setProperty(FlatSystemProperties.USE_WINDOW_DECORATIONS, "true");
                System.setProperty(FlatSystemProperties.MENU_BAR_EMBEDDED, "true");
                flatlafActive = FlatDarkLaf.setup();
                if(flatlafActive)
                    return;
            } catch(Exception e) {
                new haven.Warning(e, "FlatLaf initialization failed; falling back to system look and feel").issue();
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
        root.putClientProperty(FlatLaf.USE_WINDOW_DECORATIONS, true);
    }

    private static void setupSystemLookAndFeel() {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch(Exception e) {
            new haven.Warning(e, "AWT initialization failed").issue();
        }
    }
}
```

- [ ] **Step 4: Run tests to verify the initializer passes**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected: tests compile and the new `PaistiLookAndFeelTest` passes.

---

### Task 3: Migrate MainFrame To JFrame And Apply Chrome

**Files:**
- Modify: `src/haven/MainFrame.java:29-38`
- Modify: `src/haven/MainFrame.java:87-94`
- Modify: `src/haven/MainFrame.java:255-280`
- Modify: `src/haven/MainFrame.java:138-155`

- [ ] **Step 1: Update `MainFrame` imports and superclass**

Patch `src/haven/MainFrame.java`:

```java
import javax.swing.JFrame;
```

Change the class declaration from:

```java
public class MainFrame extends java.awt.Frame implements Console.Directory {
```

to:

```java
public class MainFrame extends JFrame implements Console.Directory {
```

- [ ] **Step 2: Delegate AWT initialization to PaistiLookAndFeel**

Replace `initawt()` with:

```java
    public static void initawt() {
        try {
            System.setProperty("apple.awt.application.name", "Haven & Hearth");
            paisti.client.ui.PaistiLookAndFeel.setup();
        } catch(Exception e) {
            new Warning(e, "AWT initialization failed").issue();
        }
    }
```

- [ ] **Step 3: Use the Swing content pane in the constructor**

Replace this constructor block:

```java
    setLayout(new BorderLayout());
    add(wrapRenderer(pp), BorderLayout.CENTER);
```

with:

```java
    paisti.client.ui.PaistiLookAndFeel.applyWindowChrome(this);
    getContentPane().setLayout(new BorderLayout());
    getContentPane().add(wrapRenderer(pp), BorderLayout.CENTER);
```

Keep the surrounding size, `pack()`, icon, visibility, and focus code unchanged.

- [ ] **Step 4: Restore custom chrome after returning from fullscreen**

In `setwnd()`, after `setUndecorated(false);`, add:

```java
        paisti.client.ui.PaistiLookAndFeel.applyWindowChrome(this);
```

This keeps fullscreen's undecorated behavior intact and reapplies FlatLaf chrome for normal windowed mode.

- [ ] **Step 5: Compile the frame migration**

Run:

```powershell
ant clean-code hafen-client -buildfile build.xml
```

Expected: compile succeeds. If compile fails due `java.awt.Frame` constants, qualify or import the equivalent `JFrame`/`Frame` constants only where needed.

---

### Task 4: Verify Tests And Packaging

**Files:**
- Test-only task unless verification reveals a compile failure.

- [ ] **Step 1: Run unit tests**

Run:

```powershell
ant test-unit -buildfile build.xml
```

Expected: all unit tests pass, including `PMainFrameTest` and `PaistiLookAndFeelTest`.

- [ ] **Step 2: Build the distributable client**

Run:

```powershell
ant bin -buildfile build.xml
```

Expected: `bin/hafen.jar` builds successfully and contains FlatLaf classes through `build/classes-lib`.

- [ ] **Step 3: Launch fallback mode**

Run:

```powershell
java -Dpaisti.flatlaf=false -jar bin/hafen.jar
```

Expected: client launches using the system look and feel path. Close the client after confirming startup.

- [ ] **Step 4: Launch FlatLaf mode**

Run:

```powershell
java -jar bin/hafen.jar
```

Expected: client launches with dark FlatLaf window chrome where supported by the platform.

---

## Self-Review

- Spec coverage: dependency addition, FlatLaf setup, escape hatch, JFrame migration, custom chrome, fullscreen restoration, tests, and manual verification are covered.
- Placeholder scan: no TBD/TODO/fill-in placeholders remain.
- Type consistency: `PaistiLookAndFeel.flatlafEnabled`, `setup`, `isFlatlafActive`, and `applyWindowChrome(JFrame)` are introduced before use and match later references.
