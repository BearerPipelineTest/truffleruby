/*
 * Copyright (c) 2015, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.extra;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.object.Shape;
import org.truffleruby.core.kernel.KernelNodes;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.language.RubyDynamicObject;

import java.util.concurrent.ConcurrentHashMap;

public class RubyConcurrentMap extends RubyDynamicObject {

    public static class Key {

        public final Object key;
        private final int hashCode;

        public Key(Object key, int hashCode) {
            this.key = key;
            this.hashCode = hashCode;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object other) {
            assert other instanceof Key;
            final Key otherKey = (Key) other;

            if (hashCode != otherKey.hashCode) {
                return false;
            } else {
                // To do: It's unfortunate we're calling this behind a boundary! Can we do better?
                final Object returnValue = KernelNodes.SameOrEqlNode.getUncached().execute(key, otherKey.key);
                if (returnValue instanceof Boolean) {
                    return (boolean) returnValue;
                } else {
                    throw new UnsupportedOperationException(returnValue.getClass().getName());
                }
            }
        }
    }

    /*
     * In Concurrent::Map, and so TruffleRuby::ConcurrentMap, keys are compared with #hash and #eql?, and keys by
     * identity (#equal? in NonConcurrentMapBackend.) To use custom code to compare the keys we need to subclass
     * and implement #hashCode and #equals. Identity is the default, so we don't need to do anything there.
     */
    private ConcurrentHashMap<Key, Object> map;

    public RubyConcurrentMap(RubyClass rubyClass, Shape shape) {
        super(rubyClass, shape);
    }

    @TruffleBoundary
    public void allocateMap(int initialCapacity, float loadFactor) {
        // To do: This method is seems empty but was blacklisted on CI
        map = new ConcurrentHashMap<>(initialCapacity, loadFactor);
    }

    public ConcurrentHashMap<Key, Object> getMap() {
        return map;
    }
}
