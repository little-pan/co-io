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

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;

import com.offbynull.coroutines.user.Continuation;

import io.co.CoIOException;
import io.co.CoInputStream;
import io.co.util.IoUtils;

/**
 * The NIO implementation of CoInputStream.
 * 
 * @author little-pan
 * @since 2019-05-13
 *
 */
public class NioCoInputStream extends CoInputStream {
    final NioCoSocket coSocket;
    final SocketChannel channel;
    final Selector selector;
    protected ByteBuffer buffer;
    
    public NioCoInputStream(NioCoSocket coSocket, SocketChannel channel, Selector selector){
        this(coSocket, channel, selector, BUFFER_SIZE);
    }
    
    public NioCoInputStream(NioCoSocket coSocket, SocketChannel channel, Selector selector, int bufferSize){
        this.coSocket= coSocket;
        this.channel = channel;
        this.selector= selector;
        this.buffer  = ByteBuffer.allocate(bufferSize);
    }
    
    @Override
    public int read(Continuation co) throws CoIOException {
        final ByteBuffer buf = this.buffer;
        if(buf.hasRemaining()){
            return buf.get();
        }

        final SocketChannel chan = this.channel;
        final SelectionKey selKey = IoUtils.enableRead(chan, this.selector, this.coSocket);
        try {
            buf.clear();
            for(;;){
                final int n = chan.read(buf);
                switch(n){
                case -1:
                    return -1;
                case 0:
                    co.suspend();
                    continue;
                default:
                    buf.flip();
                    return buf.get();
                }
            }
        } catch (final IOException cause){
            throw new CoIOException(cause);
        } finally {
            IoUtils.disableRead(selKey, this.selector, this.coSocket);
        }
    }
    
    @Override
    public void close() {
        try {
            this.channel.shutdownInput();
        } catch (final IOException e) {
            // ignore
        }
        this.buffer = null;
    }
    
}