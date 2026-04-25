package paisti.client.tabs;

import haven.RemoteUI;
import haven.UI;

public class PaistiSessionRunner extends UI.Runner.Proxy {
    private final RemoteUI remote;

    public PaistiSessionRunner(RemoteUI remote) {
	super(remote);
	this.remote = remote;
    }

    @Override
    public UI.Runner run(UI ui) {
	remote.attach(ui);
	PaistiClientTabManager.getInstance().convertActiveLoginToSession(new PaistiSessionContext(remote.sess, ui, remote));
	return(new PaistiLobbyRunner());
    }
}
