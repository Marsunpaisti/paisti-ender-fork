package paisti.pluginv2;

import haven.UI;

public abstract class PaistiPlugin {
    protected UI ui;

    public PaistiPlugin(UI ui) {
	this.ui = ui;
    }

    public abstract void startUp();
    public abstract void shutDown();
}
