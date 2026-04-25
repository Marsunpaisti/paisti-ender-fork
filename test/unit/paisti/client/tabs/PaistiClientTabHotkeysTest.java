package paisti.client.tabs;

import haven.Coord;
import haven.GOut;
import haven.RootWidget;
import haven.UI;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

class PaistiClientTabHotkeysTest {
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
        private TestUI() {
            super(null, Coord.z, null);
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }
    }

    @BeforeEach
    @AfterEach
    void clearTabs() {
        PaistiClientTabManager.getInstance().clearForTests();
    }

    @Test
    void addSessionHotkeyRequestsNewLoginTab() throws InterruptedException {
        CountDownLatch waiting = new CountDownLatch(1);
        AtomicBoolean received = new AtomicBoolean(false);
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                PaistiClientTabManager.getInstance().waitForLoginRequest();
                received.set(true);
            } catch(InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        waiter.start();
        waiting.await(1, TimeUnit.SECONDS);

        PaistiClientTabHotkeys.addSession();

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> waiter.join());
        assertTrue(received.get());
    }

    @Test
    void removeSessionHotkeyClosesActiveTab() {
        UI first = new TestUI();
        UI second = new TestUI();
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(first);
        manager.addLoginTab(second);

        PaistiClientTabHotkeys.removeSession();

        assertSame(first, manager.getActiveUi());
    }

    @Test
    void previousSessionHotkeySwitchesToPreviousTab() {
        UI first = new TestUI();
        UI second = new TestUI();
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(first);
        manager.addLoginTab(second);

        PaistiClientTabHotkeys.previousSession();

        assertSame(first, manager.getActiveUi());
    }

    @Test
    void nextSessionHotkeySwitchesToNextTab() {
        UI first = new TestUI();
        UI second = new TestUI();
        PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
        manager.addLoginTab(first);
        manager.addLoginTab(second);
        manager.activateTab(manager.findTab(first));

        PaistiClientTabHotkeys.nextSession();

        assertSame(second, manager.getActiveUi());
    }
}
