package paisti.client;

import haven.Connection;
import haven.Gob;
import haven.Session;
import haven.Transport;

import java.net.SocketAddress;

/**
 * Centralizes production session creation with {@link PGob} as the gob factory,
 * keeping {@code haven.Session} free of paisti imports.
 */
public class PaistiSessions {
    private static final Gob.Factory GOB_FACTORY = PGob::new;

    public static Session connect(SocketAddress server, Session.User user, boolean encrypt, byte[] cookie, Object... args) throws InterruptedException {
	return Session.connect(server, user, encrypt, cookie, GOB_FACTORY, args);
    }

    public static Session create(Transport conn, Session.User user) {
	return new Session(conn, user, GOB_FACTORY);
    }
}
