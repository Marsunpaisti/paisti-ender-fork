package paisti.client;

import haven.*;
import me.ender.ui.CFGBox;
import paisti.client.tabs.PaistiClientTabHotkeys;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.PluginConfig;
import paisti.plugin.PluginDescription;

import java.awt.event.KeyEvent;

public class PGameUI extends GameUI {
    public static final KeyBinding kb_addsession = KeyBinding.get("session-add-arrow", KeyMatch.forcode(KeyEvent.VK_UP, KeyMatch.C | KeyMatch.S));
    public static final KeyBinding kb_removesession = KeyBinding.get("session-remove-arrow", KeyMatch.forcode(KeyEvent.VK_DOWN, KeyMatch.C | KeyMatch.S));
    public static final KeyBinding kb_prevsession = KeyBinding.get("session-prev-arrow", KeyMatch.forcode(KeyEvent.VK_LEFT, KeyMatch.C | KeyMatch.S));
    public static final KeyBinding kb_nextsession = KeyBinding.get("session-next-arrow", KeyMatch.forcode(KeyEvent.VK_RIGHT, KeyMatch.C | KeyMatch.S));

    private boolean pluginPanelInitialized;

    public PGameUI(String chrid, long plid, String genus) {
	super(chrid, plid, genus);
    }

    @Override
    protected void attached() {
	super.attached();
	initPluginPanel();
    }

    @Override
    public boolean globtype(GlobKeyEvent ev) {
	if(super.globtype(ev))
	    return(true);
	if(kb_addsession.key().match(ev)) {
	    PaistiClientTabHotkeys.addSession();
	    return(true);
	} else if(kb_removesession.key().match(ev)) {
	    PaistiClientTabHotkeys.removeSession();
	    return(true);
	} else if(kb_prevsession.key().match(ev)) {
	    PaistiClientTabHotkeys.previousSession();
	    return(true);
	} else if(kb_nextsession.key().match(ev)) {
	    PaistiClientTabHotkeys.nextSession();
	    return(true);
	}
	return(false);
    }

    private void initPluginPanel() {
	if(pluginPanelInitialized || opts == null)
	    return;

	int x = 0;
	int y = 0;
	Widget title = opts.plugins.add(new Label("Plugins", OptWnd.LBL_FNT), x, y);
	y += title.sz.y + UI.scale(10);

	for (PaistiPlugin plugin : PUI.of(ui).pluginService().getConfigurablePlugins()) {
	    PluginDescription description = plugin.getClass().getAnnotation(PluginDescription.class);
	    CFGBox toggle = new CFGBox(
		plugin.getName(),
		PluginConfig.enabled(description.configName(), description.enabledByDefault())
	    );
	    toggle.set(v -> PUI.of(ui).pluginService().syncActivePlugins());
	    opts.plugins.add(toggle, x, y);
	    y += toggle.sz.y;
	    if(!description.description().isEmpty()) {
		Widget desc = opts.plugins.add(
		    new Label(description.description()),
		    x + UI.scale(15), y + UI.scale(2)
		);
		y += desc.sz.y + UI.scale(7);
	    } else {
		y += UI.scale(5);
	    }
	}

	/* Add "Back" button and pack — equivalent to old finishPanel() */
	opts.plugins.add(
	    opts.new PButton(UI.scale(200), "Back", 27, opts.main),
	    0, opts.plugins.contentsz().y + UI.scale(35)
	);
	opts.plugins.pack();

	pluginPanelInitialized = true;
	if(opts.current == opts.plugins)
	    opts.cresize(opts.plugins);
	else
	    opts.main.pack();
    }
}
