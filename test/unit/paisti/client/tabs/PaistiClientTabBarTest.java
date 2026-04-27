package paisti.client.tabs;

import haven.Area;
import haven.Coord;
import haven.GLPanel;
import haven.GOut;
import haven.RootWidget;
import haven.Session;
import haven.UI;
import haven.render.Pipe;
import haven.render.gl.GLEnvironment;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PaistiClientTabBarTest {
    private double savedScale;

    private static final class DummyPanel extends Canvas implements GLPanel {
        @Override
        public GLEnvironment env() {
            return null;
        }

        @Override
        public Area shape() {
            return Area.sized(Coord.z, Coord.of(10, 10));
        }

        @Override
        public Pipe basestate() {
            return null;
        }

        @Override
        public void glswap(haven.render.gl.GL gl) {
        }

        @Override
        public void setmousepos(Coord c) {
        }

        @Override
        public UI newui(UI.Runner fun) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void background(boolean bg) {
        }

        @Override
        public void run() {
        }
    }

    private static final class TestRootWidget extends RootWidget {
        private TestRootWidget(UI ui, Coord sz) {
            super(ui, sz);
        }

        @Override
        protected GobEffects createEffects(UI ui) {
            return null;
        }

        @Override
        public void tick(double dt) {
        }

        @Override
        public void draw(GOut g) {
        }
    }

    private static final class TestUI extends UI {
        private int destroyCalls = 0;

        private TestUI() {
            super(new DummyPanel(), Coord.of(10, 10), null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }

        @Override
        public void destroy() {
            destroyCalls++;
            super.destroy();
        }
    }

    private static final class FocusProbe extends Component {
        private int focusInWindowCalls;
        private int focusCalls;

        @Override
        public boolean requestFocusInWindow() {
            focusInWindowCalls++;
            return true;
        }

        @Override
        public void requestFocus() {
            focusCalls++;
        }
    }

    @BeforeEach
    void setup() throws Exception {
        savedScale = getStaticDouble(UI.class, "scalef");
        setStaticDouble(UI.class, "scalef", 1.0);
        PaistiClientTabManager.getInstance().clearForTests();
    }

    @AfterEach
    void tearDown() throws Exception {
        PaistiClientTabManager.getInstance().clearForTests();
        setStaticDouble(UI.class, "scalef", savedScale);
    }

    @Test
    @Tag("unit")
    void preferredHeightIsReadable() {
        PaistiClientTabBar bar = new PaistiClientTabBar(PaistiClientTabManager.getInstance(), new Canvas());

        Dimension size = bar.getPreferredSize();

        assertEquals(24, size.height, "tab bar should be compact without shrinking below readable tab controls");
    }

    @Test
    @Tag("unit")
    void dimensionsAndFontFollowHavenUiScale() throws Exception {
        double oldScale = getStaticDouble(UI.class, "scalef");
        try {
            setStaticDouble(UI.class, "scalef", 2.0);
            PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
            manager.addLoginTab(new TestUI());

            PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

            assertEquals(UI.scale(PaistiClientTabBar.HEIGHT), bar.getPreferredSize().height);
            assertEquals(UI.scale(13), Math.round(bar.getFont().getSize2D()));
            assertEquals(UI.scale(PaistiClientTabBar.ADD_TAB_W), addButton(bar).getPreferredSize().width);
        } finally {
            setStaticDouble(UI.class, "scalef", oldScale);
        }
    }

    @Test
    @Tag("unit")
    void snapshotContainsSwingTabCloseAndAddButtonsWithoutNativeButtons() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        assertSame(tab, tabButtonFor(bar, tab).getClientProperty(PaistiClientTabBar.TAB_PROPERTY));
        assertSame(tab, closeButtonFor(bar, tab).getClientProperty(PaistiClientTabBar.CLOSE_TAB_PROPERTY));
        assertSame(Boolean.TRUE, addButton(bar).getClientProperty(PaistiClientTabBar.ADD_TAB_PROPERTY));
        for(Component child : bar.getComponents())
            assertFalse(child instanceof java.awt.Button, "tab bar must not use native AWT Button controls");
    }

    @Test
    @Tag("unit")
    void layoutIncludesPendingLoginTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab pending = manager.addPendingLoginTab();
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        assertSame(pending, tabButtonFor(bar, pending).getClientProperty(PaistiClientTabBar.TAB_PROPERTY),
                "pending login tabs must be visible even before their UI exists");
    }

    @Test
    @Tag("unit")
    void addButtonIsTrailingTabShapedEntry() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        assertTrue(componentIndex(bar, addButton(bar)) > componentIndex(bar, tabComponentFor(bar, second)),
                "add tab should appear after visible tabs");
        assertTrue(addButton(bar).getPreferredSize().width >= PaistiClientTabBar.TAB_MIN_W,
                "add tab should be tab-shaped, not a small square button");
        assertTrue(componentIndex(bar, tabComponentFor(bar, second)) > componentIndex(bar, tabComponentFor(bar, first)));
    }

    @Test
    @Tag("unit")
    void closeButtonsFollowTheirTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        assertEquals(componentIndex(bar, tabComponentFor(bar, first)) + 1, componentIndex(bar, tabComponentFor(bar, second)));
        assertSame(tabComponentFor(bar, first), closeButtonFor(bar, first).getParent());
        assertSame(tabComponentFor(bar, second), closeButtonFor(bar, second).getParent());
    }

    @Test
    @Tag("unit")
    void closeButtonIsNestedInsideItsTabComponent() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        assertSame(tabComponentFor(bar, tab), closeButtonFor(bar, tab).getParent());
    }

    @Test
    @Tag("unit")
    void flatlafButtonStylesDoNotUseUnsupportedKeys() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        for(javax.swing.JButton button : allButtons(bar)) {
            Object style = button.getClientProperty("FlatLaf.style");
            String text = style == null ? "" : style.toString();
            assertFalse(text.contains("arc:"));
            assertFalse(text.contains("borderWidth:"));
            assertFalse(text.contains("focusWidth:"));
            assertFalse(text.contains("innerFocusWidth:"));
        }
    }

    @Test
    @Tag("unit")
    void constrainedLayoutKeepsButtonsOnOneRowInsideBarBounds() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        manager.addLoginTab(new TestUI());
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        bar.doLayout();

        for(Component component : bar.getComponents()) {
            assertTrue(component.getY() >= 0);
            assertTrue(component.getY() + component.getHeight() <= bar.getHeight());
            assertTrue(component.getX() >= 0);
            assertTrue(component.getX() + component.getWidth() <= bar.getWidth());
        }
    }

    @Test
    @Tag("unit")
    void tabComponentsAreFlushWithChromeAndGameCanvasEdges() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        bar.doLayout();

        Container tabComponent = tabComponentFor(bar, tab);
        assertEquals(0, tabComponent.getY(), "tab should touch the window chrome edge");
        assertEquals(bar.getHeight(), tabComponent.getHeight(), "tab should touch the game canvas edge");
    }

    @Test
    @Tag("unit")
    void closeClickClosesClickedInactiveTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        TestUI secondUi = new TestUI();
        PaistiClientTab second = manager.addLoginTab(secondUi);
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        closeButtonFor(bar, second).doClick();

        assertSame(first, manager.getActiveTab());
        assertFalse(manager.getTabs().contains(second));
        assertEquals(1, secondUi.destroyCalls);
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void middleClickingTabClosesItAndRefocusesGame() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        TestUI secondUi = new TestUI();
        PaistiClientTab second = manager.addLoginTab(secondUi);
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        middleClick(tabButtonFor(bar, second));

        assertSame(first, manager.getActiveTab());
        assertFalse(manager.getTabs().contains(second));
        assertEquals(1, secondUi.destroyCalls);
        assertEquals(1, focus.focusInWindowCalls);
        assertEquals(0, focus.focusCalls);
    }

    @Test
    @Tag("unit")
    void tabBarUsesSwingButtonsForTabsAndAddAction() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        assertTrue(javax.swing.JPanel.class.isInstance(bar));
        assertTrue(tabButtonFor(bar, first) instanceof javax.swing.JButton);
        assertTrue(addButton(bar) instanceof javax.swing.JButton);
    }

    @Test
    @Tag("unit")
    void swingTabButtonActivatesClickedTabAndRefocusesGame() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        tabButtonFor(bar, second).doClick();

        assertSame(second, manager.getActiveTab());
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void paintDoesNotRequireNativeWindow() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());
        bar.setSize(320, 34);

        BufferedImage img = new BufferedImage(320, 34, BufferedImage.TYPE_INT_ARGB);
        bar.paint(img.getGraphics());

        assertEquals(320, img.getWidth());
    }

    @Test
    @Tag("unit")
    void paintTerminatesWithVeryLongSessionLabel() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        String longName = repeat("W", 4000);
        Session session = newSession(longName);
        manager.addSessionTab(new PaistiSessionContext(session, new TestUI(), null));
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());
        bar.setSize(120, 34);
        BufferedImage img = new BufferedImage(120, 34, BufferedImage.TYPE_INT_ARGB);

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> bar.paint(img.getGraphics()));
    }

    @Test
    @Tag("unit")
    void addClickRequestsNewLoginTabAndRefocusesGame() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);
        CountDownLatch released = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                manager.waitForLoginRequest();
                released.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        addButton(bar).doClick();

        assertTrue(released.await(2, TimeUnit.SECONDS), "add hit region must request a login tab");
        assertEquals(1, focus.focusInWindowCalls);
        assertEquals(0, focus.focusCalls);
        waiter.join(2000);
    }

    @Test
    @Tag("unit")
    void closeClickClosesActiveTabAndRefocusesGame() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI ui = new TestUI();
        manager.addLoginTab(ui);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        closeButtonFor(bar, manager.getActiveTab()).doClick();

        assertEquals(1, manager.getTabs().size());
        assertTrue(manager.getActiveTab().isLogin());
        assertSame(null, manager.getActiveTab().ui());
        assertEquals(1, ui.destroyCalls);
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void tabClickActivatesClickedTabAndRefocusesGame() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        tabButtonFor(bar, second).doClick();

        assertSame(second, manager.getActiveTab());
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void tabClickActivatesPendingLoginTabAndRefocusesGame() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        tabButtonFor(bar, second).doClick();

        assertSame(second, manager.getActiveTab());
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void tabBarDispatchesClientTabHotkeysBeforeLoginGameUiExists() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        manager.activateTab(first);
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());
        KeyEvent event = new KeyEvent(bar, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_RIGHT, KeyEvent.CHAR_UNDEFINED);

        assertTrue(bar instanceof KeyEventDispatcher, "tab bar must handle client-tab hotkeys outside GameUI");
        assertTrue(((KeyEventDispatcher)bar).dispatchKeyEvent(event));

        assertSame(second, manager.getActiveTab());
        assertTrue(event.isConsumed());
    }

    @Test
    @Tag("unit")
    void ctrlTabSwitchesToNextClientTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        manager.activateTab(first);
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());
        KeyEvent event = new KeyEvent(bar, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED);

        assertTrue(((KeyEventDispatcher)bar).dispatchKeyEvent(event));

        assertSame(second, manager.getActiveTab());
        assertTrue(event.isConsumed());
    }

    @Test
    @Tag("unit")
    void ctrlShiftTabSwitchesToPreviousClientTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addPendingLoginTab();
        PaistiClientTab second = manager.addPendingLoginTab();
        manager.activateTab(second);
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());
        KeyEvent event = new KeyEvent(bar, KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK, KeyEvent.VK_TAB, KeyEvent.CHAR_UNDEFINED);

        assertTrue(((KeyEventDispatcher)bar).dispatchKeyEvent(event));

        assertSame(first, manager.getActiveTab());
        assertTrue(event.isConsumed());
    }

    @Test
    @Tag("unit")
    void nonPrimaryClicksDoNotTriggerTabActions() throws Exception {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        TestUI activeUi = new TestUI();
        manager.addLoginTab(activeUi);
        PaistiClientTab first = manager.getActiveTab();
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);
        CountDownLatch released = new CountDownLatch(1);
        Thread waiter = new Thread(() -> {
            try {
                manager.waitForLoginRequest();
                released.countDown();
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();

        rightClick(addButton(bar));
        rightClick(closeButtonFor(bar, first));
        rightClick(tabButtonFor(bar, second));

        assertFalse(released.await(150, TimeUnit.MILLISECONDS), "right-click must not request a login tab");
        waiter.interrupt();
        waiter.join(2000);
        assertEquals(2, manager.getTabs().size());
        assertEquals(0, activeUi.destroyCalls);
        assertSame(first, manager.getActiveTab());
        assertEquals(0, focus.focusInWindowCalls);
        assertEquals(0, focus.focusCalls);
    }

    @Test
    @Tag("unit")
    void managerListenerFiresOnlyForModelChangesAndCanBeRemoved() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        AtomicInteger changes = new AtomicInteger();
        PaistiClientTabManager.Listener listener = changes::incrementAndGet;

        manager.addListener(listener);
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        manager.activateTab(first);
        manager.activateTab(second);
        manager.removeListener(listener);
        manager.addLoginTab(new TestUI());

        assertEquals(4, changes.get());
    }

    private static PaistiClientTabBar sizedBar(PaistiClientTabManager manager, Component focus) {
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, focus);
        bar.setSize(320, 34);
        return bar;
    }

    private static int componentIndex(Container root, Component target) {
        Component[] components = root.getComponents();
        for(int i = 0; i < components.length; i++) {
            if(components[i] == target)
                return i;
        }
        throw new AssertionError("Component not found: " + target);
    }

    private static void rightClick(Component component) {
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                1, 1, 1, false, MouseEvent.BUTTON3));
    }

    private static void middleClick(Component component) {
        component.dispatchEvent(new MouseEvent(component, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                1, 1, 1, false, MouseEvent.BUTTON2));
    }

    private static javax.swing.JButton tabButtonFor(PaistiClientTabBar bar, PaistiClientTab tab) {
        return buttonWithProperty(bar, PaistiClientTabBar.TAB_PROPERTY, tab);
    }

    private static Container tabComponentFor(PaistiClientTabBar bar, PaistiClientTab tab) {
        Component component = componentWithProperty(bar, PaistiClientTabBar.TAB_PROPERTY, tab);
        if(component instanceof Container)
            return (Container) component;
        throw new AssertionError("Tab component is not a container: " + component);
    }

    private static javax.swing.JButton closeButtonFor(PaistiClientTabBar bar, PaistiClientTab tab) {
        return buttonWithProperty(bar, PaistiClientTabBar.CLOSE_TAB_PROPERTY, tab);
    }

    private static javax.swing.JButton addButton(PaistiClientTabBar bar) {
        return buttonWithProperty(bar, PaistiClientTabBar.ADD_TAB_PROPERTY, Boolean.TRUE);
    }

    private static javax.swing.JButton buttonWithProperty(Container root, String key, Object value) {
        javax.swing.JButton button = findButtonWithProperty(root, key, value);
        if(button != null)
            return button;
        throw new AssertionError("No button with client property " + key + "=" + value);
    }

    private static Component componentWithProperty(Container root, String key, Object value) {
        for(Component component : root.getComponents()) {
            if(component instanceof javax.swing.JComponent && ((javax.swing.JComponent) component).getClientProperty(key) == value)
                return component;
            if(component instanceof Container) {
                Component nested = componentWithProperty((Container) component, key, value);
                if(nested != null)
                    return nested;
            }
        }
        return null;
    }

    private static java.util.List<javax.swing.JButton> allButtons(Container root) {
        java.util.List<javax.swing.JButton> buttons = new java.util.ArrayList<>();
        collectButtons(root, buttons);
        return buttons;
    }

    private static void collectButtons(Container root, java.util.List<javax.swing.JButton> buttons) {
        for(Component component : root.getComponents()) {
            if(component instanceof javax.swing.JButton)
                buttons.add((javax.swing.JButton) component);
            if(component instanceof Container)
                collectButtons((Container) component, buttons);
        }
    }

    private static javax.swing.JButton findButtonWithProperty(Container root, String key, Object value) {
        for(Component component : root.getComponents()) {
            if(component instanceof javax.swing.JButton) {
                javax.swing.JButton button = (javax.swing.JButton) component;
                if(button.getClientProperty(key) == value)
                    return button;
            }
            if(component instanceof Container) {
                javax.swing.JButton button = findButtonWithProperty((Container) component, key, value);
                if(button != null)
                    return button;
            }
        }
        return null;
    }

    private static String repeat(String text, int count) {
        StringBuilder buf = new StringBuilder(text.length() * count);
        for(int i = 0; i < count; i++)
            buf.append(text);
        return buf.toString();
    }

    private static Session newSession(String user) {
        try {
            Session session = allocate(Session.class);
            setField(Session.class, session, "user", new Session.User(user));
            return session;
        } catch(Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T allocate(Class<T> cl) throws Exception {
        return cl.cast(unsafe().allocateInstance(cl));
    }

    private static Unsafe unsafe() throws Exception {
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        return (Unsafe) field.get(null);
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static double getStaticDouble(Class<?> owner, String name) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Unsafe unsafe = unsafe();
        return unsafe.getDouble(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field));
    }

    private static void setStaticDouble(Class<?> owner, String name, double value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        Unsafe unsafe = unsafe();
        unsafe.putDouble(unsafe.staticFieldBase(field), unsafe.staticFieldOffset(field), value);
    }
}
