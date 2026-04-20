package paisti.hooks.events;

import haven.*;

import java.lang.reflect.Array;
import java.util.Arrays;

public class BeforeOutgoingWidgetMessage {
    private static final int MAX_STRING_PREVIEW = 120;

    UI ui;
    Widget sender;
    int widgetId;
    String msg;
    Object[] args;
    boolean isConsumed;

    public BeforeOutgoingWidgetMessage(UI ui, Widget sender, int widgetId, String msg, Object[] args) {
	this.ui = ui;
	this.sender = sender;
	this.widgetId = widgetId;
	this.msg = msg;
	this.args = args;
    	this.isConsumed = false;
    }

    public UI getUi() {
	return ui;
    }

    public Widget getSender() {
	return sender;
    }

    public void setSender(Widget sender) {
	this.sender = sender;
    }

    public int getWidgetId() {
	return widgetId;
    }

    public void setWidgetId(int widgetId) {
	this.widgetId = widgetId;
    }

    public String getMsg() {
	return msg;
    }

    public void setMsg(String msg) {
	this.msg = msg;
    }

    public Object[] getArgs() {
	return args;
    }

    public void setArgs(Object[] args) {
	this.args = args;
    }

    public boolean isConsumed() {
	return isConsumed;
    }

    public void consume() {
	isConsumed = true;
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

    @Override
    public String toString() {
	return "BeforeOutgoingWidgetMessage{" +
	    "sender=" + summarizeSender(sender, widgetId) +
	    ", msg='" + summarizeString(msg) + '\'' +
	    ", args=" + summarize(args) +
	    ", isConsumed=" + isConsumed +
	    '}';
    }
}
