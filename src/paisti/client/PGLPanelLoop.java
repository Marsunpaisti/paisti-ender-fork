package paisti.client;

import haven.Coord;
import haven.GLPanel;
import haven.PMessage;
import haven.RemoteUI;
import haven.Session;
import haven.UI;
import haven.Warning;
import paisti.client.tabs.PaistiLobbyRunner;
import paisti.client.tabs.PaistiClientTab;
import paisti.client.tabs.PaistiClientTabManager;
import paisti.client.tabs.PaistiSessionRunner;
import paisti.client.tabs.PaistiSessionContext;

public class PGLPanelLoop extends GLPanel.Loop {
    public PGLPanelLoop(GLPanel panel) {
	super(panel);
    }

    @Override
    protected UI makeui(UI.Runner runner) {
	return(new PUI(p, new Coord(p.getSize()), runner));
    }

    @Override
    protected UI reuseManagedUiForRunner(UI.Runner runner) {
	if(runner instanceof PaistiLobbyRunner) {
	    PaistiClientTab active = PaistiClientTabManager.getInstance().getActiveTab();
	    if(active != null && active.isSession() && active.ui() == this.ui)
		return(this.ui);
	}
	return(null);
    }

    @Override
    protected UI syncActiveUi(UI current) {
	PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
	UI active = manager.getActiveUi();
	if(active != null && active != current)
	    return(active);
	return(current);
    }

    @Override
    protected boolean isManagedUi(UI ui) {
	return(PaistiClientTabManager.getInstance().isManagedUi(ui));
    }

    @Override
    protected boolean isManagedSessionUi(UI ui) {
	return(PaistiClientTabManager.getInstance().isSessionUi(ui));
    }

    @Override
    protected boolean isActiveManagedUi(UI ui) {
	return(PaistiClientTabManager.getInstance().isActiveUi(ui));
    }

    @Override
    protected void registerNewUi(UI.Runner runner, UI previousUi, UI newUi) {
	PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
	if(runner instanceof PaistiSessionRunner && previousUi != null)
	    manager.prepareSessionUi(previousUi, newUi);
	else if(runner instanceof PBootstrap)
	    manager.addOrHydrateLoginTab(newUi, ((PBootstrap)runner)::cancel);
    }

    @Override
    protected void pruneManagedSessions() {
	PaistiClientTabManager.getInstance().pruneDeadSessions();
    }

    @Override
    protected void tickBackgroundManagedSessions(UI visibleUi) {
	PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
	for(PaistiSessionContext context : manager.getSessionContexts()) {
	    UI sui = context.ui;
	    if(sui == null || sui == visibleUi || !context.isSelectable())
		continue;
	    synchronized(sui) {
		boolean returnHit = false;
		if(context.session != null && context.remoteUI != null) {
		    PMessage msg;
		    while((msg = context.session.pollUIMsg()) != null) {
			if(msg instanceof RemoteUI.Return) {
			    Session returned = ((RemoteUI.Return)msg).ret;
			    if(returned != null) {
				try {
				    returned.close();
				} catch(Exception e) {
				    new Warning(e, "closing leaked Return session").issue();
				}
			    }
			    context.close();
			    returnHit = true;
			    break;
			}
			try {
			    if(!context.remoteUI.dispatchMessage(msg, sui))
				break;
			} catch(InterruptedException e) {
			    Thread.currentThread().interrupt();
			    return;
			}
		    }
		}
		if(!returnHit) {
		    if(sui.sess != null && sui.sess.glob != null && sui.sess.glob.map != null) {
			sui.sess.glob.ctick();
			sui.sess.glob.map.sendreqs();
		    }
		    sui.tick();
		}
	    }
	}
    }

    @Override
    protected boolean serviceVisibleManagedSession(UI visibleUi) {
	PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
	PaistiSessionContext context = manager.findSessionContext(visibleUi);
	if(context == null || context.session == null || context.remoteUI == null)
	    return(true);
	if(!context.isSelectable() || !manager.isActiveUi(visibleUi))
	    return(false);
	synchronized(visibleUi) {
	    PMessage msg;
	    while((msg = context.session.pollUIMsg()) != null) {
		if(msg instanceof RemoteUI.Return) {
		    Session returned = ((RemoteUI.Return)msg).ret;
		    context.close();
		    manager.retireActive(context);
		    if(returned != null) {
			RemoteUI newRemote = new RemoteUI(returned);
			UI newUi = makeui(newRemote);
			initializeUi(newUi);
			newRemote.attach(newUi);
			newRemote.init(newUi);
			manager.replaceSessionContext(context, new PaistiSessionContext(returned, newUi, newRemote));
			setCurrentUi(newUi);
			context.dispose();
		    } else {
			UI successor = manager.getActiveUi();
			if(successor != null)
			    setCurrentUi(successor);
			else {
			    setCurrentUi(null);
			    manager.requestNewLoginTab();
			}
		    }
		    return(false);
		}
		try {
		    if(!context.remoteUI.dispatchMessage(msg, visibleUi))
			break;
		} catch(InterruptedException e) {
		    Thread.currentThread().interrupt();
		    return(true);
		}
	    }
	}
	return(true);
    }

    @Override
    protected UI handlePrunedManagedUi(UI visibleUi) {
	PaistiClientTabManager manager = PaistiClientTabManager.getInstance();
	if(manager.isManagedUi(visibleUi))
	    return(visibleUi);
	    UI successor = manager.getActiveUi();
	    if(successor != null)
	    return(setCurrentUi(successor));
	else {
	    setCurrentUi(null);
	    manager.requestNewLoginTab();
	}
	return(null);
    }
}
