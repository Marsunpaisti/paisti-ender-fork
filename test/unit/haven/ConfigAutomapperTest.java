package haven;

import integrations.mapv4.MappingClient;
import me.ender.gob.GobEffects;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.lang.reflect.Field;
import sun.misc.Unsafe;

import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigAutomapperTest {
    private static final class DummyTransport implements Transport {
        @Override
        public void close() {
        }

        @Override
        public void queuemsg(PMessage pmsg) {
        }

        @Override
        public void send(PMessage msg) {
        }

        @Override
        public Transport add(Callback cb) {
            return this;
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
        private TestUI(Session session) {
            super(null, Coord.z, new Runner() {
                @Override
                public Runner run(UI ui) {
                    return null;
                }

                @Override
                public void init(UI ui) {
                    ui.sess = session;
                    session.ui = ui;
                }
            });
        }

        @Override
        protected RootWidget createRoot(Coord sz) {
            return new TestRootWidget(this, sz);
        }
    }

    @AfterEach
    void destroyMappingClient() {
        MappingClient.destroy();
    }

    @Test
    @Tag("unit")
    void concurrentAutomapperInitializationDoesNotCrash() throws Exception {
        int threads = 8;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        List<Future<Throwable>> futures = new ArrayList<>();
        for(int i = 0; i < threads; i++) {
            Session session = newSession("mapper-" + i);
            TestUI ui = new TestUI(session);
            futures.add(executor.submit(() -> {
                try {
                    ready.countDown();
                    start.await();
                    Config.initAutomapper(ui);
                    return null;
                } catch(Throwable t) {
                    return t;
                }
            }));
        }

        assertTrue(ready.await(2, TimeUnit.SECONDS));
        start.countDown();
        try {
            List<Throwable> errors = new ArrayList<>();
            for(Future<Throwable> future : futures) {
                Throwable error = future.get();
                if(error != null)
                    errors.add(error);
            }
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            executor.shutdownNow();
        }
    }

    private static Session newSession(String user) {
        try {
            Session session = allocate(Session.class);
            setField(Session.class, session, "conn", new DummyTransport());
            setField(Session.class, session, "user", new Session.User(user));
            setField(Session.class, session, "glob", new Glob(session));
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
