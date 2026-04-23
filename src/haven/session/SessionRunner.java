package haven.session;

import haven.RemoteUI;
import haven.UI;

public class SessionRunner extends UI.Runner.Proxy {
    private final RemoteUI remote;

    public SessionRunner(RemoteUI remote) {
        super(remote);
        this.remote = remote;
    }

    @Override
    public UI.Runner run(UI ui) {
        remote.attach(ui);
        SessionManager.getInstance().addSession(new SessionContext(remote.sess, ui, remote));
        return new LobbyRunner();
    }
}
