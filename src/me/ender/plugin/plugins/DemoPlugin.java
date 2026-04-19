package me.ender.plugin.plugins;

import haven.CFG;
import haven.Label;
import haven.UI;
import haven.Widget;
import haven.Window;

import java.awt.Color;

import me.ender.plugin.ClientPlugin;
import me.ender.plugin.PluginAction;
import me.ender.plugin.PluginContext;
import me.ender.plugin.PluginOptionSection;
import me.ender.plugin.PluginWindowEvent;
import me.ender.ui.CFGBox;

public class DemoPlugin implements ClientPlugin {
    private static boolean enabled() {
        return CFG.PLUGIN_DEMO_ENABLED.get();
    }

    @Override
    public String id() {
        return "demo";
    }

    private static void logWindowOpened(Window window) {
        if ((window == null) || (window.ui == null) || !enabled()) {
            return;
        }
        window.ui.msg("[demo plugin] window opened: " + window.caption(), Color.WHITE, null);
    }

    @Override
    public void register(PluginContext context) {
        context.addAction(PluginAction.of(id(), "demo.toggle", "paginae/add/plugins/demo_toggle", (owner, iact) -> {
            CFG.PLUGIN_DEMO_ENABLED.set(!enabled());
            return true;
        }, DemoPlugin::enabled));

        context.addOptionSection(PluginOptionSection.of(id(), "Demo Plugin", (wnd, panel, y) -> {
            Label help = panel.add(new Label("Enable the demo plugin and open a new window or show a hidden one to see its hook fire."), 0, y);
            y = help.c.y + help.sz.y + UI.scale(6);
            Widget toggle = panel.add(new CFGBox("Enable demo plugin", CFG.PLUGIN_DEMO_ENABLED, null, true), 0, y);
            return toggle.c.y + toggle.sz.y + UI.scale(10);
        }));

        context.onWidgetAdded((ui, child, parent) -> {
            if (child instanceof Window && child.visible()) {
                logWindowOpened((Window) child);
            }
        });

        context.onWindow((window, event) -> {
            if (event != PluginWindowEvent.SHOW) {
                return;
            }
            logWindowOpened(window);
        });
    }
}
