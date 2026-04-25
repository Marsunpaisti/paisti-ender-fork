package paisti.client.tabs;

import haven.RemoteUI;
import haven.Session;
import haven.UI;

public class PaistiSessionContext {
    public final Session session;
    public final UI ui;
    public final RemoteUI remoteUI;
    private boolean disposed = false;
    private boolean retiring = false;

    public PaistiSessionContext(Session session, UI ui, RemoteUI remoteUI) {
	this.session = session;
	this.ui = ui;
	this.remoteUI = remoteUI;
    }

    public boolean isAlive() {
	return(!session.isClosed());
    }

    public synchronized boolean isSelectable() {
	return(!retiring && isAlive());
    }

    public synchronized boolean beginClose() {
	if(retiring)
	    return(false);
	retiring = true;
	return(true);
    }

    public void close() {
	beginClose();
	session.close();
    }

    public void dispose() {
	synchronized(this) {
	    if(disposed)
		return;
	    disposed = true;
	}
	session.close();
	synchronized(ui) {
	    ui.destroy();
	}
    }
}
