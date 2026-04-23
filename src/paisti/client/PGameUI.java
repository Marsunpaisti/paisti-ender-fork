package paisti.client;

import haven.*;
import me.ender.ui.CFGBox;
import paisti.plugin.PaistiPlugin;
import paisti.plugin.PluginConfig;
import paisti.plugin.PluginDescription;

public class PGameUI extends GameUI {

    private boolean pluginPanelInitialized;

    public PGameUI(String chrid, long plid, String genus) {
	super(chrid, plid, genus);
    }

    @Override
    protected void attached() {
	super.attached();
	initPluginPanel();
    }

    private void initPluginPanel() {
	if(pluginPanelInitialized || opts == null)
	    return;

	int x = 0;
	int y = 0;
	Widget title = opts.plugins.add(new Label("Plugins", OptWnd.LBL_FNT), x, y);
	y += title.sz.y + UI.scale(10);

	for(PaistiPlugin plugin : PUI.of(ui).pluginService().getConfigurablePlugins()) {
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
