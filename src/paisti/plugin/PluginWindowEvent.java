package paisti.plugin;

import java.util.Optional;

public enum PluginWindowEvent {
    SHOW,
    PACK,
    DESTROY;

    public static Optional<PluginWindowEvent> from(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        switch (raw) {
            case "show":
                return Optional.of(SHOW);
            case "pack":
                return Optional.of(PACK);
            case "destroy":
                return Optional.of(DESTROY);
            default:
                return Optional.empty();
        }
    }
}
