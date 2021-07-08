/*
 * Copyright (c) 2014, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.hash;

import com.oracle.truffle.api.dsl.CachedLanguage;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.truffleruby.RubyLanguage;
import org.truffleruby.collections.PEBiFunction;
import org.truffleruby.core.basicobject.BasicObjectNodes.ReferenceEqualNode;
import org.truffleruby.core.hash.library.PackedHashStoreLibrary;
import org.truffleruby.language.RubyBaseNode;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.ExplodeLoop.LoopExplosionKind;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;

@GenerateUncached
@ImportStatic(HashGuards.class)
public abstract class LookupPackedEntryNode extends RubyBaseNode {

    public static LookupPackedEntryNode create() {
        return LookupPackedEntryNodeGen.create();
    }

    public abstract Object executePackedLookup(Frame frame, RubyHash hash, Object key, int hashed,
            PEBiFunction defaultValueNode);

    @Specialization(
            guards = {
                    "isCompareByIdentity(hash) == cachedByIdentity",
                    "cachedIndex >= 0",
                    "cachedIndex < getSize(hash)",
                    "sameKeysAtIndex(refEqual, hash, key, hashed, cachedIndex, cachedByIdentity)" },
            limit = "1")
    protected Object getConstantIndexPackedArray(RubyHash hash, Object key, int hashed, PEBiFunction defaultValueNode,
            @Cached ReferenceEqualNode refEqual,
            @Cached("isCompareByIdentity(hash)") boolean cachedByIdentity,
            @Cached("index(refEqual, hash, key, hashed, cachedByIdentity)") int cachedIndex) {
        final Object[] store = (Object[]) hash.store;
        return PackedHashStoreLibrary.getValue(store, cachedIndex);
    }

    protected int index(ReferenceEqualNode refEqual, RubyHash hash, Object key, int hashed,
            boolean compareByIdentity) {

        final Object[] store = (Object[]) hash.store;
        final int size = hash.size;

        for (int n = 0; n < size; n++) {
            final int otherHashed = PackedHashStoreLibrary.getHashed(store, n);
            final Object otherKey = PackedHashStoreLibrary.getKey(store, n);
            if (sameKeys(refEqual, compareByIdentity, key, hashed, otherKey, otherHashed)) {
                return n;
            }
        }

        return -1;
    }

    protected boolean sameKeysAtIndex(ReferenceEqualNode refEqual, RubyHash hash, Object key, int hashed,
            int cachedIndex, boolean cachedByIdentity) {
        final Object[] store = (Object[]) hash.store;
        final Object otherKey = PackedHashStoreLibrary.getKey(store, cachedIndex);
        final int otherHashed = PackedHashStoreLibrary.getHashed(store, cachedIndex);

        return sameKeys(refEqual, cachedByIdentity, key, hashed, otherKey, otherHashed);
    }

    private boolean sameKeys(ReferenceEqualNode refEqual, boolean compareByIdentity, Object key, int hashed,
            Object otherKey, int otherHashed) {
        return CompareHashKeysNode.referenceEqualKeys(refEqual, compareByIdentity, key, hashed, otherKey, otherHashed);
    }

    protected int getSize(RubyHash hash) {
        return hash.size;
    }

    @ExplodeLoop(kind = LoopExplosionKind.FULL_UNROLL_UNTIL_RETURN)
    @Specialization(replaces = "getConstantIndexPackedArray")
    protected Object getPackedArray(Frame frame, RubyHash hash, Object key, int hashed, PEBiFunction defaultValueNode,
            @Cached CompareHashKeysNode compareHashKeys,
            @Cached BranchProfile notInHashProfile,
            @Cached ConditionProfile byIdentityProfile,
            @CachedLanguage RubyLanguage language) {
        final boolean compareByIdentity = byIdentityProfile.profile(hash.compareByIdentity);

        final Object[] store = (Object[]) hash.store;
        final int size = hash.size;

        for (int n = 0; n < language.options.HASH_PACKED_ARRAY_MAX; n++) {
            if (n < size) {
                final int otherHashed = PackedHashStoreLibrary.getHashed(store, n);
                final Object otherKey = PackedHashStoreLibrary.getKey(store, n);
                if (equalKeys(compareHashKeys, compareByIdentity, key, hashed, otherKey, otherHashed)) {
                    return PackedHashStoreLibrary.getValue(store, n);
                }
            }
        }

        notInHashProfile.enter();
        // frame should be virtual or null
        return defaultValueNode.accept((VirtualFrame) frame, hash, key);
    }

    protected boolean equalKeys(CompareHashKeysNode compareHashKeys, boolean compareByIdentity, Object key, int hashed,
            Object otherKey, int otherHashed) {
        return compareHashKeys.execute(compareByIdentity, key, hashed, otherKey, otherHashed);
    }

}
