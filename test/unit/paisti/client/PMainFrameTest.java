package paisti.client;

import haven.Console;
import haven.MainFrame;
import haven.RemoteUI;
import haven.Session;
import haven.UI;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import paisti.client.tabs.PaistiSessionRunner;
import sun.misc.Unsafe;

import java.awt.Canvas;
import java.awt.Component;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PMainFrameTest {
    private static class TestFrame extends PMainFrame {
 private TestFrame() {
     super(null);
 }

 private Component wrap(Component renderer) {
     return(wrapRenderer(renderer));
 }
    }

    @Test
    @Tag("unit")
    void rendererWrapperForwardsFrameConsoleCommands() throws Exception {
 TestFrame frame = allocate(TestFrame.class);
 Map<String, Console.Command> commands = new TreeMap<>();
 setField(MainFrame.class, frame, "cmdmap", commands);

 Component wrapper = frame.wrap(new Canvas());

 assertTrue(wrapper instanceof Console.Directory);
 assertSame(commands, ((Console.Directory)wrapper).findcmds());
    }

    @Test
    @Tag("unit")
    void replayRunnerUsesPaistiSessionRunnerForPackagedClient() throws Exception {
	PMainFrame.Factory factory = new PMainFrame.Factory();
	RemoteUI remote = new RemoteUI(allocate(Session.class));

	UI.Runner runner = factory.replayRunner(remote);

	assertTrue(runner instanceof PaistiSessionRunner);
    }

    private static <T> T allocate(Class<T> cl) throws Exception {
 Field field = Unsafe.class.getDeclaredField("theUnsafe");
 field.setAccessible(true);
 Unsafe unsafe = (Unsafe)field.get(null);
 return(cl.cast(unsafe.allocateInstance(cl)));
    }

    private static void setField(Class<?> owner, Object target, String name, Object value) throws Exception {
 Field field = owner.getDeclaredField(name);
 field.setAccessible(true);
 field.set(target, value);
    }
}
