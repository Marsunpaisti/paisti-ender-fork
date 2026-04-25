package paisti.client;

import haven.GLPanel;
import haven.LWJGLPanel;

public class PLWJGLPanel extends LWJGLPanel {
    @Override
    protected GLPanel.Loop createLoop() {
	return(new PGLPanelLoop(this));
    }
}
