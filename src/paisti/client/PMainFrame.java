package paisti.client;

import haven.Coord;
import haven.Console;
import haven.MainFrame;
import haven.RemoteUI;
import haven.SessWidget;
import haven.Session;
import haven.Transport;
import haven.UI;
import haven.UIPanel;
import paisti.client.tabs.PaistiClientTabBar;
import paisti.client.tabs.PaistiClientTabManager;
import paisti.client.tabs.PaistiSessionRunner;

import java.net.SocketAddress;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Panel;
import java.util.Map;

public class PMainFrame extends MainFrame {
    private class RendererWrapper extends Panel implements Console.Directory {
 private RendererWrapper() {
     super(new BorderLayout());
 }

 @Override
 public Map<String, Console.Command> findcmds() {
     return(PMainFrame.this.findcmds());
 }
    }

    static {
	SessWidget.setConnector(PaistiSessions::connect);
    }

    public static class Factory extends MainFrame.ClientFactory {
	@Override
	public MainFrame createFrame(Coord isz) {
	    return(new PMainFrame(isz));
	}

	@Override
	public UI.Runner sessionRunner(RemoteUI remote) {
	    return(new PaistiSessionRunner(remote));
	}

	@Override
	public UI.Runner replayRunner(RemoteUI remote) {
	    return(new PaistiSessionRunner(remote));
	}

	@Override
	public Session connect(SocketAddress server, Session.User user, boolean encrypt, byte[] cookie, Object... args) throws InterruptedException {
	    return(PaistiSessions.connect(server, user, encrypt, cookie, args));
	}

	@Override
	public Session create(Transport conn, Session.User user) {
	    return(PaistiSessions.create(conn, user));
	}
    }

    public static void main(final String[] args) {
	MainFrame.main(args, new Factory());
    }

    public PMainFrame(Coord isz) {
	super(isz);
    }

    @Override
    protected UIPanel renderer() {
	String id = haven.MainFrame.renderer.get();
	switch(id) {
	case "jogl":
	    return(new PJOGLPanel());
	case "lwjgl":
	    return(new PLWJGLPanel());
	default:
	    throw(new RuntimeException("invalid renderer specified in haven.renderer: " + id));
	}
    }

    @Override
    protected UI.Runner defaultRunner() {
	return(new PBootstrap());
    }

    @Override
    protected Component wrapRenderer(Component renderer) {
	Panel panel = new RendererWrapper();
	panel.add(new PaistiClientTabBar(PaistiClientTabManager.getInstance(), renderer), BorderLayout.NORTH);
	panel.add(renderer, BorderLayout.CENTER);
	return(panel);
    }
}
