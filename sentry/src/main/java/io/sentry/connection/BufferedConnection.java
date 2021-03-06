package io.sentry.connection;

import io.sentry.buffer.Buffer;
import io.sentry.environment.SentryEnvironment;
import io.sentry.event.Event;

import java.io.IOException;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;

/**
 * Connection wrapper that handles storing and deleting {@link Event}s from a {@link Buffer}.
 *
 * This exists as a {@link Connection} implementation because the existing API (and the Java 7
 * standard library) has no simple way of knowing whether an asynchronous request succeeded
 * or failed. The {@link #wrapConnectionWithBufferWriter} method is used to wrap an existing
 * Connection in a small anonymous Connection implementation that will always synchronously
 * write the sent Event to a Buffer and then pass it to the underlying Connection (often an
 * AsyncConnection in practice). Then, an instance of the {@link BufferedConnection} is used
 * to wrap the "real" Connection ("under" the AsyncConnection) so that it remove Events from
 * the Buffer if and only if the underlying {@link #send(Event)} call doesn't throw an exception.
 *
 * Note: In the future, if we are able to migrate to Java 8 at a minimum, we would probably make use
 * of CompletableFutures, though that would require changing the existing API regardless.
 */
public class BufferedConnection implements Connection {

    /**
     * Shutdown hook used to stop the buffered connection properly when the JVM quits.
     */
    private final BufferedConnection.ShutDownHook shutDownHook = new BufferedConnection.ShutDownHook();
    /**
     * Flusher ExecutorService, created in a verbose way to use daemon threads
     * so that it doesn't keep the JVM running after main() exits.
     */
    private final ScheduledExecutorService executorService =
        Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                return thread;
            }
        });
    /**
     * Connection used to actually send the events.
     */
    private Connection actualConnection;
    /**
     * Buffer used to store and retrieve events from.
     */
    private Buffer buffer;
    /**
     * Boolean that represents if graceful shutdown is enabled.
     */
    private boolean gracefulShutdown;
    /**
     * Timeout of the {@link #executorService}, in milliseconds.
     */
    private long shutdownTimeout;
    /**
     * Boolean used to check whether the connection is still open or not.
     */
    private volatile boolean closed = false;

    /**
     * Construct a BufferedConnection that will store events that failed to send to the provided
     * {@link Buffer} and attempt to flush them to the underlying connection later.
     *
     * @param actualConnection Connection to wrap.
     * @param buffer Buffer to be used when {@link Connection#send(Event)}s fail.
     * @param flushtime Time to wait between flush attempts, in milliseconds.
     * @param gracefulShutdown Indicates whether or not the shutdown operation should be managed by a ShutdownHook.
     * @param shutdownTimeout Timeout for graceful shutdown of the executor, in milliseconds.
     */
    public BufferedConnection(Connection actualConnection, Buffer buffer, long flushtime, boolean gracefulShutdown,
        long shutdownTimeout) {

        this.actualConnection = actualConnection;
        this.buffer = buffer;
        this.gracefulShutdown = gracefulShutdown;
        this.shutdownTimeout = shutdownTimeout;

        if (gracefulShutdown) {
            Runtime.getRuntime().addShutdownHook(shutDownHook);
        }

        Flusher flusher = new BufferedConnection.Flusher(flushtime);
        executorService.scheduleWithFixedDelay(flusher, flushtime, flushtime, TimeUnit.MILLISECONDS);
    }

    @Override
    public void send(Event event) {
        actualConnection.send(event);

        // success, remove the event from the buffer
        buffer.discard(event);
    }

    @Override
    public void addEventSendCallback(EventSendCallback eventSendCallback) {
        actualConnection.addEventSendCallback(eventSendCallback);
    }

    @Override
    @SuppressWarnings("checkstyle:magicnumber")
    public void close() throws IOException {
        if (gracefulShutdown) {
            shutDownHook.enabled = false;
        }

        closed = true;
        executorService.shutdown();
        try {
            if (shutdownTimeout == -1L) {
                // Block until the executor terminates, but log periodically.
                long waitBetweenLoggingMs = 5000L;
                while (true) {
                    if (executorService.awaitTermination(waitBetweenLoggingMs, TimeUnit.MILLISECONDS)) {
                        break;
                    }
                }
            } else if (!executorService.awaitTermination(shutdownTimeout, TimeUnit.MILLISECONDS)) {
                List<Runnable> tasks = executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            List<Runnable> tasks = executorService.shutdownNow();
        } finally {
            actualConnection.close();
        }
    }

    /**
     * Wrap a connection so that {@link Event}s are buffered before being passed on to
     * the underlying connection.
     *
     * This is important to ensure buffering happens synchronously with {@link Event} creation,
     * before they are passed on to the (optional) asynchronous connection so that Events will
     * be stored even if the application is about to exit due to a crash.
     *
     * @param connectionToWrap {@link Connection} to wrap with buffering logic.
     * @return Connection that will write {@link Event}s to a buffer before passing along to
     *         a wrapped {@link Connection}.
     */
    public Connection wrapConnectionWithBufferWriter(final Connection connectionToWrap) {
        return new Connection() {
            final Connection wrappedConnection = connectionToWrap;

            @Override
            public void send(Event event) throws ConnectionException {
                try {
                    // buffer before we attempt to send
                    buffer.add(event);
                } catch (Exception ignored) {
                }

                wrappedConnection.send(event);
            }

            @Override
            public void addEventSendCallback(EventSendCallback eventSendCallback) {
                wrappedConnection.addEventSendCallback(eventSendCallback);
            }

            @Override
            public void close() throws IOException {
                wrappedConnection.close();
            }
        };
    }

    /**
     * Flusher is scheduled to periodically run by the BufferedConnection. It retrieves
     * an Iterator of Events from the underlying {@link Buffer} and attempts to send
     * one at time, discarding from the buffer on success.
     *
     * Upon the first failure, Flusher will return and wait to be run again in the future,
     * under the assumption that the network is now/still down.
     */
    private class Flusher implements Runnable {
        private long minAgeMillis;

        Flusher(long minAgeMillis) {
            this.minAgeMillis = minAgeMillis;
        }

        @Override
        public void run() {
            SentryEnvironment.startManagingThread();
            try {
                Iterator<Event> events = buffer.getEvents();
                while (events.hasNext() && !closed) {
                    Event event = events.next();

                    /*
                     Skip events that have been in the buffer for less than minAgeMillis
                     milliseconds. We need to do this because events are added to the
                     buffer before they are passed on to the underlying "real" connection,
                     which means the Flusher might run and see them before we even attempt
                     to send them for the first time.
                     */
                    long now = System.currentTimeMillis();
                    long eventTime = event.getTimestamp().getTime();
                    long age = now - eventTime;
                    if (age < minAgeMillis) {
                        return;
                    }

                    try {
                        send(event);
                    } catch (Exception e) {
                        return;
                    }
                }
            } catch (Exception ignored) {

            } finally {
                SentryEnvironment.stopManagingThread();
            }
        }
    }

    private final class ShutDownHook extends Thread {

        /**
         * Whether or not this ShutDownHook instance will do anything when run.
         */
        private volatile boolean enabled = true;

        @Override
        public void run() {
            if (!enabled) {
                return;
            }

            SentryEnvironment.startManagingThread();
            try {
                // The current thread is managed by sentry
                BufferedConnection.this.close();
            } catch (Exception ignored) {
            } finally {
                SentryEnvironment.stopManagingThread();
            }
        }
    }
}
