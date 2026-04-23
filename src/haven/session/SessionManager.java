package haven.session;

import haven.UI;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Semaphore;

public class SessionManager {
    private static final SessionManager instance = new SessionManager();

    public static SessionManager getInstance() {
        return instance;
    }

    private final List<SessionContext> sessions = new ArrayList<>();
    private final Semaphore addAccountSignal = new Semaphore(0);
    private volatile SessionContext activeSession;

    private SessionManager() {
    }

    public synchronized void addSession(SessionContext ctx) {
        sessions.add(ctx);
        activeSession = ctx;
    }

    public synchronized void removeSession(SessionContext ctx) {
        if(!sessions.remove(ctx)) {
            return;
        }
        ctx.close();
        synchronized(ctx.ui) {
            ctx.ui.destroy();
        }
        if(activeSession == ctx) {
            activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
    }

    public synchronized List<SessionContext> getSessions() {
        return new ArrayList<>(sessions);
    }

    public synchronized boolean isSessionUi(UI ui) {
        for(SessionContext ctx : sessions) {
            if(ctx.ui == ui) {
                return true;
            }
        }
        return false;
    }

    public SessionContext getActiveSession() {
        return activeSession;
    }

    public synchronized void switchToNext() {
        if(sessions.size() <= 1) {
            return;
        }
        int idx = sessions.indexOf(activeSession);
        if(idx < 0) {
            idx = 0;
        }
        activeSession = sessions.get((idx + 1) % sessions.size());
    }

    public void requestAddAccount() {
        addAccountSignal.release();
    }

    public void waitForAddRequest() throws InterruptedException {
        addAccountSignal.acquire();
    }

    public synchronized void pruneDeadSessions() {
        for(Iterator<SessionContext> it = sessions.iterator(); it.hasNext(); ) {
            SessionContext ctx = it.next();
            if(ctx.isAlive()) {
                continue;
            }
            synchronized(ctx.ui) {
                ctx.ui.destroy();
            }
            it.remove();
            if(activeSession == ctx) {
                activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
            }
        }
    }
}
