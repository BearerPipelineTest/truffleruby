/*
 * Copyright (c) 2013, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.hash;

import org.truffleruby.core.hash.library.EntryArrayHashStore;
import org.truffleruby.core.hash.library.NullHashStore;

public abstract class HashGuards {

    // Storage strategies

    public static boolean isNullHash(RubyHash hash) {
        return hash.store == NullHashStore.NULL_HASH_STORE;
    }

    public static boolean isPackedHash(RubyHash hash) {
        return hash.store instanceof Object[];
    }

    public static boolean isBucketHash(RubyHash hash) {
        return hash.store instanceof EntryArrayHashStore;
    }

    public static int hashStrategyLimit() {
        return 3;
    }

    // Higher level properties

    public static boolean isEmptyHash(RubyHash hash) {
        return hash.size == 0;
    }

    public static boolean isCompareByIdentity(RubyHash hash) {
        return hash.compareByIdentity;
    }

}
