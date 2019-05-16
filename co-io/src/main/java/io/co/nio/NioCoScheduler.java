/*
 * Copyright (c) 2019, little-pan, All rights reserved.
 * 
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3.0 of the License, or (at your option) any later version.
 * 
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 * 
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library.
 */
package io.co.nio;

import java.io.*;
import java.net.SocketAddress;
import java.nio.channels.*;
import java.text.*;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

import com.offbynull.coroutines.user.Continuation;
import com.offbynull.coroutines.user.Coroutine;
import com.offbynull.coroutines.user.CoroutineException;
import com.offbynull.coroutines.user.CoroutineRunner;

import io.co.CoChannel;
import io.co.CoIOException;
import io.co.CoScheduler;
import io.co.CoServerSocket;
import io.co.CoSocket;
import io.co.TimeRunner;
import io.co.util.IoUtils;

/**
 * The coroutine scheduler based on NIO.
 * 
 * @author little-pan
 * @since 2019-05-13
 *
 */
public class NioCoScheduler implements CoScheduler {
    
    private NioCoChannel<?>[] chans;
    private final int maxConnections;
    private int maxSlot;
    
    private TimeRunner[] timers;
    private int maxTimerSlot;
    
    protected volatile Selector selector;
    protected volatile boolean shutdown;
    protected volatile boolean stopped;
    
    protected final NioCoScheduler[] childs;
    private int nextChildSlot;
    final BlockingQueue<Runnable> syncQueue;
    
    public NioCoScheduler(){
        this(INIT_CONNECTIONS);
    }
    
    public NioCoScheduler(int initConnections){
        this(initConnections, MAX_CONNECTIONS, CHILD_COUNT);
    }
    
    public NioCoScheduler(int initConnections, int childCount){
        this(initConnections, MAX_CONNECTIONS, childCount);
    }
    
    public NioCoScheduler(int initConnections, int maxConnections, int childCount){
        if(initConnections < 0){
            throw new IllegalArgumentException("initConnections " + initConnections);
        }
        if(maxConnections  < 0){
            throw new IllegalArgumentException("maxConnections " + maxConnections);
        }
        if(initConnections > maxConnections){
            final String fmt = "initConnections %s bigger than maxConnections %s";
            throw new IllegalArgumentException(String.format(fmt, initConnections, maxConnections));
        }
        if(childCount < 0){
            throw new IllegalArgumentException("childCount " + childCount);
        }
        
        this.chans = new NioCoChannel<?>[initConnections];
        this.maxConnections = maxConnections;
        
        this.timers    = new TimeRunner[1024];
        this.syncQueue = new LinkedBlockingQueue<>();
        this.childs    = new NioCoScheduler[childCount];
    }
    
    @Override
    public void startAndServe() {
        initialize();
        
        final NioCoScheduler[] childs = this.childs;
        final int minConns = this.chans.length;
        final int maxConnx = this.maxConnections;
        for(int i = 0; i < childs.length; ++i){
            // Child threads
            final NioCoScheduler child = new NioCoScheduler(minConns, maxConnx, 0);
            childs[i] = child;
            (new Thread("nio-child-"+i) {
                @Override
                public void run(){
                    child.startAndServe();
                }
            }).start();
        }
        
        serve();
    }
    
    @Override
    public CoSocket accept(Continuation co, CoServerSocket coServerSocket) {
        debug("accept() ->");
        co.suspend();
        final CoSocket coSocket = (CoSocket)co.getContext();
        debug("accept() <-");
        return coSocket;
    }
    
    @Override
    public void bind(CoServerSocket coServerSocket, SocketAddress endpoint, int backlog)
        throws IOException {
        
        final NioCoServerSocket serverSocket = (NioCoServerSocket)coServerSocket;
        final ServerSocketChannel ssChan = serverSocket.channel();
        boolean failed = true;
        try {
            this.initialize();
            
            ssChan.bind(endpoint, backlog);
            ssChan.register(this.selector, SelectionKey.OP_ACCEPT, serverSocket);
            final CoroutineRunner coRunner = serverSocket.coRunner();
            coRunner.setContext(serverSocket);
            coRunner.execute();
            failed = false;
        } finally {
            if(failed){
                IoUtils.close(serverSocket);
            }
        }
    }
    
    @Override
    public void connect(CoSocket coSocket, SocketAddress remote) throws IOException {
        connect(coSocket, remote, 0);
    }
    
    @Override
    public void connect(CoSocket coSocket, SocketAddress remote, int timeout)
            throws IOException {
        final NioCoSocket socket = (NioCoSocket)coSocket;
        boolean failed = true;
        try {
            final SocketChannel chan = socket.channel();
            chan.register(this.selector, SelectionKey.OP_CONNECT, coSocket);
            chan.connect(remote);
            socket.startConnectionTimer(timeout);
            failed = false;
        } finally {
            if(failed){
                this.close(coSocket);
            }
        }
    }
    
    @Override
    public void schedule(TimeRunner timeRunner) {
        final TimeRunner oldTimer = this.timers[timeRunner.id];
        if(oldTimer != null && !oldTimer.isCanceled()){
            throw new IllegalStateException(String.format("Time runner slot %s used", timeRunner.id));
        }
        this.timers[timeRunner.id] = timeRunner;
    }
    
    @Override
    public void schedule(CoSocket coSocket, Runnable task, long delay) {
        schedule(coSocket, task, delay, 0L);
    }
    
    @Override
    public void schedule(CoSocket coSocket, Runnable task, long delay, long period) {
        final int slot = nextTimerSlot();
        final long runat = System.currentTimeMillis() + delay;
        final TimeRunner timer = new TimeRunner(slot, task, this, coSocket, runat, period);
        schedule(timer);
    }
    
    @Override
    public void close(CoChannel coChannel) {
        if(coChannel == null){
            return;
        }
        
        final NioCoChannel<?> chan = (NioCoChannel<?>)coChannel;
        final NioCoChannel<?> scChan = slotCoChannel(chan);
        if(scChan != null && scChan == chan) {
            final int slot = chan.id();
            recycleChanSlot(slot);
            cancelTimers(chan);
        }
    }
    
    @Override
    public void shutdown() {
        final Selector sel = this.selector;
        if(sel != null){
            sel.wakeup();
        }
        this.shutdown = true;
        
        for(final NioCoScheduler child: this.childs){
            if(child == null){
                continue;
            }
            child.shutdown();
        }
    }
    
    @Override
    public boolean isShutdown() {
        return this.shutdown;
    }
    
    @Override
    public boolean isStopped(){
        return this.stopped;
    }
    
    NioCoScheduler register(final NioCoChannel<?> coChannel){
        final int slot = coChannel.id();
        final NioCoChannel<?> oldChan = this.chans[slot];
        if(oldChan != null && oldChan.isOpen()){
            throw new IllegalStateException(String.format("Channel slot %s used", slot));
        }
        
        this.chans[slot] = coChannel;
        return this;
    }
    
    <S extends Channel> NioCoChannel<?> slotCoChannel(final NioCoChannel<S> coChannel){
        final NioCoChannel<?> coChan = this.chans[coChannel.id()];
        if(coChan == null || coChan != coChannel){
            return null;
        }
        return coChan;
    }
    
    int nextSlot() throws CoIOException {
        final NioCoChannel<?>[] chans = this.chans;
        final int n = chans.length;
        
        // quick allocate
        if(this.maxSlot < n){
            return this.maxSlot++;
        }
        
        // traverse
        for(int i = 0; i < n; ++i){
            final NioCoChannel<?> coChan = chans[i];
            if(coChan == null || !coChan.isOpen()){
                return i;
            }
        }
        
        // check limit
        if(this.maxSlot >= this.maxConnections){
            // Too many connections
            throw new CoIOException("Too many connections: " + this.maxSlot);
        }
        
        // expand
        final int size = Math.min(this.maxConnections, n << 1);
        final NioCoChannel<?>[] newChans = new NioCoChannel<?>[size];
        System.arraycopy(chans, 0, newChans, 0, n);
        this.chans = newChans;
        return this.maxSlot++;
    }
    
    private void cancelTimers(final NioCoChannel<?> coChannel) {
        final TimeRunner[] timers = this.timers;
        final int n = Math.min(this.maxTimerSlot, timers.length);
        for(int i = 0; i < n; ++i){
            final TimeRunner timer = timers[i];
            if(timer != null && timer.source() == coChannel){
                timer.cancel();
                if(i == n - 1){
                    this.recycleTimerSlot(i);
                }
            }
        }
    }
    
    private void recycleChanSlot(final int slot){
        this.chans[slot] = null;
        if(slot == this.maxSlot - 1){
            this.maxSlot--;
            for(int i = slot - 1; i >= 0; --i){
                final NioCoChannel<?> c = this.chans[i];
                if(c != null && c.isOpen()){
                    break;
                }
                this.chans[i] = null;
                this.maxSlot--;
            }
        }
    }
    
    protected void initialize() throws CoIOException {
        try {
            if(this.selector == null){
                this.selector = Selector.open();
            }
        } catch (final IOException cause){
            throw new CoIOException(cause);
        }
    }
    
    protected void serve(){
        for(;;){
            final Selector selector = this.selector;
            try {
                // Shutdown handler
                if(this.shutdown){
                    final NioCoChannel<?>[] chans = this.chans;
                    int actives = 0;
                    for(int i = 0, n = chans.length; i < n; ++i){
                        final NioCoChannel<?> runChan = chans[i];
                        if(runChan == null){
                            continue;
                        }
                        final Channel chan = runChan.channel();
                        if(chan instanceof ServerSocketChannel){
                            this.close(runChan);
                            continue;
                        }
                        ++actives;
                    }
                    if(actives == 0){
                        this.stop();
                        // Exit normally
                        break;
                    }
                }
                
                // Execute runner
                executeSyncRunners();
                
                // Do selection
                final long minRunat = minRunat();
                debug("minRunat = %s", minRunat);
                if(selector.select(minRunat) == 0){
                    continue;
                }
                
                final Set<SelectionKey> selKeys = selector.selectedKeys();
                for(Iterator<SelectionKey> i = selKeys.iterator(); i.hasNext(); i.remove()){
                    NioCoChannel<?> coChan = null;
                    boolean failed = false;
                    try {
                        final SelectionKey key = i.next();
                        if(!key.isValid()){
                            failed = true;
                            continue;
                        }
                        coChan = (NioCoChannel<?>)key.attachment();
                        if(key.isAcceptable()){
                            doAccept(key);
                            continue;
                        }
                        if(key.isConnectable()){
                            doConnect(key);
                            continue;
                        }
                        if(key.isReadable()){
                            doRead(key);
                        }
                        if(key.isValid() && key.isWritable()){
                            doWrite(key);
                        }
                    } catch (final CoroutineException e){
                        failed = true;
                        debug("Uncaught exception in coroutine", e.getCause());
                    } finally {
                        if(failed){
                            IoUtils.close(coChan);
                        }
                    }
                }
            } catch (final Throwable uncaught){
                this.stop();
                throw new CoIOException("Scheduler fatal", uncaught);
            }
        }
    }
    
    private void executeSyncRunners() {
        final BlockingQueue<Runnable> queue = this.syncQueue;
        for(;;){
            final Runnable runner = queue.poll();
            if(runner == null){
                break;
            }
            
            try {
                runner.run();
            } catch (final CoroutineException e){
                final Throwable cause = e.getCause();
                debug("Uncaught exception in coroutine", cause);
            }
        }
    }

    protected static void debug(final String message, final Throwable cause){
        if(DEBUG){
            if(cause != null){
                debug(System.err, message);
                cause.printStackTrace(System.err);
            }else{
                debug(System.err, message);
            }
        }
    }
    
    protected static void debug(final String format, Object ...args){
        debug(System.out, format, args);
    }
    
    protected static void debug(final PrintStream out, String format, Object ...args){
        if(DEBUG){
            final DateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            String message = format;
            if(args.length > 0){
                message = String.format(format, args);
            }
            final String thread = Thread.currentThread().getName();
            out.println(String.format("%s[%s] %s", df.format(new Date()), thread, message));
        }
    }
    
    protected void stop(){
        IoUtils.close(this.selector);
        this.shutdown();
        this.chans   = null;
        this.timers  = null;
        this.stopped = true;
    }
    
    protected void execute(CoroutineRunner coRunner, NioCoChannel<?> coChannel) {
        try {
            if(coRunner.execute() == false){
                IoUtils.close(coChannel);
            }
            return;
        } catch (final CoroutineException coe) {
            debug("Coroutine executes error", coe);
            IoUtils.close(coChannel);
        }
    }
    
    protected void doWrite(final SelectionKey key) {
        final NioCoChannel<?> socket = (NioCoChannel<?>)key.attachment();
        final CoroutineRunner coRunner = socket.coRunner();
        execute(coRunner, socket);
    }
    
    protected void doRead(final SelectionKey key) {
        final NioCoChannel<?> socket = (NioCoChannel<?>)key.attachment();
        final CoroutineRunner coRunner = socket.coRunner();
        execute(coRunner, socket);
    }
    
    protected void doConnect(final SelectionKey key) {
        final SocketChannel chan = (SocketChannel)key.channel();
        final NioCoSocket socket = (NioCoSocket)key.attachment();
        boolean failed = true;
        try {
            final NioCoChannel<?> coChan = slotCoChannel(socket);
            if(coChan == null){
                // Had closed when timeout etc
                return;
            }
            socket.cancelConnetionTimer();
            final CoroutineRunner coRunner = coChan.coRunner();
            try {
                chan.finishConnect();
                failed = false;
            } catch (final IOException cause){
                coRunner.setContext(cause);
                execute(coRunner, socket);
                return;
            }
            coRunner.setContext(socket);
            execute(coRunner, socket);
        } finally {
            if(failed){
                IoUtils.close(socket);
            }
        }
    }
    
    protected void doAccept(final SelectionKey key) throws IOException {
        NioCoSocket coSocket = null;
        SocketChannel chan = null;
        boolean failed = true;
        try {
            final ServerSocketChannel ssChan = (ServerSocketChannel)key.channel();
            final NioCoServerSocket cosSocket = (NioCoServerSocket)key.attachment();
            try {
                chan = ssChan.accept();
                chan.configureBlocking(false);
                chan.socket().setKeepAlive(true);
                chan.socket().setTcpNoDelay(true);
                
                // 1. Create coSocket
                final Coroutine coConnector = cosSocket.getCoConnector();
                if(this.childs.length != 0){
                    // 3-2. Post coSocket
                    postSocket(chan, coConnector);
                    failed = false;
                    return;
                }
                coSocket = new PassiveNioCoSocket(coConnector, chan, this);
            } catch (final CoIOException cause){
                debug("Create accepted socket error", cause);
                return;
            } finally {
                // 2. Next accept
                final CoroutineRunner coRunner = cosSocket.coRunner();
                coRunner.setContext(coSocket);
                coRunner.execute();
            }
            
            // 3-1. Start coSocket
            final CoroutineRunner coRunner = coSocket.coRunner();
            coRunner.setContext(coSocket);
            execute(coRunner, coSocket);
            
            failed = false;
        } finally {
            if(failed){
                IoUtils.close(chan);
                this.close(coSocket);
            }
        }
    }
    
    private void postSocket(SocketChannel channel, Coroutine coConnector) {
        final NioCoScheduler[] childs = this.childs;
        final int childCount = childs.length;
        NioCoScheduler child = null;
        for(int i = 0; i < childCount; ++i){
            if(++this.nextChildSlot >= childCount){
                this.nextChildSlot = 0;
            }
            final NioCoScheduler c = childs[this.nextChildSlot];
            if(!c.isShutdown()){
                child = c;
                break;
            }
        }
        if(child == null){
            IoUtils.close(channel);
            throw new IllegalStateException("No valid child scheduler available");
        }
        
        PostSocketRunner runner = new PostSocketRunner(child, channel, coConnector);
        final boolean ok = child.syncQueue.offer(runner);
        if(ok){
            child.selector.wakeup();
            debug("postSocket()");
        }
    }

    int nextTimerSlot() {
        final TimeRunner[] timers = this.timers;
        final int n = timers.length;
        
        if(this.maxTimerSlot < n){
            return this.maxTimerSlot++;
        }
        for(int i = 0; i < n; ++i){
            final TimeRunner timer = timers[i];
            if(timer == null || timer.isCanceled()){
                return i;
            }
        }
        
        // expand
        final TimeRunner[] newTimers = new TimeRunner[n << 1];
        System.arraycopy(timers, 0, newTimers, 0, n);
        this.timers = newTimers;
        
        return this.maxTimerSlot++;
    }
    
    private long minRunat() {
        final TimeRunner[] timers = this.timers;
        final int n = Math.min(this.maxTimerSlot, timers.length);
        final long cur = System.currentTimeMillis();
        long min = Long.MAX_VALUE;
        
        for(int i = 0; i < n; ++i){
            final TimeRunner timer = timers[i];
            if(timer == null || timer.isCanceled()){
                recycleTimerSlot(i);
                continue;
            }
            final long runat = timer.runat();
            if(runat <= cur){
                try {
                    timer.run();
                    if(!timer.next()){
                        recycleTimerSlot(i);
                    }
                } catch (final Exception e){
                    timer.cancel();
                    debug("Timer runs error", e);
                }
                continue;
            }
            if(runat < min){
                min = runat;
            }
        }
        if(min == Long.MAX_VALUE){
            return 0L;
        }
        
        return Math.min(1000L, min - cur);
    }
    
    private void recycleTimerSlot(final int slot){
        this.timers[slot] = null;
        if(slot == this.maxTimerSlot - 1){
            this.maxTimerSlot--;
            for(int i = slot - 1; i >= 0; --i){
                final TimeRunner r = this.timers[i];
                if(r != null && !r.isCanceled()){
                    break;
                }
                this.timers[i] = null;
                this.maxTimerSlot--;
            }
        }
    }
    
    static class PostSocketRunner implements Runnable {
        
        final NioCoScheduler scheduler;
        final SocketChannel channel;
        final Coroutine connector;

        PostSocketRunner(NioCoScheduler scheduler, SocketChannel channel, Coroutine connector){
            this.scheduler = scheduler;
            this.channel   = channel;
            this.connector = connector;
        }
        
        @Override
        public void run() {
            final NioCoScheduler scheduler = this.scheduler;
            NioCoSocket coSocket = null;
            boolean failed = true;
            try {
                coSocket = new PassiveNioCoSocket(this.connector, this.channel, scheduler);
                final CoroutineRunner coRunner = coSocket.coRunner();
                coRunner.setContext(coSocket);
                scheduler.execute(coRunner, coSocket);
                failed = false;
            } finally {
                if(failed){
                    IoUtils.close(this.channel);
                    IoUtils.close(coSocket);
                }
            }
        }
        
    }

}
