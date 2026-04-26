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
import java.awt.Dimension;
import java.awt.KeyEventDispatcher;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PaistiClientTabBarTest {
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

    private static final class PaintProbeTabBar extends PaistiClientTabBar {
        private int paintCalls;

        private PaintProbeTabBar(PaistiClientTabManager manager) {
            super(manager, new Canvas());
        }

        @Override
        public void paint(java.awt.Graphics g) {
            paintCalls++;
        }
    }

    @BeforeEach
    @AfterEach
    void clearTabs() {
        PaistiClientTabManager.getInstance().clearForTests();
    }

    @Test
    @Tag("unit")
    void preferredHeightIsReadable() {
        PaistiClientTabBar bar = new PaistiClientTabBar(PaistiClientTabManager.getInstance(), new Canvas());

        Dimension size = bar.getPreferredSize();

        assertTrue(size.height >= 30, "tab bar should be taller than tiny native buttons");
    }

    @Test
    @Tag("unit")
    void snapshotContainsAddCloseAndTabRegionsWithoutNativeButtons() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        List<PaistiClientTabBar.HitRegion> regions = bar.layoutRegionsForTests(320, 34);

        assertTrue(regions.stream().anyMatch(r -> r.kind == PaistiClientTabBar.HitKind.ADD));
        assertTrue(regions.stream().anyMatch(r -> r.kind == PaistiClientTabBar.HitKind.CLOSE));
        assertTrue(regions.stream().anyMatch(r -> r.kind == PaistiClientTabBar.HitKind.TAB));
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

        List<PaistiClientTabBar.HitRegion> regions = bar.layoutRegionsForTests(320, 34);

        assertTrue(regions.stream().anyMatch(r -> r.kind == PaistiClientTabBar.HitKind.TAB && r.tab == pending),
                "pending login tabs must be visible even before their UI exists");
    }

    @Test
    @Tag("unit")
    void addRegionIsTrailingTabShapedEntry() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(new TestUI());
        manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        List<PaistiClientTabBar.HitRegion> regions = bar.layoutRegionsForTests(420, 34);
        PaistiClientTabBar.HitRegion add = firstRegion(regions, PaistiClientTabBar.HitKind.ADD);
        int rightMostTabEdge = regions.stream()
                .filter(r -> r.kind == PaistiClientTabBar.HitKind.TAB)
                .mapToInt(r -> r.rect.x + r.rect.width)
                .max()
                .orElseThrow(AssertionError::new);

        assertTrue(add.rect.x > rightMostTabEdge, "add tab should appear after visible tabs");
        assertTrue(add.rect.width >= PaistiClientTabBar.TAB_MIN_W, "add tab should be tab-shaped, not a small square button");
    }

    @Test
    @Tag("unit")
    void closeRegionsAreInsideTheirTabs() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = new PaistiClientTabBar(manager, new Canvas());

        List<PaistiClientTabBar.HitRegion> regions = bar.layoutRegionsForTests(420, 34);

        assertTrue(regionForTab(regions, first).rect.contains(regionForCloseTab(regions, first).rect));
        assertTrue(regionForTab(regions, second).rect.contains(regionForCloseTab(regions, second).rect));
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

        click(bar, regionForCloseTab(bar, second));

        assertSame(first, manager.getActiveTab());
        assertFalse(manager.getTabs().contains(second));
        assertEquals(1, secondUi.destroyCalls);
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void mouseMoveTracksHoveredTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        move(bar, regionForTab(bar, tab));

        assertSame(tab, bar.hoveredTabForTests());
        assertEquals(PaistiClientTabBar.HitKind.TAB, bar.hoveredKindForTests());
    }

    @Test
    @Tag("unit")
    void mouseMovePrefersCloseHoverOverContainingTab() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        move(bar, regionForCloseTab(bar, tab));

        assertSame(tab, bar.hoveredTabForTests());
        assertEquals(PaistiClientTabBar.HitKind.CLOSE, bar.hoveredKindForTests());
    }

    @Test
    @Tag("unit")
    void mouseExitClearsHoverState() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab tab = manager.addLoginTab(new TestUI());
        PaistiClientTabBar bar = sizedBar(manager, new Canvas());

        move(bar, regionForTab(bar, tab));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_EXITED, System.currentTimeMillis(), 0, -1, -1, 0, false));

        assertSame(null, bar.hoveredTabForTests());
        assertSame(null, bar.hoveredKindForTests());
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
    void updatePaintsWithoutClearingBackgroundFirst() {
        PaintProbeTabBar bar = new PaintProbeTabBar(PaistiClientTabManager.getInstance());
        bar.setSize(20, 20);
        bar.setBackground(java.awt.Color.GREEN);
        BufferedImage img = new BufferedImage(20, 20, BufferedImage.TYPE_INT_ARGB);
        img.setRGB(0, 0, java.awt.Color.MAGENTA.getRGB());

        bar.update(img.getGraphics());

        assertEquals(1, bar.paintCalls);
        assertEquals(java.awt.Color.MAGENTA.getRGB(), img.getRGB(0, 0));
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

        click(bar, firstRegion(bar, PaistiClientTabBar.HitKind.ADD));

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

        click(bar, firstRegion(bar, PaistiClientTabBar.HitKind.CLOSE));

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

        click(bar, regionForTab(bar, second));

        assertSame(second, manager.getActiveTab());
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void tabPressReleaseActivatesTabWithoutClickedEvent() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        pressRelease(bar, regionForTab(bar, second));

        assertSame(second, manager.getActiveTab());
        assertEquals(1, focus.focusInWindowCalls);
    }

    @Test
    @Tag("unit")
    void mismatchedPressReleaseSuppressesFollowingClickedEvent() {
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        PaistiClientTab first = manager.addLoginTab(new TestUI());
        PaistiClientTab second = manager.addLoginTab(new TestUI());
        manager.activateTab(first);
        FocusProbe focus = new FocusProbe();
        PaistiClientTabBar bar = sizedBar(manager, focus);

        pressReleaseClick(bar, regionForTab(bar, first), regionForTab(bar, second));

        assertSame(first, manager.getActiveTab());
        assertEquals(0, focus.focusInWindowCalls);
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

        click(bar, regionForTab(bar, second));

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

        click(bar, firstRegion(bar, PaistiClientTabBar.HitKind.ADD), MouseEvent.BUTTON3);
        click(bar, firstRegion(bar, PaistiClientTabBar.HitKind.CLOSE), MouseEvent.BUTTON3);
        click(bar, regionForTab(bar, second), MouseEvent.BUTTON3);

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

    private static PaistiClientTabBar.HitRegion firstRegion(PaistiClientTabBar bar, PaistiClientTabBar.HitKind kind) {
        return firstRegion(bar.layoutRegionsForTests(bar.getWidth(), bar.getHeight()), kind);
    }

    private static PaistiClientTabBar.HitRegion firstRegion(List<PaistiClientTabBar.HitRegion> regions, PaistiClientTabBar.HitKind kind) {
        return regions.stream()
                .filter(region -> region.kind == kind)
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static PaistiClientTabBar.HitRegion regionForTab(PaistiClientTabBar bar, PaistiClientTab tab) {
        return regionForTab(bar.layoutRegionsForTests(bar.getWidth(), bar.getHeight()), tab);
    }

    private static PaistiClientTabBar.HitRegion regionForTab(List<PaistiClientTabBar.HitRegion> regions, PaistiClientTab tab) {
        return regions.stream()
                .filter(region -> region.kind == PaistiClientTabBar.HitKind.TAB && region.tab == tab)
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static PaistiClientTabBar.HitRegion regionForCloseTab(PaistiClientTabBar bar, PaistiClientTab tab) {
        return regionForCloseTab(bar.layoutRegionsForTests(bar.getWidth(), bar.getHeight()), tab);
    }

    private static PaistiClientTabBar.HitRegion regionForCloseTab(List<PaistiClientTabBar.HitRegion> regions, PaistiClientTab tab) {
        return regions.stream()
                .filter(region -> region.kind == PaistiClientTabBar.HitKind.CLOSE && region.tab == tab)
                .findFirst()
                .orElseThrow(AssertionError::new);
    }

    private static void click(PaistiClientTabBar bar, PaistiClientTabBar.HitRegion region) {
        click(bar, region, MouseEvent.BUTTON1);
    }

    private static void click(PaistiClientTabBar bar, PaistiClientTabBar.HitRegion region, int button) {
        Rectangle rect = region.rect;
        MouseEvent event = new MouseEvent(bar, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                rect.x + (rect.width / 2), rect.y + (rect.height / 2), 1, false, button);
        bar.dispatchEvent(event);
    }

    private static void move(PaistiClientTabBar bar, PaistiClientTabBar.HitRegion region) {
        Rectangle rect = region.rect;
        MouseEvent event = new MouseEvent(bar, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                rect.x + (rect.width / 2), rect.y + (rect.height / 2), 0, false);
        bar.dispatchEvent(event);
    }

    private static void pressRelease(PaistiClientTabBar bar, PaistiClientTabBar.HitRegion region) {
        Rectangle rect = region.rect;
        int x = rect.x + (rect.width / 2);
        int y = rect.y + (rect.height / 2);
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                x, y, 1, false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                x, y, 1, false, MouseEvent.BUTTON1));
    }

    private static void pressReleaseClick(PaistiClientTabBar bar, PaistiClientTabBar.HitRegion pressRegion,
                                          PaistiClientTabBar.HitRegion releaseRegion) {
        Rectangle press = pressRegion.rect;
        Rectangle release = releaseRegion.rect;
        int pressX = press.x + (press.width / 2);
        int pressY = press.y + (press.height / 2);
        int releaseX = release.x + (release.width / 2);
        int releaseY = release.y + (release.height / 2);
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0,
                pressX, pressY, 1, false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                releaseX, releaseY, 1, false, MouseEvent.BUTTON1));
        bar.dispatchEvent(new MouseEvent(bar, MouseEvent.MOUSE_CLICKED, System.currentTimeMillis(), 0,
                releaseX, releaseY, 1, false, MouseEvent.BUTTON1));
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
        Field field = Unsafe.class.getDeclaredField("theUnsafe");
        field.setAccessible(true);
        Unsafe unsafe = (Unsafe) field.get(null);
        return cl.cast(unsafe.allocateInstance(cl));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
        Field field = owner.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
