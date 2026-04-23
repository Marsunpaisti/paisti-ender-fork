package haven.session;

import haven.RemoteUI;
import haven.Session;
import haven.UI;

public class SessionContext {
    public final Session session;
    public final UI ui;
    public final RemoteUI remoteUI;
    private boolean disposed = false;

    public SessionContext(Session session, UI ui, RemoteUI remoteUI) {
        this.session = session;
        this.ui = ui;
        this.remoteUI = remoteUI;
    }

    public boolean isAlive() {
        return !session.isClosed();
    }

    public void close() {
        session.close();
    }

    public void dispose() {
        synchronized(this) {
            if(disposed) {
                return;
            }
            disposed = true;
        }
        session.close();
        synchronized(ui) {
            ui.destroy();
        }
    }
}
