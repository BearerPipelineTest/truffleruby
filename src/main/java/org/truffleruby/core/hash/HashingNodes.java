/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.hash;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.Fallback;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.basicobject.BasicObjectNodes.ObjectIDNode;
import org.truffleruby.core.cast.ToRubyIntegerNode;
import org.truffleruby.core.hash.HashingNodesFactory.HashCastResultNodeGen;
import org.truffleruby.core.hash.HashingNodesFactory.ToHashByIdentityNodeGen;
import org.truffleruby.core.numeric.BigIntegerOps;
import org.truffleruby.core.numeric.RubyBignum;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.core.symbol.SymbolNodes;
import org.truffleruby.core.string.ImmutableRubyString;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.dispatch.DispatchNode;

public abstract class HashingNodes {

    public abstract static class ToHash extends RubyContextNode {

        public static ToHash create() {
            return HashingNodesFactory.ToHashNodeGen.create();
        }

        public abstract int execute(Object key, boolean compareByIdentity);

        @Specialization(guards = "!compareByIdentity")
        protected int hash(Object key, boolean compareByIdentity,
                @Cached ToHashByHashCode toHashByHashCode) {
            return toHashByHashCode.execute(key);
        }


        @Specialization(guards = "compareByIdentity")
        protected int hashCompareByIdentity(Object key, boolean compareByIdentity,
                @Cached ToHashByIdentity toHashByIdentity) {
            return toHashByIdentity.execute(key);
        }

    }

    // MRI: any_hash
    public abstract static class ToHashByHashCode extends RubyContextNode {

        @Child private DispatchNode hashCallNode;
        @Child private HashCastResultNode hashCastResultNode;

        public static ToHashByHashCode create() {
            return HashingNodesFactory.ToHashByHashCodeNodeGen.create();
        }

        public abstract int execute(Object key);

        @Specialization
        protected int hashBoolean(boolean value,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (int) HashOperations.hashBoolean(value, context, this);
        }

        @Specialization
        protected int hashInt(int value,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (int) HashOperations.hashLong(value, context, this);
        }

        @Specialization
        protected int hashLong(long value,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (int) HashOperations.hashLong(value, context, this);
        }

        @Specialization
        protected int hashDouble(double value,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (int) HashOperations.hashDouble(value, context, this);
        }

        @Specialization
        protected int hashBignum(RubyBignum value,
                @CachedContext(RubyLanguage.class) RubyContext context) {
            return (int) HashOperations.hashBignum(value, context, this);
        }

        @Specialization
        protected int hashString(RubyString value,
                @Cached StringNodes.HashNode stringHashNode) {
            return (int) stringHashNode.execute(value);
        }

        @Specialization
        protected int hashImmutableString(ImmutableRubyString value,
                @Cached StringNodes.HashNode stringHashNode) {
            return (int) stringHashNode.execute(value);
        }


        @Specialization
        protected int hashSymbol(RubySymbol value,
                @Cached SymbolNodes.HashNode symbolHashNode) {
            return (int) symbolHashNode.execute(value);
        }

        @Fallback
        protected int hash(Object value) {
            return castResult(callHash(value));
        }

        private Object callHash(Object value) {
            if (hashCallNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hashCallNode = insert(DispatchNode.create());
            }
            return hashCallNode.call(value, "hash");
        }

        private int castResult(Object value) {
            if (hashCastResultNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                hashCastResultNode = insert(HashCastResultNode.create());
            }
            return hashCastResultNode.execute(value);
        }

    }

    @GenerateUncached
    public abstract static class ToHashByIdentity extends RubyBaseNode {

        public static ToHashByIdentity create() {
            return ToHashByIdentityNodeGen.create();
        }

        public static ToHashByIdentity getUncached() {
            return ToHashByIdentityNodeGen.getUncached();
        }

        public abstract int execute(Object key);

        @Specialization
        protected int toHashByIdentity(Object hashed,
                @Cached ObjectIDNode objectIDNode,
                @Cached HashCastResultNode hashCastResultNode) {
            return hashCastResultNode.execute(objectIDNode.execute(hashed));
        }
    }

    @GenerateUncached
    public abstract static class HashCastResultNode extends RubyBaseNode {

        public abstract int execute(Object key);

        public static HashCastResultNode create() {
            return HashCastResultNodeGen.create();
        }

        @Specialization
        protected int castInt(int hashed) {
            return hashed;
        }

        @Specialization
        protected int castLong(long hashed) {
            return (int) hashed;
        }

        @Specialization
        protected int castBignum(RubyBignum hashed) {
            return BigIntegerOps.hashCode(hashed);
        }

        @Specialization(guards = "!isRubyInteger(hashed)")
        protected int castOther(Object hashed,
                @Cached ToRubyIntegerNode toRubyInteger,
                @Cached HashCastResultNode hashCastResult) {
            return hashCastResult.execute(toRubyInteger.execute(hashed));
        }
    }
}
