package paisti.testing;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class RecordingSubscriber<T> {
    private final List<T> events = new ArrayList<>();

    public void record(T event) {
        events.add(event);
    }

    public List<T> events() {
        return Collections.unmodifiableList(events);
    }
}
