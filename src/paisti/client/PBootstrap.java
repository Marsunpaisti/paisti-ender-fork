package paisti.client;

import haven.AccountList;
import haven.AuthClient;
import haven.Bootstrap;
import haven.Connection;
import haven.Coord;
import haven.Digest;
import haven.LoginScreen;
import haven.NamedSocketAddress;
import haven.RemoteUI;
import haven.Session;
import haven.UI;
import haven.Utils;
import paisti.client.tabs.PaistiLobbyRunner;
import paisti.client.tabs.PaistiSessionRunner;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

public class PBootstrap extends Bootstrap {
    private final Queue<LoginMessage> msgs = new LinkedList<>();
    private boolean cancelled;
    private String inituser;
    private byte[] initcookie;
    private byte[] inittoken;

    private static final LoginMessage CANCEL = new LoginMessage(-1, "cancel");

    private static class LoginMessage {
        private final int id;
        private final String name;
        private final Object[] args;

        private LoginMessage(int id, String name, Object... args) {
            this.id = id;
            this.name = name;
            this.args = args;
        }
    }

    @Override
    public void setinitcookie(String username, byte[] cookie) {
        inituser = username;
        initcookie = cookie;
    }

    @Override
    public void setinittoken(String username, byte[] token) {
        inituser = username;
        inittoken = token;
    }

    public void cancel() {
        synchronized(msgs) {
            cancelled = true;
            msgs.clear();
            msgs.notifyAll();
        }
    }

    private LoginMessage getmsg() throws InterruptedException {
        LoginMessage msg;
        synchronized(msgs) {
            if(cancelled)
                return(CANCEL);
            while((msg = msgs.poll()) == null) {
                msgs.wait();
                if(cancelled)
                    return(CANCEL);
            }
            if(cancelled)
                return(CANCEL);
            return(msg);
        }
    }

    String takeNextMessageNameForTests() throws InterruptedException {
        return(getmsg().name);
    }

    private boolean isCancelled() {
        synchronized(msgs) {
            return(cancelled);
        }
    }

    private UI.Runner cancelIfRequested(Session session) {
        if(!isCancelled())
            return(null);
        if(session != null)
            session.close();
        return(new PaistiLobbyRunner());
    }

    private String getpref(String name, String def) {
        return(Utils.getpref(name + "@" + confname, def));
    }

    private void setpref(String name, String val) {
        Utils.setpref(name + "@" + confname, val);
    }

    private static byte[] getprefb(String name, String confname, byte[] def, boolean zerovalid) {
        String sv = Utils.getpref(name + "@" + confname, null);
        if(sv == null)
            return(def);
        byte[] ret = Utils.hex.dec(sv);
        if((ret.length == 0) && !zerovalid)
            return(def);
        return(ret);
    }

    private static String mangleuser(String user) {
        if(user.length() <= 32)
            return(user);
        return(Utils.hex.enc(Digest.hash(Digest.MD5, user.getBytes(Utils.utf8))));
    }

    private static void preferhost(List<InetSocketAddress> hosts, SocketAddress prev) {
        if((prev == null) || !(prev instanceof InetSocketAddress))
            return;
        InetAddress host = ((InetSocketAddress)prev).getAddress();
        Collections.sort(hosts, (a, b) -> {
            boolean pa = Utils.eq(a.getAddress(), host), pb = Utils.eq(b.getAddress(), host);
            if(pa && pb)
                return(0);
            else if(pa)
                return(-1);
            else if(pb)
                return(1);
            else
                return(0);
        });
    }

    protected Session connectSession(SocketAddress address, Session.User acct, boolean encrypt, byte[] cookie, Object... args) throws InterruptedException {
        return(PaistiSessions.connect(address, acct, encrypt, cookie, args));
    }

    @Override
    public UI.Runner run(UI ui) throws InterruptedException {
        UI.Runner cancelled;
        Session sess = null;
        if((cancelled = cancelIfRequested(null)) != null)
            return(cancelled);
        ui.setreceiver(this);
        if((cancelled = cancelIfRequested(null)) != null)
            return(cancelled);
        ui.newwidgetp(1, ($1, $2) -> new LoginScreen(confname), 0, new Object[] {Coord.z});
        if((cancelled = cancelIfRequested(null)) != null)
            return(cancelled);
        String loginname = getpref("loginname", "");
        boolean savepw = false;
        NamedSocketAddress defserv = new NamedSocketAddress(server.host, gameport.get());
        retry: do {
            byte[] cookie, token;
            Session.User acct;
            SocketAddress authaddr = null;
            List<NamedSocketAddress> hosts = Collections.emptyList();
            if((cancelled = cancelIfRequested(null)) != null)
                return(cancelled);
            if(initcookie != null) {
                acct = new Session.User(inituser);
                cookie = initcookie;
                initcookie = null;
            } else if((inituser != null) && (inittoken != null)) {
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                ui.uimsg(1, "prg", "Authenticating...");
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                byte[] inittoken = this.inittoken;
                this.inittoken = null;
                authed: try(AuthClient auth = new AuthClient(server)) {
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    authaddr = auth.address();
                    if(!Arrays.equals(inittoken, getprefb("lasttoken-" + mangleuser(inituser), confname, null, false))) {
                        String authed = null;
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        try {
                            authed = new AuthClient.TokenCred(inituser, inittoken).tryauth(auth);
                        } catch(AuthClient.Credentials.AuthException e) {
                        }
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        setpref("lasttoken-" + mangleuser(inituser), Utils.hex.enc(inittoken));
                        if(authed != null) {
                            acct = new Session.User(authed);
                            cookie = auth.getcookie();
                            if(Connection.encrypt.get())
                                acct.alias(auth.getalias());
                            hosts = auth.gethosts(defserv);
                            settoken(authed, confname, auth.gettoken());
                            AccountList.storeAccount(authed, Utils.byte2hex(auth.gettoken()));
                            if((cancelled = cancelIfRequested(null)) != null)
                                return(cancelled);
                            break authed;
                        }
                    }
                    if((token = gettoken(inituser, confname)) != null) {
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        try {
                            String authed = new AuthClient.TokenCred(inituser, token).tryauth(auth);
                            if((cancelled = cancelIfRequested(null)) != null)
                                return(cancelled);
                            acct = new Session.User(authed);
                            cookie = auth.getcookie();
                            if(Connection.encrypt.get())
                                acct.alias(auth.getalias());
                            hosts = auth.gethosts(defserv);
                            break authed;
                        } catch(AuthClient.Credentials.AuthException e) {
                            settoken(inituser, confname, null);
                        }
                    }
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    ui.uimsg(1, "error", "Launcher login expired");
                    continue retry;
                } catch(IOException e) {
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    ui.uimsg(1, "error", e.getMessage());
                    continue retry;
                }
            } else {
                AuthClient.Credentials creds;
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                ui.uimsg(1, "login");
                while(true) {
                    LoginMessage msg = getmsg();
                    if("cancel".equals(msg.name))
                        return(new PaistiLobbyRunner());
                    if(msg.id == 1) {
                        if("login".equals(msg.name)) {
                            creds = (AuthClient.Credentials) msg.args[0];
                            savepw = (Boolean) msg.args[1];
                            loginname = creds.name();
                            break;
                        }
                    }
                }
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                ui.uimsg(1, "prg", "Authenticating...");
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                try(AuthClient auth = new AuthClient(server)) {
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    authaddr = auth.address();
                    try {
                        acct = new Session.User(creds.tryauth(auth));
                    } catch(AuthClient.Credentials.AuthException e) {
                        settoken(creds.name(), confname, null);
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        ui.uimsg(1, "error", e.getMessage());
                        continue retry;
                    }
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    cookie = auth.getcookie();
                    if(Connection.encrypt.get())
                        acct.alias(auth.getalias());
                    if(savepw) {
                        byte[] ntoken = (creds instanceof AuthClient.TokenCred) ? ((AuthClient.TokenCred)creds).token : auth.gettoken();
                        settoken(acct.name, confname, ntoken);
                        AccountList.storeAccount(acct.name, Utils.byte2hex(ntoken));
                    }
                    hosts = auth.gethosts(defserv);
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                } catch(UnknownHostException e) {
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    ui.uimsg(1, "error", "Could not locate server");
                    continue retry;
                } catch(IOException e) {
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    ui.uimsg(1, "error", e.getMessage());
                    continue retry;
                }
            }
            if((cancelled = cancelIfRequested(null)) != null)
                return(cancelled);
            ui.uimsg(1, "prg", "Connecting...");
            if((cancelled = cancelIfRequested(null)) != null)
                return(cancelled);
            try {
                List<InetSocketAddress> addrs = new ArrayList<>();
                NamedSocketAddress ea = gameserv.get();
                if(ea != null) {
                    for(InetAddress addr : InetAddress.getAllByName(ea.host))
                        addrs.add(new InetSocketAddress(addr, ea.port));
                    if(addrs.isEmpty())
                        throw(new UnknownHostException(ea.host));
                } else {
                    if(hosts.isEmpty()) {
                        for(InetAddress addr : InetAddress.getAllByName(defserv.host))
                            addrs.add(new InetSocketAddress(addr, defserv.port));
                        if(addrs.isEmpty())
                            throw(new UnknownHostException(ea.host));
                    } else {
                        for(NamedSocketAddress addr : hosts) {
                            for(InetAddress host : InetAddress.getAllByName(addr.host))
                                addrs.add(new InetSocketAddress(host, addr.port));
                        }
                        if(addrs.isEmpty())
                            throw(new UnknownHostException(server.host));
                    }
                }
                preferhost(addrs, authaddr);
                connect: {
                    for(int i = 0; i < addrs.size(); i++) {
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        if(i > 0)
                            ui.uimsg(1, "prg", String.format("Connecting (address %d/%d)...", i + 1, addrs.size()));
                        if((cancelled = cancelIfRequested(null)) != null)
                            return(cancelled);
                        try {
                            sess = connectSession(addrs.get(i), acct, Connection.encrypt.get(), cookie);
                            sess.ui = ui;
                            if((cancelled = cancelIfRequested(sess)) != null)
                                return(cancelled);
                            break connect;
                        } catch(Connection.SessionConnError err) {
                        } catch(Connection.SessionError err) {
                            if((cancelled = cancelIfRequested(null)) != null)
                                return(cancelled);
                            ui.uimsg(1, "error", err.getMessage());
                            continue retry;
                        }
                    }
                    if((cancelled = cancelIfRequested(null)) != null)
                        return(cancelled);
                    ui.uimsg(1, "error", "Could not connect to server");
                    continue retry;
                }
            } catch(UnknownHostException e) {
                if((cancelled = cancelIfRequested(null)) != null)
                    return(cancelled);
                ui.uimsg(1, "error", "Could not locate server");
                continue retry;
            }
            if((cancelled = cancelIfRequested(sess)) != null)
                return(cancelled);
            setpref("loginname", loginname);
            rottokens(loginname, confname, false, false);
            break retry;
        } while(true);
        if((cancelled = cancelIfRequested(sess)) != null)
            return(cancelled);
        ui.destroy(1);
        if((cancelled = cancelIfRequested(sess)) != null)
            return(cancelled);
        haven.error.ErrorHandler.setprop("usr", sess.user.name);
        if((cancelled = cancelIfRequested(sess)) != null)
            return(cancelled);
        return(new PaistiSessionRunner(new RemoteUI(sess)));
    }

    @Override
    public void rcvmsg(int widget, String msg, Object... args) {
        synchronized(msgs) {
            if(cancelled)
                return;
            msgs.add(new LoginMessage(widget, msg, args));
            msgs.notifyAll();
        }
    }
}
