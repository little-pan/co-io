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

import static io.co.util.LogUtils.*;

/**
 * @author little-pan
 * @since 2019-05-14
 *
 */
public class NioReadTimer extends NioCoTimer {
    
    boolean timeout;
    
    public NioReadTimer(NioCoSocket source, int timeout){
        super(source, timeout);
    }
    
    @Override
    public void run() {
        debug("Running: %s", this);
        if(this.isCanceled()){
            return;
        }
        
        final NioScheduler scheduler = this.scheduler;
        final NioCoSocket source = (NioCoSocket)source();
        this.timeout = true;
        scheduler.resume(source);
    }
    
}
