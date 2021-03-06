package io.sentry.connection;

import io.sentry.environment.SentryEnvironment;
import io.sentry.event.Event;

import java.util.HashSet;
import java.util.Set;

/**
 * Abstract connection to a Sentry server.
 * <p>
 * Provide the basic tools to submit events to the server (authentication header, dsn).<br>
 * To avoid spamming the network if and when Sentry is down, automatically lock the connection each time a
 * {@link ConnectionException} is caught.
 */
public abstract class AbstractConnection implements Connection {
    /**
     * Current Sentry protocol version.
     */
    public static final String SENTRY_PROTOCOL_VERSION = "6";
    /**
     * Value of the X-Sentry-Auth header.
     */
    private final String authHeader;
    /**
     * Set of callbacks that will be called when an exception occurs while attempting to
     * send events to the Sentry server.
     */
    private Set<EventSendCallback> eventSendCallbacks;
    private LockdownManager lockdownManager;

    /**
     * Creates a connection based on the public and secret keys.
     *
     * @param publicKey public key (identifier) to the Sentry server.
     * @param secretKey secret key (password) to the Sentry server.
     */
    protected AbstractConnection(String publicKey, String secretKey) {
        this.lockdownManager = new LockdownManager();
        this.eventSendCallbacks = new HashSet<>();
        this.authHeader = "Sentry sentry_version=" + SENTRY_PROTOCOL_VERSION + ","
            + "sentry_client=" + SentryEnvironment.getSentryName() + ","
            + "sentry_key=" + publicKey + ","
            + "sentry_secret=" + secretKey;
    }

    /**
     * Creates an authentication header for the Sentry protocol.
     *
     * @return an authentication header as a String.
     */
    protected String getAuthHeader() {
        return authHeader;
    }

    @Override
    public final void send(Event event) throws ConnectionException {
        try {
            if (lockdownManager.isLockedDown()) {
                /*
                An exception is thrown to signal that this Event was not sent, which may be
                important in, for example, a BufferedConnection where the Event would be deleted
                from the Buffer if an exception isn't raised in the call to send.
                 */
                throw new LockedDownException("Dropping an Event due to lockdown: " + event);
            }

            doSend(event);

            for (EventSendCallback eventSendCallback : eventSendCallbacks) {
                try {
                    eventSendCallback.onSuccess(event);
                } catch (Exception ignored) {
                }
            }

            lockdownManager.resetState();
        } catch (ConnectionException e) {
            for (EventSendCallback eventSendCallback : eventSendCallbacks) {
                try {
                    eventSendCallback.onFailure(event, e);
                } catch (Exception ignored) {
                }
            }

            lockdownManager.setState(e);

            throw e;
        }
    }

    /**
     * Sends an event to the Sentry server.
     *
     * @param event captured event to add in Sentry.
     * @throws ConnectionException whenever a temporary exception due to the connection happened.
     */
    protected abstract void doSend(Event event) throws ConnectionException;

    /**
     * Add a callback that is called when an exception occurs while attempting to
     * send events to the Sentry server.
     *
     * @param eventSendCallback callback instance
     */
    public void addEventSendCallback(EventSendCallback eventSendCallback) {
        eventSendCallbacks.add(eventSendCallback);
    }

}
