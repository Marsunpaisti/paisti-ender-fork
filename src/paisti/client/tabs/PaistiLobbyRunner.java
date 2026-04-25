package paisti.client.tabs;

import haven.UI;
import paisti.client.PBootstrap;

public class PaistiLobbyRunner implements UI.Runner {
    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
	PaistiClientTabManager.getInstance().waitForLoginRequest();
	return(new PBootstrap());
    }
}
