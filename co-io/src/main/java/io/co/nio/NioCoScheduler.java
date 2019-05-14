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
    
    private CoRunnerChannel[] chans;
    private final int maxConnections;
    private int maxSlot;
    
    private TimeRunner[] timers;
    private int maxTimerSlot;
    
    protected volatile Selector selector;
    protected volatile boolean shutdown;
    protected volatile boolean stopped;
    
    public NioCoScheduler(){
        this(INIT_CONNECTIONS, MAX_CONNECTIONS);
    }
    
    public NioCoScheduler(int initConnections){
        this(initConnections, MAX_CONNECTIONS);
    }
    
    public NioCoScheduler(int initConnections, int maxConnections){
        if(initConnections < 0){
            throw new IllegalArgumentException("initConnections " + initConnections);
        }
        if(maxConnections  < 2/* include server socket*/){
            throw new IllegalArgumentException("maxConnections must bigger than 1: given " + maxConnections);
        }
        if(initConnections > maxConnections){
            final String fmt = "initConnections %s bigger than maxConnections %s";
            throw new IllegalArgumentException(String.format(fmt, initConnections, maxConnections));
        }
        
        this.chans = new CoRunnerChannel[initConnections];
        this.maxConnections = maxConnections;
        
        this.timers = new TimeRunner[1024];
    }
    
    @Override
    public void start() {
        initialize();
        schedule();
    }
    
    @Override
    public CoSocket accept(Continuation co, CoServerSocket coServerSocket)
        throws CoIOException {
        try {
            this.initialize();
            
            final NioCoServerSocket sSocket = (NioCoServerSocket)coServerSocket;
            final CoRunnerChannel corChan= this.chans[sSocket.id()];
            final SelectableChannel chan = (SelectableChannel)corChan.coChannel.channel();
            chan.register(this.selector, SelectionKey.OP_ACCEPT, coServerSocket);
        } catch (final IOException cause){
            throw new CoIOException(cause);
        }
        co.suspend();
        return (CoSocket)co.getContext();
    }
    
    @Override
    public void bind(CoServerSocket coServerSocket, SocketAddress endpoint, int backlog)
        throws IOException {
        
        final NioCoServerSocket serverSocket = (NioCoServerSocket)coServerSocket;
        final ServerSocketChannel ssChan = serverSocket.channel();
        boolean failed = true;
        try {
            ssChan.bind(endpoint, backlog);
            final CoRunnerChannel corChan = runnerChannel(serverSocket);
            corChan.coRunner.setContext(serverSocket);
            corChan.coRunner.execute();
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
            if(timeout > 0){
                final long runat = System.currentTimeMillis() + timeout;
                this.schedule(new NioConnectionTimer(this, socket, runat));
            }
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
        final CoRunnerChannel corChan = runnerChannel(chan);
        if(corChan != null && corChan.coChannel == chan) {
            final int slot = chan.id();
            recycleChanSlot(slot);
            cancelTimers(chan);
        }
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

    @Override
    public void shutdown() {
        final Selector sel = this.selector;
        if(sel != null){
            sel.wakeup();
        }
        this.shutdown = true;
    }
    
    @Override
    public boolean isShutdown() {
        return this.shutdown;
    }
    
    @Override
    public boolean isStopped(){
        return this.stopped;
    }
    
    CoRunnerChannel register(final NioCoChannel<?> coChannel, final Coroutine co){
        final int slot = coChannel.id();
        final CoRunnerChannel oldChan = this.chans[slot];
        if(oldChan != null && oldChan.coChannel.isOpen()){
            throw new IllegalStateException(String.format("Channel slot %s used", slot));
        }
        final CoRunnerChannel newChan = new CoRunnerChannel(co, coChannel);
        this.chans[slot] = newChan;
        return newChan;
    }
    
    <S extends Channel> CoRunnerChannel runnerChannel(final NioCoChannel<S> coChannel){
        final CoRunnerChannel corChan = this.chans[coChannel.id()];
        if(corChan == null || corChan.coChannel != coChannel){
            return null;
        }
        return corChan;
    }
    
    int nextSlot() throws CoIOException {
        final CoRunnerChannel[] chans = this.chans;
        final int n = chans.length;
        
        // quick allocate
        if(this.maxSlot < n){
            return this.maxSlot++;
        }
        
        // traverse
        for(int i = 0; i < n; ++i){
            final CoRunnerChannel corChan = chans[i];
            if(corChan == null || !corChan.coChannel.isOpen()){
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
        final CoRunnerChannel[] newChans = new CoRunnerChannel[size];
        System.arraycopy(chans, 0, newChans, 0, n);
        this.chans = newChans;
        return this.maxSlot++;
    }
    
    private void recycleChanSlot(final int slot){
        if(slot == this.maxSlot - 1){
            for(int i = slot; i >= 0; --i){
                final CoRunnerChannel c = this.chans[i];
                if(c != null && c.coChannel.isOpen()){
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
    
    protected void schedule(){
        for(;;){
            final Selector selector = this.selector;
            try {
                // Shutdown handler
                if(this.shutdown){
                    final CoRunnerChannel[] chans = this.chans;
                    int actives = 0;
                    for(int i = 0, n = chans.length; i < n; ++i){
                        final CoRunnerChannel runChan = chans[i];
                        if(runChan == null){
                            continue;
                        }
                        final Channel chan = runChan.coChannel.channel();
                        if(chan instanceof ServerSocketChannel){
                            this.close(runChan.coChannel);
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
                
                // Do selection
                try {
                    final long minRunat = minRunat();
                    if(selector.select(minRunat) == 0){
                        continue;
                    }
                } catch(final ClosedSelectorException cause){
                    debug("Selector closed", cause);
                    this.stop();
                    break;
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
            } catch (final IOException cause){
                this.stop();
                throw new CoIOException("Scheduler fatal", cause);
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
        this.chans   = null;
        this.timers  = null;
        this.stopped = true;
    }
    
    protected void execute(CoroutineRunner coRunner, NioCoChannel<?> coChannel) {
        if(coRunner.execute() == false){
            this.close(coChannel);
        }
    }
    
    protected void doWrite(final SelectionKey key) {
        final NioCoChannel<?> socket = (NioCoChannel<?>)key.attachment();
        final CoRunnerChannel corChan = runnerChannel(socket);
        final CoroutineRunner coRunner = corChan.coRunner;
        execute(coRunner, socket);
    }
    
    protected void doRead(final SelectionKey key) {
        final NioCoChannel<?> socket = (NioCoChannel<?>)key.attachment();
        final CoRunnerChannel corChan = runnerChannel(socket);
        final CoroutineRunner coRunner = corChan.coRunner;
        execute(coRunner, socket);
    }
    
    protected void doConnect(final SelectionKey key) {
        final SocketChannel chan = (SocketChannel)key.channel();
        final NioCoChannel<?> socket = (NioCoChannel<?>)key.attachment();
        boolean failed = true;
        try {
            final CoRunnerChannel corChan = runnerChannel(socket);
            if(corChan == null){
                // Had closed when timeout etc
                return;
            }
            final CoroutineRunner coRunner = corChan.coRunner;
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
            final NioCoChannel<?> sSocket = (NioCoChannel<?>)key.attachment();
            final CoRunnerChannel ssRunChan = runnerChannel(sSocket);
            final CoServerSocket cosSocket  = (CoServerSocket)ssRunChan.coChannel;
            try {
                chan = ssChan.accept();
                chan.configureBlocking(false);
                
                // 1. Create coSocket
                final Coroutine coConnector = cosSocket.getCoConnector();
                coSocket = new PassiveNioCoSocket(coConnector, chan, this);
            } catch (final CoIOException cause){
                debug("Create accepted socket error", cause);
                return;
            } finally {
                // 2. Next accept
                ssRunChan.coRunner.setContext(coSocket);
                ssRunChan.coRunner.execute();
            }
            
            // 3. Start coSocket
            final CoRunnerChannel sRunChan = runnerChannel(coSocket);
            sRunChan.coRunner.setContext(coSocket);
            execute(sRunChan.coRunner, coSocket);
            
            failed = false;
        } finally {
            if(failed){
                IoUtils.close(chan);
                this.close(coSocket);
            }
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
                } catch(final Exception e){
                    timer.cancel();
                    debug("Timer run error", e);
                }
                if(!timer.next()){
                    recycleTimerSlot(i);
                }
                continue;
            }
            if(runat < min){
                min = runat;
            }
        }
        return Math.min(1000L, min - cur);
    }
    
    private void recycleTimerSlot(final int slot){
        if(slot == this.maxTimerSlot - 1){
            for(int i = slot; i >= 0; --i){
                final TimeRunner r = this.timers[i];
                if(r != null && !r.isCanceled()){
                    break;
                }
                this.timers[i] = null;
                this.maxTimerSlot--;
            }
        }
    }

}
