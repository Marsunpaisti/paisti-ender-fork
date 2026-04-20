package paisti.plugin.plugins;

import haven.CFG;
import haven.Coord;
import haven.Coord2d;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

import java.awt.Color;
import java.lang.reflect.Array;

import me.ender.ui.CFGBox;
import paisti.plugin.ClientPlugin;
import paisti.plugin.PluginAction;
import paisti.plugin.PluginContext;
import paisti.plugin.PluginOptionSection;

public class OutgoingWdgmsgPlugin implements ClientPlugin {
    private static final String PLUGIN_ID = "outgoing-wdgmsg";
    private static final String ACTION_ID = PLUGIN_ID + ".toggle";
    private static final String ACTION_RESOURCE_PATH = "paginae/add/plugins/demo_toggle";
    private static final String OPTION_SECTION_TITLE = "Outgoing Wdgmsg Logger";
    private static final String OPTION_HELP_TEXT = "Logs outgoing widget messages to chat with compact summaries. Warning: typed/raw strings may be logged.";
    private static final String OPTION_TOGGLE_LABEL = "Enable outgoing wdgmsg logger";
    private static final int MAX_STRING_PREVIEW = 120;

    private static boolean enabled() {
        return CFG.PLUGIN_DEMO_ENABLED.get();
    }

    @Override
    public String id() {
        return PLUGIN_ID;
    }

    @Override
    public void register(PluginContext context) {
        context.addAction(PluginAction.of(PLUGIN_ID, ACTION_ID, ACTION_RESOURCE_PATH, (owner, iact) -> {
            CFG.PLUGIN_DEMO_ENABLED.set(!enabled());
            return true;
        }, OutgoingWdgmsgPlugin::enabled));

        context.addOptionSection(PluginOptionSection.of(PLUGIN_ID, OPTION_SECTION_TITLE, (wnd, panel, y) -> {
            Label help = panel.add(new Label(OPTION_HELP_TEXT), 0, y);
            y = help.c.y + help.sz.y + UI.scale(6);
            Widget toggle = panel.add(new CFGBox(OPTION_TOGGLE_LABEL, CFG.PLUGIN_DEMO_ENABLED, null, true), 0, y);
            return toggle.c.y + toggle.sz.y + UI.scale(10);
        }));

        context.onOutgoingWidgetMessage((ui, sender, widgetId, msg, args) -> {
            if (!enabled()) {
                return;
            }
            String line = format(sender, widgetId, msg, args);
            if (ui != null) {
                ui.msg(line, Color.WHITE, null);
            }
            System.out.println(line);
        });
    }

    private static String format(Widget sender, int widgetId, String msg, Object[] args) {
        return "[wdgmsg] sender=" + summarizeSender(sender, widgetId) +
            " window=" + summarizeWindow(sender) +
            " msg=" + summarizeString(msg) +
            " args=" + summarize(args);
    }

    private static String summarizeSender(Widget sender, int widgetId) {
        if (sender == null) {
            return "<null>#" + widgetId;
        }
        return sender.getClass().getSimpleName() + "#" + widgetId;
    }

    private static String summarizeWindow(Widget sender) {
        Window window = (sender == null) ? null : sender.getparent(Window.class);
        String caption = (window == null) ? null : window.caption();
        return (caption == null || caption.trim().isEmpty()) ? "<none>" : summarizeString(caption);
    }

    private static String summarize(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof Object[]) {
            Object[] array = (Object[]) value;
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < array.length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(summarize(array[i]));
            }
            return out.append(']').toString();
        }
        Class<?> type = value.getClass();
        if (type.isArray()) {
            int length = Array.getLength(value);
            StringBuilder out = new StringBuilder("[");
            for (int i = 0; i < length; i++) {
                if (i > 0) {
                    out.append(", ");
                }
                out.append(summarize(Array.get(value, i)));
            }
            return out.append(']').toString();
        }
        if (value instanceof Coord) {
            Coord c = (Coord) value;
            return "Coord(" + c.x + ", " + c.y + ")";
        }
        if (value instanceof Coord2d) {
            Coord2d c = (Coord2d) value;
            return "Coord2d(" + c.x + ", " + c.y + ")";
        }
        if (value instanceof Widget) {
            Widget widget = (Widget) value;
            int id = (widget.ui == null) ? -1 : widget.wdgid();
            return widget.getClass().getSimpleName() + ((id >= 0) ? ("#" + id) : "");
        }
        if (value instanceof String) {
            return summarizeString((String) value);
        }
        return String.valueOf(value);
    }

    private static String summarizeString(String value) {
        StringBuilder out = new StringBuilder(Math.min(value.length(), MAX_STRING_PREVIEW) + 5);
        out.append('"');
        int count = 0;
        for (int i = 0; i < value.length(); i++) {
            String escaped = escapeChar(value.charAt(i));
            if (count + escaped.length() > MAX_STRING_PREVIEW) {
                out.append("...");
                out.append('"');
                return out.toString();
            }
            out.append(escaped);
            count += escaped.length();
        }
        out.append('"');
        return out.toString();
    }

    private static String escapeChar(char ch) {
        switch (ch) {
            case '\\': return "\\\\";
            case '"': return "\\\"";
            case '\n': return "\\n";
            case '\r': return "\\r";
            case '\t': return "\\t";
            default: return String.valueOf(ch);
        }
    }
}
