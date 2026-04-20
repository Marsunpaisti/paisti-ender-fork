package haven.test;

import haven.Coord;
import haven.MenuGrid;
import haven.OptWnd;
import haven.OwnerContext;
import haven.Widget;
import me.ender.plugin.ClientPlugin;
import me.ender.plugin.PluginAction;
import me.ender.plugin.PluginContext;
import me.ender.plugin.PluginManager;
import me.ender.plugin.PluginOptionSection;
import me.ender.plugin.PluginWindowEvent;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

public class PluginRuntimeSelfTest {
    public static void main(String[] args) {
        registrationDispatchOrder();
        toggleMetadataIsCheap();
        duplicatePluginIdRejected();
        invalidRegistrationIsPluginLocalFailure();
        optionRenderingIsAtomic();
        optionSectionsAttachAtCursor();
        throwableFailuresAreQuarantined();
        failingActionQuarantinesPlugin();
        outgoingWidgetMessageHooksDispatchAndQuarantine();
        System.out.println("PluginRuntimeSelfTest OK");
    }

    private static void registrationDispatchOrder() {
        List<String> events = new ArrayList<>();
        PluginManager global = PluginManager.get();
        PluginManager manager = PluginManager.install(List.of(
            new TestPlugin("alpha") {
                @Override
                public void register(PluginContext context) {
                    context.onClientStart(() -> events.add("alpha:start"));
                    context.onClientShutdown(() -> events.add("alpha:shutdown"));
                    context.onGameUiReady(gui -> events.add("alpha:gameui"));
                    context.onWindow((window, event) -> events.add("alpha:window:" + event.name()));
                    context.onWidgetAdded((ui, child, parent) -> events.add("alpha:widget"));
                    context.addAction(PluginAction.of("alpha", "alpha-action", "paginae/add/alpha", (ctx, iact) -> true));
                    context.addOptionSection(PluginOptionSection.of("alpha", "Alpha", (wnd, panel, y) -> y + 1));
                }
            },
            new TestPlugin("beta") {
                @Override
                public void register(PluginContext context) {
                    context.onClientStart(() -> events.add("beta:start"));
                    context.onClientShutdown(() -> events.add("beta:shutdown"));
                    context.onGameUiReady(gui -> events.add("beta:gameui"));
                    context.onWindow((window, event) -> events.add("beta:window:" + event.name()));
                    context.onWidgetAdded((ui, child, parent) -> events.add("beta:widget"));
                }
            }
        ));

        require(manager == global, "install should keep stable singleton instance");
        require(PluginManager.get() == global, "get should return stable singleton instance");
        require(PluginWindowEvent.from("pack").orElseThrow(() -> new AssertionError("missing pack mapping")) == PluginWindowEvent.PACK, "pack should map to PACK");
        require(PluginWindowEvent.from("destroy").orElseThrow(() -> new AssertionError("missing destroy mapping")) == PluginWindowEvent.DESTROY, "destroy should map to DESTROY");
        require(PluginWindowEvent.from("other").isEmpty(), "unknown window event should stay empty");

        manager.dispatchClientStart();
        manager.dispatchGameUiReady(null);
        manager.dispatchWindow(null, PluginWindowEvent.PACK);
        manager.dispatchWidgetAdded(null, null, null);
        manager.dispatchClientShutdown();

        require(events.equals(List.of(
            "alpha:start",
            "beta:start",
            "alpha:gameui",
            "beta:gameui",
            "alpha:window:PACK",
            "beta:window:PACK",
            "alpha:widget",
            "beta:widget",
            "alpha:shutdown",
            "beta:shutdown"
        )), "ordered dispatch mismatch: " + events);
        require(manager.actions().size() == 1, "action should be registered");
        require(manager.actions().get(0).pluginId().equals("alpha"), "action plugin id mismatch");
        require(manager.actions().get(0).resourcePath().equals("paginae/add/alpha"), "action resource path mismatch");
        require(manager.toggleState(manager.actions().get(0)).isEmpty(), "action toggle should be empty by default");
        require(manager.optionSections().size() == 1, "option section should be registered");
        require(manager.optionSections().get(0).pluginId().equals("alpha"), "section plugin id mismatch");
        require(manager.optionSections().get(0).title().equals("Alpha"), "section title mismatch");
    }

    private static void toggleMetadataIsCheap() {
        int[] toggleReads = {0};
        PluginManager manager = PluginManager.install(List.of(
            new TestPlugin("toggle-meta") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("toggle-meta", "toggle-meta-action", "paginae/add/toggle-meta", (ctx, iact) -> true, () -> {
                        toggleReads[0]++;
                        return true;
                    }));
                }
            }
        ));

        PluginAction action = manager.actions().get(0);
        require(action.hasToggleState(), "toggle metadata should be exposed without evaluating supplier");
        require(toggleReads[0] == 0, "toggle metadata should not evaluate supplier");
        require(manager.toggleState(action).orElseThrow(() -> new AssertionError("missing toggle state")), "toggle state should still be readable via manager");
        require(toggleReads[0] == 1, "manager toggle read should evaluate supplier exactly once");
    }

    private static void throwableFailuresAreQuarantined() {
        List<String> events = new ArrayList<>();
        PluginManager manager = PluginManager.install(List.of(
            new TestPlugin("toggle-bad") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("toggle-bad", "toggle-bad-action", "paginae/add/toggle-bad", (ctx, iact) -> true, () -> {
                        throw new AssertionError("toggle boom");
                    }));
                    context.onClientShutdown(() -> events.add("toggle-bad:shutdown"));
                }
            },
            new TestPlugin("survivor") {
                @Override
                public void register(PluginContext context) {
                    context.onClientShutdown(() -> events.add("survivor:shutdown"));
                }
            }
        ));

        PluginAction badToggle = manager.actions().stream()
            .filter(action -> action.id().equals("toggle-bad-action"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing toggle-bad action"));

        require(manager.performResult(badToggle, new DummyOwnerContext(), new MenuGrid.Interaction()) == PluginManager.PerformResult.PERFORMED_TRUE, "bad toggle action should still perform before quarantine");
        require(manager.toggleState(badToggle).isEmpty(), "bad toggle should quarantine plugin and return empty");
        require(!manager.isAvailable(badToggle), "toggle failure should quarantine plugin");

        manager.dispatchClientShutdown();
        require(events.equals(List.of("toggle-bad:shutdown", "survivor:shutdown")), "throwable quarantine mismatch: " + events);
    }

    private static void duplicatePluginIdRejected() {
        require(rejected(() -> PluginManager.install(List.of(new TestPlugin("dupe"), new TestPlugin("dupe"))), "dupe"), "duplicate plugin id should be rejected");
        List<ClientPlugin> withNullPlugin = new ArrayList<>();
        withNullPlugin.add(null);
        require(rejected(() -> PluginManager.install(withNullPlugin), "plugin"), "null plugin should be rejected");
        require(rejected(() -> PluginManager.install(List.of(new TestPlugin(null))), "id"), "null plugin id should be rejected");
        require(rejected(() -> PluginManager.install(List.of(new TestPlugin("   "))), "blank"), "blank top-level plugin id should be rejected");
    }

    private static void invalidRegistrationIsPluginLocalFailure() {
        List<String> events = new ArrayList<>();
        PluginAction sharedAction = PluginAction.of("reuse-a", "shared-action", "paginae/add/shared", (ctx, iact) -> true);
        PluginManager manager = PluginManager.install(List.of(
            new TestPlugin("bad-null-hook") {
                @Override
                public void register(PluginContext context) {
                    context.onClientStart(null);
                }
            },
            new TestPlugin("bad-mismatch") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("other", "wrong-owner", "paginae/add/wrong", (ctx, iact) -> true));
                }
            },
            new TestPlugin("reuse-a") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(sharedAction);
                }
            },
            new TestPlugin("reuse-b") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(sharedAction);
                }
            },
            new TestPlugin("bad-blank-section") {
                @Override
                public void register(PluginContext context) {
                    context.addOptionSection(PluginOptionSection.of("bad-blank-section", "   ", (wnd, panel, y) -> y));
                }
            },
            new TestPlugin("dup-id-a") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("dup-id-a", "dup-action-id", "paginae/add/dup-id-a", (ctx, iact) -> true));
                }
            },
            new TestPlugin("dup-id-b") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("dup-id-b", "dup-action-id", "paginae/add/dup-id-b", (ctx, iact) -> true));
                }
            },
            new TestPlugin("dup-path-a") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("dup-path-a", "dup-path-a-action", "paginae/add/dup-path", (ctx, iact) -> true));
                }
            },
            new TestPlugin("dup-path-b") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("dup-path-b", "dup-path-b-action", "paginae/add/dup-path", (ctx, iact) -> true));
                }
            },
            new TestPlugin("dup-section-plugin") {
                @Override
                public void register(PluginContext context) {
                    context.addOptionSection(PluginOptionSection.of("dup-section-plugin", "Shared Title", (wnd, panel, y) -> y + 1));
                    context.addOptionSection(PluginOptionSection.of("dup-section-plugin", "Shared Title", (wnd, panel, y) -> y + 2));
                }
            },
            new TestPlugin("good-late") {
                @Override
                public void register(PluginContext context) {
                    context.onClientStart(() -> events.add("good-late:start"));
                    context.addAction(PluginAction.of("good-late", "good-late-action", "paginae/add/good-late", (ctx, iact) -> true));
                    context.addOptionSection(PluginOptionSection.of("good-late", "Good Late", (wnd, panel, y) -> y + 1));
                }
            }
        ));

        manager.dispatchClientStart();

        require(events.equals(List.of("good-late:start")), "later good plugin should still load: " + events);
        require(manager.actions().size() == 4, "only valid plugin actions should remain");
        require(manager.actions().stream().anyMatch(action -> action.id().equals("shared-action")), "shared action should survive first owner only");
        require(manager.actions().stream().anyMatch(action -> action.id().equals("dup-action-id")), "first duplicate action id should survive");
        require(manager.actions().stream().anyMatch(action -> action.id().equals("dup-path-a-action")), "first duplicate resource path should survive");
        require(manager.actions().stream().anyMatch(action -> action.id().equals("good-late-action")), "good late action missing");
        require(manager.actions().stream().noneMatch(action -> action.id().equals("wrong-owner")), "owner-mismatch action should be rejected locally");
        require(manager.actions().stream().noneMatch(action -> action.id().equals("dup-path-b-action")), "second duplicate resource path should be rejected locally");
        require(manager.optionSections().size() == 1, "only valid option sections should remain");
        require(manager.optionSections().get(0).pluginId().equals("good-late"), "good late section should load");
    }

    private static void optionRenderingIsAtomic() {
        try {
            OptWnd.Panel outer = detachedPanel();
            PluginManager manager = PluginManager.install(List.of(
                new TestPlugin("bad-panel") {
                    @Override
                    public void register(PluginContext context) {
                        context.addOptionSection(PluginOptionSection.of("bad-panel", "Bad Panel", (ignoredWnd, panel, y) -> {
                            panel.add(new Widget());
                            throw new RuntimeException("panel boom");
                        }));
                    }
                },
                new TestPlugin("good-panel") {
                    @Override
                    public void register(PluginContext context) {
                        context.addOptionSection(PluginOptionSection.of("good-panel", "Good Panel", (ignoredWnd, panel, y) -> {
                            panel.add(new Widget(Coord.of(3, 4)), 0, y);
                            return y + 5;
                        }));
                    }
                }
            ));

            manager.populateOptions(null, outer);

            require(children(outer) == 1, "only successful section container should attach");
            require(children(outer.child) == 1, "attached section should keep its child content");
            require(outer.child.c.y == 0, "first attached section should start at y=0");
            require(outer.child.child.c.y == 0, "section content should use local coordinates");
            require(outer.child.sz.y == 4, "staged section panel should be packed before attach");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to build option panel test harness", e);
        }
    }

    private static void optionSectionsAttachAtCursor() {
        try {
            OptWnd.Panel outer = detachedPanel();
            PluginManager manager = PluginManager.install(List.of(
                new TestPlugin("panel-a") {
                    @Override
                    public void register(PluginContext context) {
                        context.addOptionSection(PluginOptionSection.of("panel-a", "Panel A", (ignoredWnd, panel, y) -> {
                            panel.add(new Widget(Coord.of(2, 10)), 0, y);
                            return y + 10;
                        }));
                    }
                },
                new TestPlugin("panel-b") {
                    @Override
                    public void register(PluginContext context) {
                        context.addOptionSection(PluginOptionSection.of("panel-b", "Panel B", (ignoredWnd, panel, y) -> {
                            panel.add(new Widget(Coord.of(2, 20)), 0, y);
                            return y + 20;
                        }));
                    }
                }
            ));

            manager.populateOptions(null, outer);

            require(children(outer) == 2, "two successful section containers should attach");
            require(outer.child.c.y == 0, "first section should attach at initial cursor");
            require(outer.child.next.c.y == 10, "second section should attach at advanced cursor");
            require(outer.child.child.c.y == 0, "first section content should start at local y=0");
            require(outer.child.next.child.c.y == 0, "second section content should start at local y=0");
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("failed to build option cursor test harness", e);
        }
    }

    private static void failingActionQuarantinesPlugin() {
        List<String> events = new ArrayList<>();
        RetainingPlugin retaining = new RetainingPlugin();
        PluginManager manager = PluginManager.install(List.of(
            retaining,
            new TestPlugin("good") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("good", "good-action", "paginae/add/good", (ctx, iact) -> {
                        events.add("good:action");
                        return true;
                    }));
                    context.onClientShutdown(() -> events.add("good:shutdown"));
                }
            },
            new TestPlugin("bad") {
                @Override
                public void register(PluginContext context) {
                    context.addAction(PluginAction.of("bad", "bad-action", "paginae/add/bad", (ctx, iact) -> {
                        events.add("bad:action");
                        throw new RuntimeException("boom");
                    }));
                    context.onClientShutdown(() -> events.add("bad:shutdown"));
                }
            }
        ));

        retaining.mutateAfterInstall();
        require(manager.actions().stream().noneMatch(action -> action.id().equals("late-action")), "late action should not be visible");
        require(manager.optionSections().stream().noneMatch(section -> section.pluginId().equals("retained") && section.title().equals("Late")), "late option section should not be visible");

        PluginAction badAction = manager.actions().stream()
            .filter(action -> action.id().equals("bad-action"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("missing bad action"));

        require(manager.isAvailable(badAction), "bad action should start available");
        require(manager.performResult(badAction, new DummyOwnerContext(), new MenuGrid.Interaction()) == PluginManager.PerformResult.UNAVAILABLE_AFTER_FAILURE, "bad action should report quarantine result");
        require(!manager.perform(badAction, new DummyOwnerContext(), new MenuGrid.Interaction()), "compat boolean perform should be false after quarantine");
        manager.dispatchClientShutdown();

        require(events.equals(List.of("bad:action", "good:shutdown", "bad:shutdown")), "quarantine mismatch: " + events);
        require(manager.actions().stream().noneMatch(action -> action.id().equals("bad-action")), "quarantined action should be filtered");
    }

    private static void outgoingWidgetMessageHooksDispatchAndQuarantine() {
        List<String> events = new ArrayList<>();
        Object[] originalArgs = new Object[] {
            1,
            new Object[] {"foo", new int[] {2, 3}},
            new long[] {4L, 5L}
        };
        int[] badHookCalls = {0};

        PluginManager manager = PluginManager.install(List.of(
            new ClientPlugin() {
                @Override
                public String id() {
                    return "logger-a";
                }

                @Override
                public void register(PluginContext context) {
                    context.onOutgoingWidgetMessage((ui, sender, widgetId, msg, args) -> {
                        events.add("a:" + widgetId + ":" + msg + ":" + args.length);
                        if (args.length > 0) {
                            args[0] = 99;
                        }
                        if (args.length > 2) {
                            ((Object[]) args[1])[0] = "mutated";
                            ((int[]) ((Object[]) args[1])[1])[0] = 77;
                            ((long[]) args[2])[1] = 88L;
                        }
                    });
                }
            },
            new ClientPlugin() {
                @Override
                public String id() {
                    return "logger-bad";
                }

                @Override
                public void register(PluginContext context) {
                    context.onOutgoingWidgetMessage((ui, sender, widgetId, msg, args) -> {
                        badHookCalls[0]++;
                        events.add("bad:" + msg);
                        throw new RuntimeException("boom");
                    });
                }
            },
            new ClientPlugin() {
                @Override
                public String id() {
                    return "logger-c";
                }

                @Override
                public void register(PluginContext context) {
                    context.onOutgoingWidgetMessage((ui, sender, widgetId, msg, args) ->
                        events.add("c:" + widgetId + ":" + msg));
                }
            }
        ));

        manager.dispatchOutgoingWidgetMessage(null, null, 17, "itemact", originalArgs);
        manager.dispatchOutgoingWidgetMessage(null, null, 19, "drop", new Object[0]);

        require(events.equals(List.of(
            "a:17:itemact:3",
            "bad:itemact",
            "c:17:itemact",
            "a:19:drop:0",
            "c:19:drop"
        )), "unexpected outgoing hook dispatch order: " + events);
        require(badHookCalls[0] == 1, "failing outgoing hook should be quarantined after first failure");
        require(((Integer) originalArgs[0]) == 1, "outgoing hook should observe cloned args only");
        require("foo".equals(((Object[]) originalArgs[1])[0]), "nested object arrays should be defensively copied");
        require(((int[]) ((Object[]) originalArgs[1])[1])[0] == 2, "nested primitive arrays should be defensively copied");
        require(((long[]) originalArgs[2])[1] == 5L, "top-level primitive arrays should be defensively copied");
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class DummyOwnerContext implements OwnerContext {
        @Override
        public <T> T context(Class<T> cl) {
            return null;
        }
    }

    private static class TestPlugin implements ClientPlugin {
        private final String id;

        private TestPlugin(String id) {
            this.id = id;
        }

        @Override
        public String id() {
            return id;
        }

        @Override
        public void register(PluginContext context) {
        }
    }

    private static class RetainingPlugin extends TestPlugin {
        private PluginContext retained;

        private RetainingPlugin() {
            super("retained");
        }

        @Override
        public void register(PluginContext context) {
            retained = context;
            context.onClientStart(() -> { });
            context.addAction(PluginAction.of("retained", "retained-action", "paginae/add/retained", (ctx, iact) -> true, new FixedToggle(false)));
            context.addOptionSection(PluginOptionSection.of("retained", "Retained", (wnd, panel, y) -> y));
        }

        private void mutateAfterInstall() {
            require(stateRejected(() -> retained.onClientShutdown(() -> {
                throw new AssertionError("retained context should be frozen");
            }), "frozen"), "retained shutdown hook mutation should be rejected");
            require(stateRejected(() -> retained.addAction(PluginAction.of("retained", "late-action", "paginae/add/late", (ctx, iact) -> true)), "frozen"), "retained action mutation should be rejected");
            require(stateRejected(() -> retained.addOptionSection(PluginOptionSection.of("retained", "Late", (wnd, panel, y) -> y + 10)), "frozen"), "retained option mutation should be rejected");
        }
    }

    private static class FixedToggle implements Supplier<Boolean> {
        private final boolean value;

        private FixedToggle(boolean value) {
            this.value = value;
        }

        @Override
        public Boolean get() {
            return value;
        }
    }

    private static boolean rejected(ThrowingRunnable runnable, String expectedText) {
        try {
            runnable.run();
            return false;
        } catch (IllegalArgumentException e) {
            return e.getMessage() != null && e.getMessage().toLowerCase().contains(expectedText.toLowerCase());
        }
    }

    private static boolean stateRejected(ThrowingRunnable runnable, String expectedText) {
        try {
            runnable.run();
            return false;
        } catch (IllegalStateException e) {
            return e.getMessage() != null && e.getMessage().toLowerCase().contains(expectedText.toLowerCase());
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run();
    }

    private static int children(Widget widget) {
        int count = 0;
        for (Widget child = widget.child; child != null; child = child.next) {
            count++;
        }
        return count;
    }

    private static OptWnd.Panel detachedPanel() throws ReflectiveOperationException {
        java.lang.reflect.Constructor<OptWnd.Panel> ctor = OptWnd.Panel.class.getDeclaredConstructor(OptWnd.class);
        ctor.setAccessible(true);
        return ctor.newInstance(new Object[] {null});
    }

}
