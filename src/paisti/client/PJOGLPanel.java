package paisti.client;

import haven.GLPanel;
import haven.JOGLPanel;

public class PJOGLPanel extends JOGLPanel {
    @Override
    protected GLPanel.Loop createLoop() {
	return(new PGLPanelLoop(this));
    }
}
