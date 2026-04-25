package paisti.client.tabs;

import haven.Session;
import haven.UI;

import java.util.Objects;

public class PaistiClientTab {
    public enum State {
        LOGIN,
        SESSION
    }

    private final int id;
    private State state;
    private UI ui;
    private PaistiSessionContext sessionContext;
    private Runnable cancelLogin;

    PaistiClientTab(int id, UI ui) {
        this(id, ui, null);
    }

    PaistiClientTab(int id, UI ui, Runnable cancelLogin) {
        this.id = id;
        this.state = State.LOGIN;
        this.ui = ui;
        this.sessionContext = null;
        this.cancelLogin = cancelLogin;
    }

    PaistiClientTab(int id, PaistiSessionContext sessionContext) {
        sessionContext = Objects.requireNonNull(sessionContext, "sessionContext");
        this.id = id;
        this.state = State.SESSION;
        this.ui = sessionContext.ui;
        this.sessionContext = sessionContext;
        this.cancelLogin = null;
    }

    void replaceLoginUi(UI ui) {
        if(!isLogin())
            throw(new IllegalStateException("tab is not a login tab"));
        this.ui = Objects.requireNonNull(ui, "ui");
    }

    void hydrateLoginUi(UI ui, Runnable cancelLogin) {
        replaceLoginUi(ui);
        this.cancelLogin = cancelLogin;
    }

    void convertToSession(PaistiSessionContext sessionContext) {
        this.sessionContext = Objects.requireNonNull(sessionContext, "sessionContext");
        this.ui = sessionContext.ui;
        this.state = State.SESSION;
        this.cancelLogin = null;
    }

    void cancelLogin() {
        if(cancelLogin != null)
            cancelLogin.run();
    }

    public int id() {
        return id;
    }

    public State state() {
        return state;
    }

    public boolean isLogin() {
        return state == State.LOGIN;
    }

    public boolean isSession() {
        return state == State.SESSION;
    }

    public UI ui() {
        return ui;
    }

    public PaistiSessionContext sessionContext() {
        return sessionContext;
    }

    public boolean isSelectable() {
        if(isSession())
            return sessionContext != null && sessionContext.isSelectable();
        return ui != null;
    }

    public boolean isActivatable() {
        return isLogin() || isSelectable();
    }

    public String label() {
        if(isLogin())
            return "Login";
        if(sessionContext != null) {
            Session session = sessionContext.session;
            if(session != null && session.user != null && session.user.name != null) {
                String name = session.user.name.trim();
                if(!name.isEmpty())
                    return name;
            }
        }
        return "Session";
    }
}
