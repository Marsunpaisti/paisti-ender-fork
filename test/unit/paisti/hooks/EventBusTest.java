package paisti.hooks;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.testing.RecordingSubscriber;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EventBusTest {
    private static final class TestEvent {
        private final String payload;

        private TestEvent(String payload) {
            this.payload = payload;
        }
    }

    @Test
    @Tag("unit")
    void postDeliversEventToRegisteredSubscriber() {
        EventBus bus = new EventBus();
        RecordingSubscriber<TestEvent> subscriber = new RecordingSubscriber<>();

        bus.register(TestEvent.class, subscriber::record, 10);

        bus.post(new TestEvent("alpha"));

        assertEquals(1, subscriber.events().size());
        assertEquals("alpha", subscriber.events().get(0).payload);
    }

    @Test
    @Tag("unit")
    void unregisterStopsFurtherDelivery() {
        EventBus bus = new EventBus();
        RecordingSubscriber<TestEvent> subscriber = new RecordingSubscriber<>();

        EventBus.Subscriber handle = bus.register(TestEvent.class, subscriber::record, 10);

        bus.post(new TestEvent("first"));
        bus.unregister(handle);
        bus.post(new TestEvent("second"));

        List<String> payloads = subscriber.events().stream().map(event -> event.payload).collect(Collectors.toList());
        assertEquals(List.of("first"), payloads);
    }

    @Test
    @Tag("unit")
    void subscribersRunInDescendingPriorityOrder() {
        EventBus bus = new EventBus();
        List<String> callOrder = new ArrayList<>();

        bus.register(TestEvent.class, event -> callOrder.add("low"), 1);
        bus.register(TestEvent.class, event -> callOrder.add("high"), 10);

        bus.post(new TestEvent("ordered"));

        assertEquals(List.of("high", "low"), callOrder);
    }

    @Test
    @Tag("unit")
    void exceptionHandlerReceivesSubscriberFailureAndDeliveryContinues() {
        List<Throwable> failures = new ArrayList<>();
        EventBus bus = new EventBus(failures::add);
        RecordingSubscriber<TestEvent> subscriber = new RecordingSubscriber<>();

        bus.register(TestEvent.class, event -> {
            throw new IllegalStateException("boom");
        }, 10);
        bus.register(TestEvent.class, subscriber::record, 1);

        bus.post(new TestEvent("safe"));

        assertEquals(1, failures.size());
        assertEquals("boom", failures.get(0).getMessage());
        assertEquals(List.of("safe"), subscriber.events().stream().map(event -> event.payload).collect(Collectors.toList()));
    }
}
