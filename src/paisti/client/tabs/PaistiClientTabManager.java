package paisti.client.tabs;

import haven.UI;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class PaistiClientTabManager {
    public interface Listener {
        void tabsChanged();
    }

    private static final PaistiClientTabManager INSTANCE = new PaistiClientTabManager();

    private final List<PaistiClientTab> tabs = new ArrayList<>();
    private final List<Listener> listeners = new ArrayList<>();
    private PaistiClientTab activeTab;
    private int nextId = 1;

    private PaistiClientTabManager() {
    }

    public static PaistiClientTabManager getInstance() {
        return INSTANCE;
    }

    public PaistiClientTab addLoginTab(UI ui) {
        return addLoginTab(ui, null);
    }

    public PaistiClientTab addLoginTab(UI ui, Runnable cancelLogin) {
        Objects.requireNonNull(ui, "ui");
        PaistiClientTab tab;
        synchronized(this) {
            tab = new PaistiClientTab(nextId++, ui, cancelLogin);
            tabs.add(tab);
            activeTab = tab;
        }
        fireTabsChanged();
        return tab;
    }

    public PaistiClientTab addPendingLoginTab() {
        PaistiClientTab tab;
        synchronized(this) {
            tab = new PaistiClientTab(nextId++, (UI)null);
            tabs.add(tab);
            if(activeTab == null)
                activeTab = tab;
        }
        fireTabsChanged();
        return tab;
    }

    public PaistiClientTab addOrHydrateLoginTab(UI ui, Runnable cancelLogin) {
        Objects.requireNonNull(ui, "ui");
        PaistiClientTab tab;
        synchronized(this) {
            tab = firstPendingLoginTabLocked();
            if(tab == null) {
                tab = new PaistiClientTab(nextId++, ui, cancelLogin);
                tabs.add(tab);
            } else {
                tab.hydrateLoginUi(ui, cancelLogin);
            }
            activeTab = tab;
        }
        fireTabsChanged();
        return tab;
    }

    public PaistiClientTab addSessionTab(PaistiSessionContext context) {
        Objects.requireNonNull(context, "context");
        PaistiClientTab tab;
        synchronized(this) {
            tab = new PaistiClientTab(nextId++, context);
            tabs.add(tab);
            activeTab = tab;
        }
        fireTabsChanged();
        return tab;
    }

    public synchronized void addListener(Listener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    public synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void fireTabsChanged() {
        List<Listener> copy;
        synchronized(this) {
            copy = new ArrayList<>(listeners);
        }
        for(Listener listener : copy)
            listener.tabsChanged();
    }

    public void requestNewLoginTab() {
        addPendingLoginTab();
        synchronized(this) {
            notifyAll();
        }
    }

    public synchronized void waitForLoginRequest() throws InterruptedException {
        while(firstPendingLoginTabLocked() == null)
            wait();
    }

    public UI prepareSessionUi(UI sessionUi) {
        Objects.requireNonNull(sessionUi, "sessionUi");
        UI loginUi;
        synchronized(this) {
            if(activeTab == null || !activeTab.isLogin())
                return null;
            loginUi = activeTab.ui();
            activeTab.replaceLoginUi(sessionUi);
        }
        fireTabsChanged();
        return loginUi;
    }

    public UI prepareSessionUi(UI previousLoginUi, UI sessionUi) {
        Objects.requireNonNull(previousLoginUi, "previousLoginUi");
        Objects.requireNonNull(sessionUi, "sessionUi");
        UI loginUi;
        synchronized(this) {
            PaistiClientTab tab = findTabLocked(previousLoginUi);
            if(tab == null || !tab.isLogin())
                return null;
            loginUi = tab.ui();
            tab.replaceLoginUi(sessionUi);
        }
        fireTabsChanged();
        return loginUi;
    }

    public PaistiClientTab convertActiveLoginToSession(PaistiSessionContext context) {
        Objects.requireNonNull(context, "context");
        PaistiClientTab tab;
        synchronized(this) {
            tab = findPreparedLoginTab(context.ui);
            if(tab == null && activeTab != null && activeTab.isLogin())
                tab = activeTab;
            if(tab == null) {
                tab = new PaistiClientTab(nextId++, context);
                tabs.add(tab);
            } else {
                tab.convertToSession(context);
            }
            activeTab = tab;
        }
        fireTabsChanged();
        return tab;
    }

    private PaistiClientTab findPreparedLoginTab(UI sessionUi) {
        if(sessionUi == null)
            return null;
        for(PaistiClientTab tab : tabs) {
            if(tab.isLogin() && tab.ui() == sessionUi)
                return tab;
        }
        return null;
    }

    private PaistiClientTab firstPendingLoginTabLocked() {
        for(PaistiClientTab tab : tabs) {
            if(tab.isLogin() && tab.ui() == null)
                return tab;
        }
        return null;
    }

    public synchronized List<PaistiClientTab> getTabs() {
        return new ArrayList<>(tabs);
    }

    public synchronized PaistiClientTab getActiveTab() {
        return activeTab;
    }

    public synchronized UI getActiveUi() {
        return activeTab == null ? null : activeTab.ui();
    }

    public synchronized List<PaistiSessionContext> getSessionContexts() {
        List<PaistiSessionContext> contexts = new ArrayList<>();
        for(PaistiClientTab tab : tabs) {
            if(tab.isSession() && tab.sessionContext() != null)
                contexts.add(tab.sessionContext());
        }
        return contexts;
    }

    public synchronized PaistiSessionContext findSessionContext(UI ui) {
        PaistiClientTab tab = findTab(ui);
        return (tab == null || !tab.isSession()) ? null : tab.sessionContext();
    }

    public synchronized boolean isManagedUi(UI ui) {
        return findTab(ui) != null;
    }

    public synchronized boolean isSessionUi(UI ui) {
        PaistiClientTab tab = findTab(ui);
        return tab != null && tab.isSession();
    }

    public void retireActive(PaistiSessionContext context) {
        boolean changed = false;
        synchronized(this) {
            if(activeTab == null || activeTab.sessionContext() != context)
                return;
            activeTab = selectableSuccessorLocked(activeTab);
            changed = true;
        }
        if(changed)
            fireTabsChanged();
    }

    private PaistiClientTab selectableSuccessorLocked(PaistiClientTab retiring) {
        int index = tabs.indexOf(retiring);
        if(index < 0)
            return null;
        for(int i = index + 1; i < tabs.size(); i++) {
            PaistiClientTab candidate = tabs.get(i);
            if(candidate.isSelectable())
                return candidate;
        }
        for(int i = index - 1; i >= 0; i--) {
            PaistiClientTab candidate = tabs.get(i);
            if(candidate.isSelectable())
                return candidate;
        }
        return null;
    }

    public PaistiClientTab replaceSessionContext(PaistiSessionContext oldContext, PaistiSessionContext newContext) {
        Objects.requireNonNull(newContext, "newContext");
        PaistiClientTab changed = null;
        synchronized(this) {
            for(PaistiClientTab tab : tabs) {
                if(tab.sessionContext() == oldContext) {
                    tab.convertToSession(newContext);
                    activeTab = tab;
                    changed = tab;
                    break;
                }
            }
            if(changed == null) {
                changed = new PaistiClientTab(nextId++, newContext);
                tabs.add(changed);
                activeTab = changed;
            }
        }
        fireTabsChanged();
        return changed;
    }

    public synchronized PaistiClientTab findTab(UI ui) {
        return findTabLocked(ui);
    }

    private PaistiClientTab findTabLocked(UI ui) {
        if(ui == null)
            return null;
        for(PaistiClientTab tab : tabs) {
            if(tab.ui() == ui)
                return tab;
        }
        return null;
    }

    public boolean activateTab(PaistiClientTab tab) {
        synchronized(this) {
            if(tab == null || !tab.isActivatable())
                return false;
            if(!tabs.contains(tab))
                return false;
            if(activeTab == tab)
                return true;
            activeTab = tab;
        }
        fireTabsChanged();
        return true;
    }

    public synchronized boolean isActiveUi(UI ui) {
        return activeTab != null && activeTab.ui() == ui;
    }

    public void pruneDeadSessions() {
        List<PaistiSessionContext> removed = new ArrayList<>();
        boolean requestLogin = false;
        synchronized(this) {
            for(java.util.Iterator<PaistiClientTab> it = tabs.iterator(); it.hasNext();) {
                PaistiClientTab tab = it.next();
                int index = tabs.indexOf(tab);
                PaistiSessionContext context = tab.sessionContext();
                if(context == null || context.isAlive())
                    continue;
                it.remove();
                removed.add(context);
                if(activeTab == tab)
                    activeTab = selectableAfterRemovalLocked(index);
            }
            if(tabs.isEmpty())
                requestLogin = true;
        }
        for(PaistiSessionContext context : removed)
            context.dispose();
        if(requestLogin)
            requestNewLoginTab();
        if(!removed.isEmpty())
            fireTabsChanged();
    }

    private PaistiClientTab selectableAfterRemovalLocked(int removedIndex) {
        for(int i = removedIndex; i < tabs.size(); i++) {
            PaistiClientTab candidate = tabs.get(i);
            if(candidate.isSelectable())
                return candidate;
        }
        for(int i = Math.min(removedIndex, tabs.size()) - 1; i >= 0; i--) {
            PaistiClientTab candidate = tabs.get(i);
            if(candidate.isSelectable())
                return candidate;
        }
        return null;
    }

    public void switchToNext() {
        switchBy(1);
    }

    public void switchToPrevious() {
        switchBy(-1);
    }

    private void switchBy(int delta) {
        boolean changed = false;
        synchronized(this) {
            if(tabs.isEmpty())
                return;
            int idx = tabs.indexOf(activeTab);
            if(idx < 0)
                idx = 0;
            int size = tabs.size();
            for(int i = 1; i <= size; i++) {
                PaistiClientTab candidate = tabs.get((idx + (delta * i) + size) % size);
                if(candidate.isActivatable()) {
                    changed = activeTab != candidate;
                    activeTab = candidate;
                    break;
                }
            }
        }
        if(changed)
            fireTabsChanged();
    }

    public void closeActiveTab() {
        PaistiClientTab tab;
        synchronized(this) {
            tab = activeTab;
        }
        closeTab(tab);
    }

    public void closeTab(PaistiClientTab tab) {
        if(tab == null)
            return;
        UI loginUi = null;
        PaistiSessionContext sessionContext = null;
        boolean requestLogin = false;
        synchronized(this) {
            if(!tabs.contains(tab))
                return;
            if(activeTab == tab)
                selectNeighbor(tab);
            if(tab.isLogin()) {
                tabs.remove(tab);
                loginUi = tab.ui();
                requestLogin = tabs.isEmpty();
            } else {
                sessionContext = tab.sessionContext();
                sessionContext.beginClose();
            }
        }
        if(loginUi != null) {
            tab.cancelLogin();
            synchronized(loginUi) {
                loginUi.destroy();
            }
        }
        if(requestLogin)
            requestNewLoginTab();
        if(sessionContext != null)
            sessionContext.close();
        fireTabsChanged();
    }

    private void selectNeighbor(PaistiClientTab closing) {
        activeTab = null;
        int idx = tabs.indexOf(closing);
        if(idx < 0)
            return;
        int size = tabs.size();
        for(int i = 1; i < size; i++) {
            PaistiClientTab candidate = tabs.get((idx - i + size) % size);
            if(candidate != closing && candidate.isSelectable()) {
                activeTab = candidate;
                return;
            }
        }
        for(int i = 1; i < size; i++) {
            PaistiClientTab candidate = tabs.get((idx - i + size) % size);
            if(candidate != closing && candidate.isLogin()) {
                activeTab = candidate;
                return;
            }
        }
    }

    synchronized void clearForTests() {
        tabs.clear();
        listeners.clear();
        activeTab = null;
        nextId = 1;
    }
}
