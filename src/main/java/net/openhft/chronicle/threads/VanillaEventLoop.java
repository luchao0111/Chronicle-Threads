/*
 *     Copyright (C) 2015  higherfrequencytrading.com
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU Lesser General Public License as published by
 *     the Free Software Foundation, either version 3 of the License.
 *
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU Lesser General Public License for more details.
 *
 *     You should have received a copy of the GNU Lesser General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.openhft.chronicle.threads;

import net.openhft.affinity.AffinityLock;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.annotation.HotMethod;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.core.threads.EventHandler;
import net.openhft.chronicle.core.threads.EventLoop;
import net.openhft.chronicle.core.threads.HandlerPriority;
import net.openhft.chronicle.core.threads.InvalidEventHandlerException;
import net.openhft.chronicle.core.util.Time;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static net.openhft.chronicle.core.io.Closeable.closeQuietly;

/**
 * Created by peter.lawrey on 22/01/15.
 */
public class VanillaEventLoop implements EventLoop, Runnable {
    private static final Logger LOG = LoggerFactory.getLogger(VanillaEventLoop.class);
    private final EventLoop parent;
    @NotNull
    private final ExecutorService service;
    private final List<EventHandler> highHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> mediumHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> timerHandlers = new CopyOnWriteArrayList<>();
    private final List<EventHandler> daemonHandlers = new CopyOnWriteArrayList<>();
    private final AtomicReference<EventHandler> newHandler = new AtomicReference<>();
    private final Queue<EventHandler> newHandlerQueue = new LinkedTransferQueue<>();
    private final Pauser pauser;
    private final long timerIntervalMS;
    private final String name;
    private final Consumer<Throwable> onThrowable;
    private final boolean binding;
    private long lastTimerNS;
    private volatile long loopStartMS;
    @NotNull
    private volatile AtomicBoolean running = new AtomicBoolean();
    @Nullable
    private volatile Thread thread = null;
    @Nullable
    private volatile Throwable closedHere = null;

    /**
     * @param parent          the parent event loop
     * @param name            the name of this event hander
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause
     * @param daemon          is a demon thread
     * @param binding
     */
    public VanillaEventLoop(EventLoop parent, String name, Pauser pauser, long timerIntervalMS, boolean daemon, boolean binding) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
        onThrowable = t -> LOG.error("", t);
    }

    /**
     * @param parent          the parent event loop
     * @param name            the name of this event hander
     * @param pauser          the pause strategy
     * @param timerIntervalMS how long to pause
     * @param daemon          is a demon thread
     * @param onThrowable     consumer is called when ever an error occurs
     * @param binding
     */
    public VanillaEventLoop(EventLoop parent,
                            String name,
                            Pauser pauser,
                            long timerIntervalMS,
                            boolean daemon,
                            Consumer<Throwable> onThrowable, boolean binding) {
        this.parent = parent;
        this.name = name;
        this.pauser = pauser;
        this.timerIntervalMS = timerIntervalMS;
        this.binding = binding;
        loopStartMS = Long.MAX_VALUE;
        service = Executors.newSingleThreadExecutor(new NamedThreadFactory(name, daemon));
        this.onThrowable = onThrowable;
    }

    public void start() {
        if (closedHere != null)
            throw new IllegalStateException("Event Group has been closed", closedHere);
        if (!running.getAndSet(true))
            service.submit(this);
    }

    @Override
    public void unpause() {
        pauser.unpause();
    }

    public void stop() {

    }

    @Override
    public boolean isClosed() {
        return closedHere != null;
    }

    public void addHandler(@NotNull EventHandler handler) {
        addHandler(false, handler);
    }

    public void addHandler(boolean dontAttemptToRunImmediatelyInCurrentThread, @NotNull EventHandler handler) {
        if (thread == null || thread == Thread.currentThread()) {
            addNewHandler(handler);

        } else {

            if (!running.get()) {
                if (!dontAttemptToRunImmediatelyInCurrentThread) {
                    try {
                        if (LOG.isDebugEnabled())
                            LOG.debug("Running " + handler + " in the current thread as " + this + " has finished");
                        handler.action();
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    } catch (InvalidEventHandlerException ignored) {
                    }
                }
                return;
            }
            pauser.unpause();
            if (!newHandler.compareAndSet(null, handler))
                newHandlerQueue.add(handler);

        }
    }

    public long loopStartMS() {
        return loopStartMS;
    }

    @Override
    @HotMethod
    public void run() {
        AffinityLock affinityLock = null;
        try {
            if (binding)
                affinityLock = AffinityLock.acquireLock();

            thread = Thread.currentThread();
            while (running.get()) {
                if (closedHere != null) {
                    closeAllHandlers();
                    runAllHighHandlers();
                    runAllMediumHandler();
                    runDaemonHandlers();
                    runTimerHandlers();
                    dumpRunningHandlers();
                    break;
                }
                boolean busy = false;
                if (highHandlers.isEmpty()) {
                    loopStartMS = Time.currentTimeMillis();
                    busy |= runAllMediumHandler();

                } else {
                    for (int i = 0; i < 10; i++) {
                        loopStartMS = Time.currentTimeMillis();
                        busy |= runAllHighHandlers();
                        busy |= runOneTenthLowHandler(i);
                    }
                }
                if (lastTimerNS + timerIntervalMS < loopStartMS) {
                    lastTimerNS = loopStartMS;
                    runTimerHandlers();
                }
                busy |= acceptNewHandlers();

                if (busy) {
                    pauser.reset();

                } else {
                    runDaemonHandlers();
                    // reset the loop timeout.
                    loopStartMS = Long.MAX_VALUE;
                    pauser.pause();
                }
            }
        } catch (Throwable e) {
            //         e.printStackTrace();
            onThrowable.accept(e);
        } finally {
            loopStartMS = Long.MAX_VALUE - 1;
            if (binding && affinityLock != null)
                affinityLock.release();
        }
    }

    @HotMethod
    private boolean runAllHighHandlers() {
        boolean busy = false;
        for (int i = 0; i < highHandlers.size(); i++) {
            EventHandler handler = highHandlers.get(i);
            try {
                boolean action = handler.action();
                busy |= action;
            } catch (InvalidEventHandlerException e) {
                try {
                    highHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }

                closeQuietly(handler);

            } catch (Exception e) {
                onThrowable.accept(e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runOneTenthLowHandler(int i) {
        boolean busy = false;
        for (int j = i; j < mediumHandlers.size(); j += 10) {
            EventHandler handler = mediumHandlers.get(j);
            try {
                boolean action = handler.action();
                if (action)
                    handler.action();

                busy |= action;
            } catch (InvalidEventHandlerException e) {
                try {
                    mediumHandlers.remove(j);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                onThrowable.accept(e);
            }
        }
        return busy;
    }

    @HotMethod
    private boolean runAllMediumHandler() {
        boolean busy = false;
        for (int j = 0; j < mediumHandlers.size(); j++) {
            EventHandler handler = mediumHandlers.get(j);
            try {
                busy |= handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    mediumHandlers.remove(j);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!mediumHandlers.isEmpty())
                        throw e2;
                }
                e.printStackTrace();
                closeQuietly(handler);

            } catch (Exception e) {
                onThrowable.accept(e);
            }
        }
        return busy;
    }

    @HotMethod
    private void runTimerHandlers() {
        for (int i = 0; i < timerHandlers.size(); i++) {
            EventHandler handler = timerHandlers.get(i);
            try {
                handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    timerHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!timerHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                onThrowable.accept(e);
            }
        }
    }

    @HotMethod
    private void runDaemonHandlers() {
        for (int i = 0; i < daemonHandlers.size(); i++) {
            EventHandler handler = daemonHandlers.get(i);
            try {
                handler.action();
            } catch (InvalidEventHandlerException e) {
                try {
                    daemonHandlers.remove(i--);
                } catch (ArrayIndexOutOfBoundsException e2) {
                    if (!daemonHandlers.isEmpty())
                        throw e2;
                }
                closeQuietly(handler);

            } catch (Exception e) {
                onThrowable.accept(e);
            }
        }
    }

    @HotMethod
    private boolean acceptNewHandlers() {
        boolean busy = false;
        EventHandler handler = newHandler.getAndSet(null);
        if (handler != null) {
            addNewHandler(handler);
            busy = true;
        }
        while ((handler = newHandlerQueue.poll()) != null) {
            addNewHandler(handler);
            busy = true;
        }
        return busy;
    }

    private void addNewHandler(@NotNull EventHandler handler) {
        HandlerPriority t1 = handler.priority();
        switch (t1 == null ? HandlerPriority.MEDIUM : t1) {
            case HIGH:
                if (!highHandlers.contains(handler))
                    highHandlers.add(handler);
                break;

            case REPLICATION:
            case MEDIUM:
                if (!mediumHandlers.contains(handler))
                    mediumHandlers.add(handler);
                break;

            case TIMER:
                if (!timerHandlers.contains(handler))
                    timerHandlers.add(handler);
                break;

            case DAEMON:
                if (!daemonHandlers.contains(handler))
                    daemonHandlers.add(handler);
                break;

            default:
                throw new IllegalArgumentException("Cannot add a " + handler.priority() + " task to a busy waiting thread");
        }
        handler.eventLoop(parent);
    }

    public String name() {
        return name;
    }

    public void dumpRunningState(@NotNull String message, @NotNull BooleanSupplier finalCheck) {
        Thread thread = this.thread;
        if (thread == null) return;
        StringBuilder out = new StringBuilder(message);
        Jvm.trimStackTrace(out, thread.getStackTrace());

        if (finalCheck.getAsBoolean() && LOG.isInfoEnabled())
            LOG.info(out.toString());
    }

    public int handlerCount() {
        return highHandlers.size() + mediumHandlers.size() + daemonHandlers.size() + timerHandlers.size();
    }

    @Override
    public void close() {
        try {
            closedHere = new Throwable("Closed here");

            closeAllHandlers();

            for (int i = 0; i < 100; i++) {
                pauser.unpause();
                Jvm.pause(10);
                if (handlerCount() == 0)
                    break;
                if (i % 10 == 9) {
                    StringBuilder sb = new StringBuilder();
                    sb.append("Shutting down thread is executing ").append(thread)
                            .append(", " + "handlerCount=").append(handlerCount()).append("\n");
                    Jvm.trimStackTrace(sb, thread.getStackTrace());
                    LOG.warn(sb.toString());
                }
            }
            dumpRunningHandlers();
            running.set(false);
            service.shutdown();
            pauser.unpause();
            if (thread != null)
                thread.interrupt();

            if (!(service.awaitTermination(1, TimeUnit.SECONDS))) {
                Thread thread = this.thread;
                if (thread != null) {
                    StackTraceElement[] stackTrace = thread.getStackTrace();
                    StringBuilder sb = new StringBuilder(thread + " still running ");
                    Jvm.trimStackTrace(sb, stackTrace);
                    LOG.info(sb.toString());
                }
                service.shutdownNow();
            }
        } catch (InterruptedException e) {
            service.shutdownNow();
        } finally {
            highHandlers.clear();
            mediumHandlers.clear();
            daemonHandlers.clear();
            timerHandlers.clear();
            newHandlerQueue.clear();
            newHandler.set(null);
        }
    }

    public void closeAllHandlers() {
        highHandlers.forEach(Closeable::closeQuietly);
        mediumHandlers.forEach(Closeable::closeQuietly);
        daemonHandlers.forEach(Closeable::closeQuietly);
        timerHandlers.forEach(Closeable::closeQuietly);
        Optional.ofNullable(newHandler.get()).ifPresent(Closeable::closeQuietly);

        for (Object o; (o = newHandlerQueue.poll()) != null; )
            Closeable.closeQuietly(o);
    }

    public void dumpRunningHandlers() {
        final int handlerCount = handlerCount();
        if (handlerCount > 0) {
            LOG.info("Handlers still running after being closed, handlerCount=" + handlerCount);
            Stream.of(highHandlers, mediumHandlers, daemonHandlers, timerHandlers)
                    .flatMap(List::stream)
                    .forEach(h -> LOG.info("\t" + h));
        }
    }

    @Override
    public boolean isAlive() {
        Thread thread = this.thread;
        return thread != null && thread.isAlive();
    }
}
