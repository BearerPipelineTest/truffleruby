/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryPoolMXBean;
import java.lang.management.MemoryUsage;
import java.time.Duration;

import com.oracle.truffle.api.dsl.Cached;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.SuppressFBWarnings;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.collections.WeakValueCache;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.language.SafepointManager;
import org.truffleruby.language.control.RaiseException;

@CoreModule("GC")
public abstract class GCNodes {

    @Primitive(name = "gc_start")
    public static abstract class GCStartPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object vmGCStart() {
            getContext().getMarkingService().queueMarking();
            System.gc();
            return nil;
        }

    }

    /** Attempts to run the garbage collector. This cannot be guaranteed in general, but calling this method should be
     * much more likely to actually trigger GC than calling {@link System#gc()} or Ruby's {@link GCStartPrimitiveNode}.
     *
     * <p>
     * In particular, this attempts to trigger the GC by waiting until a weak reference has been cleared.
     *
     * <p>
     * Note that even when GC is triggered, there is not guarantee that the all the garbage has been cleared or all the
     * memory reclaimed. */
    @Primitive(name = "gc_force")
    public static abstract class GCForce extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected Object force() {
            getContext().getMarkingService().queueMarking();

            // NOTE(norswap, 16 Apr 20): We could have used a WeakReference here, but the hope is that the extra
            // indirection will prevent the compiler to optimize this method away (assuming JIT compilation, and
            // the fact that such an optimizaton is permitted and possible, both of which are unlikely).
            WeakValueCache<Object, Object> cache = new WeakValueCache<>();
            Object key = new Object();

            // NOTE(norswap, 02 Feb 21): The split into two methods here is another way to try to outwit optimizations
            // by making sure the method that allocates the weak value is exited before we run the GC loop;
            initCache(cache, key);
            gcLoop(cache, key);
            return nil;
        }

        @SuppressFBWarnings("DLS")
        private void initCache(WeakValueCache<Object, Object> cache, Object key) {
            Object value = new Object();
            cache.put(key, value);
            value = null;
        }

        private void gcLoop(WeakValueCache<Object, Object> cache, Object key) {
            final long duration = Duration.ofSeconds(60).toNanos();
            final long start = System.nanoTime();
            do {
                if (System.nanoTime() - start > duration) {
                    throw new RaiseException(
                            getContext(),
                            getContext().getCoreExceptions().runtimeError(
                                    "gc_force exceeded its 60 seconds timeout",
                                    this));
                }
                System.gc();
                SafepointManager.poll(getLanguage(), this);
            } while (cache.get(key) != null);
        }
    }


    @CoreMethod(names = "count", onSingleton = true)
    public abstract static class CountNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected int count() {
            int count = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                count += bean.getCollectionCount();
            }
            return count;
        }

    }

    @CoreMethod(names = "time", onSingleton = true)
    public abstract static class TimeNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected long time() {
            long time = 0;
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                time += bean.getCollectionTime();
            }
            return time;
        }

    }

    @Primitive(name = "gc_stat")
    public static abstract class GCStatPrimitiveNode extends PrimitiveArrayArgumentsNode {

        @TruffleBoundary
        @Specialization
        protected RubyArray stat(
                @Cached StringNodes.MakeStringNode makeStringNode) {
            long time = 0;
            int count = 0;
            int minorCount = 0;
            int majorCount = 0;
            int unknownCount = 0;
            String[] memoryPoolNames = new String[0];
            Object[] memoryPools;

            // Get GC time and counts from GarbageCollectorMXBean
            for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
                // Get MemoryPoolName relevant to GC
                if (bean.getMemoryPoolNames().length > memoryPoolNames.length) {
                    // Since old generation memory pools are a superset of young generation memory pools,
                    // it suffices to check that we have the longer list of memory pools
                    memoryPoolNames = bean.getMemoryPoolNames();
                }
                time += bean.getCollectionTime();
                count += bean.getCollectionCount();
                switch (bean.getName()) {
                    case "G1 Young Generation": // during 'jvm' and 'jvm-ce'
                    case "PS Scavenge": // during 'jvm --vm.XX:+UseParallelGC'
                    case "young generation scavenger": // during 'native'
                        minorCount += bean.getCollectionCount();
                        break;
                    case "G1 Old Generation": // during 'jvm' and 'jvm-ce'
                    case "PS MarkSweep": // during 'jvm --vm.XX:+UseParallelGC'
                    case "complete scavenger": // during 'native'
                        majorCount += bean.getCollectionCount();
                        break;
                    default:
                        unknownCount += bean.getCollectionCount();
                        break;
                }
            }

            // Get memory usage values from relevant memory pools (2-3 / ~8 are relevant)
            memoryPools = new Object[memoryPoolNames.length];
            for (int i = 0; i < memoryPoolNames.length; i++) {
                String memoryPoolName = memoryPoolNames[i];
                for (MemoryPoolMXBean bean : ManagementFactory.getMemoryPoolMXBeans()) {
                    if (bean.getName().equals(memoryPoolName)) {
                        memoryPools[i] = beanToArray(bean);
                    }
                }
            }

            MemoryUsage usage = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();

            // Use an object array instead because otherwise ArrayHelpers.java line 51 complains
            Object[] memoryPoolNamesCast = new Object[memoryPoolNames.length];
            for (int i = 0; i < memoryPoolNames.length; i++) {
                memoryPoolNamesCast[i] = makeStringNode
                        .executeMake(memoryPoolNames[i], UTF8Encoding.INSTANCE, CodeRange.CR_UNKNOWN);
            }


            return createArray(
                    new Object[]{
                            time,
                            count,
                            minorCount,
                            majorCount,
                            unknownCount,
                            usage.getCommitted(),
                            usage.getUsed(),
                            createArray(memoryPoolNamesCast),
                            createArray(memoryPools) });
        }

        protected RubyArray beanToArray(MemoryPoolMXBean bean) {
            MemoryUsage usage = bean.getUsage();
            MemoryUsage peak = bean.getPeakUsage();
            MemoryUsage last = bean.getCollectionUsage();
            return createArray(
                    new long[]{
                            usage.getCommitted(),
                            usage.getInit(),
                            usage.getMax(),
                            usage.getUsed(),
                            peak.getCommitted(),
                            peak.getInit(),
                            peak.getMax(),
                            peak.getUsed(),
                            last.getCommitted(),
                            last.getInit(),
                            last.getMax(),
                            last.getUsed() });
        }

    }

}
