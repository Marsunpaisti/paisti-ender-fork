package paisti.pluginv2.overlay;

public interface PluginOverlay {
    default String id() {
        return getClass().getName();
    }

    default int priority() {
        return 0;
    }

    default boolean enabled() {
        return true;
    }

    default void dispose() {
    }
}
