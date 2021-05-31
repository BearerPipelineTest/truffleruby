/*
 * Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.thread;

import com.oracle.truffle.api.TruffleSafepoint;
import org.truffleruby.signal.LibRubySignal;

import java.util.Timer;
import java.util.TimerTask;

class NativeCallInterrupter implements TruffleSafepoint.Interrupter {

    private final Timer timer;
    private final long threadID;
    private volatile Task currentTask = null;

    NativeCallInterrupter(Timer timer, long threadID) {
        this.timer = timer;
        this.threadID = threadID;
    }

    @Override
    public void interrupt(Thread thread) {
        final Task task = new Task(threadID);
        currentTask = task;
        timer.schedule(task, 0, Task.PERIOD);
    }

    @Override
    public void resetInterrupted() {
        Task task = currentTask;
        if (task != null) {
            task.cancel();
        }
    }

    // Try every 100ms for 50 times maximum (5 seconds)
    static class Task extends TimerTask {
        private static final int PERIOD = 100; // milliseconds
        private static final int MAX_TIME = 5000; // milliseconds
        private static final int MAX_EXECUTIONS = MAX_TIME / PERIOD;

        private final long threadID;
        private int executed = 0;

        Task(long threadID) {
            this.threadID = threadID;
        }

        @Override
        public void run() {
            if (executed < MAX_EXECUTIONS) {
                executed++;
                LibRubySignal.sendSIGVTALRMToThread(threadID);
            } else {
                cancel();
            }
        }
    }

}
