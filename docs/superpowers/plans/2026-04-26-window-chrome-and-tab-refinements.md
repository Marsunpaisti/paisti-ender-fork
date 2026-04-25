# Window Chrome And Tab Refinements Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix FlatLaf titlebar sizing on high-DPI displays and make the Paisti tab bar use per-tab close buttons plus a tab-shaped add button.

**Architecture:** Keep Java UI auto-scaling disabled for renderer stability, but explicitly set FlatLaf titlebar height from screen DPI. Update the custom AWT tab bar layout/paint model so all tab actions live inside tab-shaped regions and remain covered by existing headless tests.

**Tech Stack:** Java 11, Ant, AWT/Swing, FlatLaf 3.7, JUnit 5.

---

## File Structure

- Modify: `src/paisti/client/ui/PaistiLookAndFeel.java`
  - Add DPI-derived titlebar height helper and apply it to FlatLaf root pane properties.
- Modify: `test/unit/paisti/client/ui/PaistiLookAndFeelTest.java`
  - Test titlebar height calculations without creating native windows.
- Modify: `src/paisti/client/tabs/PaistiClientTabBar.java`
  - Move close hit regions inside tabs and paint add as the final tab-shaped entry.
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabBarTest.java`
  - Update layout/click assertions for per-tab close regions and trailing add tab.

---

### Task 1: Scale FlatLaf Titlebar Explicitly

**Files:**
- Modify: `test/unit/paisti/client/ui/PaistiLookAndFeelTest.java`
- Modify: `src/paisti/client/ui/PaistiLookAndFeel.java`

- [ ] **Step 1: Add failing tests**

Add tests for `titleBarHeightForDpi(96) == 32`, `titleBarHeightForDpi(192) == 64`, and a lower bound for invalid DPI.

- [ ] **Step 2: Run tests to verify failure**

Run: `ant test-unit -buildfile build.xml`

Expected: compile fails because `titleBarHeightForDpi` does not exist.

- [ ] **Step 3: Implement titlebar height helper and root-pane property**

Add `titleBarHeightForDpi(int dpi)`, `currentScreenDpi(JFrame frame)`, and set `FlatClientProperties.TITLE_BAR_HEIGHT` in `applyWindowChrome`.

- [ ] **Step 4: Run tests to verify pass**

Run: `ant test-unit -buildfile build.xml`

Expected: all tests pass.

---

### Task 2: Move Tab Close Into Each Tab And Add Trailing Add Tab

**Files:**
- Modify: `test/unit/paisti/client/tabs/PaistiClientTabBarTest.java`
- Modify: `src/paisti/client/tabs/PaistiClientTabBar.java`

- [ ] **Step 1: Add/update failing layout tests**

Assert that add appears after tab regions, close regions are inside tab bounds, no standalone left close button exists, and closing a non-active tab closes that tab.

- [ ] **Step 2: Run tests to verify failure**

Run: `ant test-unit -buildfile build.xml`

Expected: assertions fail under the old left-control layout.

- [ ] **Step 3: Implement layout and paint changes**

Lay out visible tabs first, add close subregions inside each tab, then add a tab-shaped `+ Add new tab` region. Check close regions before tab regions in click handling.

- [ ] **Step 4: Run tests to verify pass**

Run: `ant test-unit -buildfile build.xml`

Expected: all tests pass.

---

### Task 3: Final Verification

**Files:**
- Test-only unless verification exposes a regression.

- [ ] **Step 1: Compile client**

Run: `ant clean-code hafen-client -buildfile build.xml`

Expected: build succeeds.

- [ ] **Step 2: Run unit tests**

Run: `ant test-unit -buildfile build.xml`

Expected: all tests pass.

- [ ] **Step 3: Build distributable**

Run: `ant bin -buildfile build.xml`

Expected: build succeeds and `bin/flatlaf-3.7.jar` remains present.

## Self-Review

- Spec coverage: high-DPI titlebar sizing, per-tab close controls, trailing add tab, tests, and build verification are covered.
- Placeholder scan: no placeholders remain.
- Type consistency: helper names and affected files are consistent across tasks.
