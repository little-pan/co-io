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

import com.offbynull.coroutines.user.Continuation;

import io.co.*;
import io.co.util.IoUtils;
import static io.co.util.LogUtils.*;
import junit.framework.TestCase;

import java.io.IOException;

/**
 * @author little-pan
 * @since 2019-05-26
 *
 */
public class AcceptTest extends TestCase {

    public static void main(String[] args) throws Exception {
        new AcceptTest().testAccept();
    }

    public void testAccept() throws Exception {
        System.setProperty("io.co.debug", "true");
        int port = 9960;
        NioCoServerSocket server = new NioCoServerSocket(port, ServerHandler.class);
        NioScheduler scheduler = server.getScheduler();

        CoSocket socket = new NioCoSocket(port, new ClientHandler(), scheduler);
        info("wait");
        server.awaitClosed();
        socket.close();

        info("OK");
    }

    static class ClientHandler extends Connector {

        private static final long serialVersionUID = 1L;

        @Override
        public void handleConnection(Continuation co, CoSocket socket) throws Exception {
            Scheduler scheduler = socket.getScheduler();
            try {
                info("%s connected", socket);
                CoOutputStream out = socket.getOutputStream();
                out.write(co, 1);
                out.flush(co);
                int i = socket.getInputStream().read(co);
                if (i != 1) throw new IOException("Echo error");
            } finally {
                IoUtils.close(socket);
                scheduler.shutdown();
            }
        }

    }

    static class ServerHandler extends Connector {

        private static final long serialVersionUID = 1L;

        @Override
        public void handleConnection(Continuation co, CoSocket socket) throws Exception {
            try {
                info("%s accepted", socket);
                int i = socket.getInputStream().read(co);
                if (i != 1) throw new IOException("Request error");
                CoOutputStream out = socket.getOutputStream();
                out.write(co, i);
                out.flush(co);
            } finally {
                IoUtils.close(socket);
            }
        }

    }

}
