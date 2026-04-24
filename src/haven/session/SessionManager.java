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

    public void removeSession(SessionContext ctx) {
        SessionContext removed = removeRegisteredSession(ctx);
        if(removed != null) {
            removed.dispose();
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

    public synchronized boolean isActiveSessionUi(UI ui) {
        return activeSession != null && activeSession.ui == ui;
    }

    public synchronized SessionContext getActiveSession() {
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
        int size = sessions.size();
        for(int i = 1; i <= size; i++) {
            SessionContext candidate = sessions.get((idx + i) % size);
            if(candidate.isSelectable()) {
                activeSession = candidate;
                return;
            }
        }
        /* All sessions are dead/retiring — leave activeSession as-is */
    }

    public synchronized void switchToPrevious() {
        if(sessions.size() <= 1) {
            return;
        }
        int idx = sessions.indexOf(activeSession);
        if(idx < 0) {
            idx = 0;
        }
        int size = sessions.size();
        for(int i = 1; i <= size; i++) {
            SessionContext candidate = sessions.get((idx - i + size) % size);
            if(candidate.isSelectable()) {
                activeSession = candidate;
                return;
            }
        }
        /* All sessions are dead/retiring — leave activeSession as-is */
    }

    public void removeActiveSession() {
        SessionContext ctx;
        boolean wakeLogin = false;
        synchronized(this) {
            ctx = activeSession;
            if(ctx == null) {
                return;
            }
            int idx = sessions.indexOf(ctx);
            int size = sessions.size();
            activeSession = null;
            if(idx >= 0) {
                for(int i = 1; i < size; i++) {
                    SessionContext candidate = sessions.get((idx - i + size) % size);
                    if(candidate != ctx && candidate.isSelectable()) {
                        activeSession = candidate;
                        break;
                    }
                }
            }
            wakeLogin = (activeSession == null);
        }
        ctx.close();
        if(wakeLogin) {
            requestAddAccount();
        }
    }

    /**
     * If the given context is currently the active session, advance
     * activeSession to the next live session (excluding {@code ctx}),
     * or null if none remains.  This prevents the GL loop's
     * active-session sync from rebinding to a retiring context.
     */
    public synchronized void retireActive(SessionContext ctx) {
        if(activeSession != ctx) {
            return;
        }
        for(SessionContext other : sessions) {
            if(other != ctx && other.isSelectable()) {
                activeSession = other;
                return;
            }
        }
        activeSession = null;
    }

    public void requestAddAccount() {
        addAccountSignal.release();
    }

    public void waitForAddRequest() throws InterruptedException {
        addAccountSignal.acquire();
        /* Drain any extra permits that accumulated from repeated
         * requestAddAccount() calls, so we process exactly one
         * add-account cycle per wakeup. */
        addAccountSignal.drainPermits();
    }

    public void pruneDeadSessions() {
        List<SessionContext> removed = new ArrayList<>();
        synchronized(this) {
        for(Iterator<SessionContext> it = sessions.iterator(); it.hasNext();) {
            SessionContext ctx = it.next();
            if(ctx.isAlive()) {
                continue;
            }
            removeRegisteredSession(it, ctx);
            removed.add(ctx);
        }
        }
        for(SessionContext ctx : removed) {
            ctx.dispose();
        }
    }

    private synchronized SessionContext removeRegisteredSession(SessionContext ctx) {
        Iterator<SessionContext> it = sessions.iterator();
        while(it.hasNext()) {
            SessionContext current = it.next();
            if(current == ctx) {
                removeRegisteredSession(it, current);
                return current;
            }
        }
        return null;
    }

    private void removeRegisteredSession(Iterator<SessionContext> it, SessionContext ctx) {
        it.remove();
        if(activeSession == ctx) {
            activeSession = sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
        }
    }
}
