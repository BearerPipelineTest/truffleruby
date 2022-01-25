/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language.dispatch;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.frame.Frame;
import com.oracle.truffle.api.interop.TruffleObject;
import com.oracle.truffle.api.nodes.DirectCallNode;
import com.oracle.truffle.api.nodes.NodeCost;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.truffleruby.core.cast.ToSymbolNode;
import org.truffleruby.core.exception.ExceptionOperations.ExceptionFormatter;
import org.truffleruby.core.klass.RubyClass;
import org.truffleruby.core.symbol.RubySymbol;
import org.truffleruby.language.FrameAndVariablesSendingNode;
import org.truffleruby.language.RubyGuards;
import org.truffleruby.language.RubyRootNode;
import org.truffleruby.language.arguments.RubyArguments;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.language.methods.CallForeignMethodNode;
import org.truffleruby.language.methods.CallInternalMethodNode;
import org.truffleruby.language.methods.CallInternalMethodNodeGen;
import org.truffleruby.language.methods.InternalMethod;
import org.truffleruby.language.methods.LookupMethodNode;
import org.truffleruby.language.methods.LookupMethodNodeGen;
import org.truffleruby.language.objects.MetaClassNode;
import org.truffleruby.language.objects.MetaClassNodeGen;
import org.truffleruby.options.Options;

public class DispatchNode extends FrameAndVariablesSendingNode {

    private static final class Missing implements TruffleObject {
    }

    public static final Missing MISSING = new Missing();

    // NOTE(norswap): We need these static fields to be able to specify these values as `Cached#parameters` string
    //   values. We also want to use `parameters` rather than factory methods because Truffle uses it to automatically
    //   generate uncached instances where required.

    public static final DispatchConfiguration PUBLIC = DispatchConfiguration.PUBLIC;
    public static final DispatchConfiguration PRIVATE_RETURN_MISSING = DispatchConfiguration.PRIVATE_RETURN_MISSING;
    public static final DispatchConfiguration PUBLIC_RETURN_MISSING = DispatchConfiguration.PUBLIC_RETURN_MISSING;

    public static DispatchNode create(DispatchConfiguration config) {
        return new DispatchNode(config);
    }

    public static DispatchNode create() {
        return new DispatchNode(DispatchConfiguration.PRIVATE);
    }

    public static DispatchNode getUncached(DispatchConfiguration config) {
        return Uncached.UNCACHED_NODES[config.ordinal()];
    }

    public static DispatchNode getUncached() {
        return getUncached(DispatchConfiguration.PRIVATE);
    }

    public final DispatchConfiguration config;

    @Child protected MetaClassNode metaclassNode;
    @Child protected LookupMethodNode methodLookup;
    @Child protected CallInternalMethodNode callNode;
    @Child protected CallForeignMethodNode callForeign;
    @Child protected DispatchNode callMethodMissing;
    @Child protected ToSymbolNode toSymbol;

    protected final ConditionProfile methodMissing;
    protected final BranchProfile methodMissingMissing;

    protected DispatchNode(
            DispatchConfiguration config,
            MetaClassNode metaclassNode,
            LookupMethodNode methodLookup,
            CallInternalMethodNode callNode,
            ConditionProfile methodMissing,
            BranchProfile methodMissingMissing) {
        this.config = config;
        this.metaclassNode = metaclassNode;
        this.methodLookup = methodLookup;
        this.callNode = callNode;
        this.methodMissing = methodMissing;
        this.methodMissingMissing = methodMissingMissing;
    }

    protected DispatchNode(DispatchConfiguration config) {
        this(
                config,
                MetaClassNode.create(),
                LookupMethodNode.create(),
                CallInternalMethodNode.create(),
                ConditionProfile.create(),
                BranchProfile.create());
    }

    public Object call(Object receiver, String method) {
        final Object[] rubyArgs = RubyArguments.allocate(0);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        return dispatch(null, method, rubyArgs);
    }

    public Object call(Object receiver, String method, Object arg1) {
        final Object[] rubyArgs = RubyArguments.allocate(1);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        return dispatch(null, method, rubyArgs);
    }

    public Object call(Object receiver, String method, Object arg1, Object arg2) {
        final Object[] rubyArgs = RubyArguments.allocate(2);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        return dispatch(null, method, rubyArgs);
    }

    public Object call(Object receiver, String method, Object arg1, Object arg2, Object arg3) {
        final Object[] rubyArgs = RubyArguments.allocate(3);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        RubyArguments.setArgument(rubyArgs, 2, arg3);
        return dispatch(null, method, rubyArgs);
    }

    public Object call(Object receiver, String method, Object[] arguments) {
        return dispatch(null, method, RubyArguments.pack(null, null, null, null, null, receiver, nil, arguments));
    }

    public Object callWithBlock(Object receiver, String method, Object block) {
        final Object[] rubyArgs = RubyArguments.allocate(0);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, block);
        return dispatch(null, method, rubyArgs);
    }

    public Object callWithBlock(Object receiver, String method, Object block, Object arg1) {
        final Object[] rubyArgs = RubyArguments.allocate(1);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, block);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        return dispatch(null, method, rubyArgs);
    }

    public Object callWithBlock(Object receiver, String method, Object block, Object arg1, Object arg2) {
        final Object[] rubyArgs = RubyArguments.allocate(2);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, block);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        return dispatch(null, method, rubyArgs);
    }

    public Object callWithBlock(Object receiver, String method, Object block, Object arg1, Object arg2, Object arg3) {
        final Object[] rubyArgs = RubyArguments.allocate(3);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, block);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        RubyArguments.setArgument(rubyArgs, 2, arg3);
        return dispatch(null, method, rubyArgs);
    }

    public Object callWithBlock(Object receiver, String method, Object block, Object[] arguments) {
        return dispatch(null, method, RubyArguments.pack(null, null, null, null, null, receiver, block, arguments));
    }

    public Object callWithFrame(Frame frame, Object receiver, String method) {
        final Object[] rubyArgs = RubyArguments.allocate(0);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        return dispatch(frame, method, rubyArgs);
    }

    public Object callWithFrame(Frame frame, Object receiver, String method, Object arg1) {
        final Object[] rubyArgs = RubyArguments.allocate(1);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        return dispatch(frame, method, rubyArgs);
    }

    public Object callWithFrame(Frame frame, Object receiver, String method, Object arg1, Object arg2) {
        final Object[] rubyArgs = RubyArguments.allocate(2);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        return dispatch(frame, method, rubyArgs);
    }

    public Object callWithFrame(Frame frame, Object receiver, String method, Object arg1, Object arg2, Object arg3) {
        final Object[] rubyArgs = RubyArguments.allocate(3);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        RubyArguments.setArgument(rubyArgs, 0, arg1);
        RubyArguments.setArgument(rubyArgs, 1, arg2);
        RubyArguments.setArgument(rubyArgs, 2, arg3);
        return dispatch(frame, method, rubyArgs);
    }

    public Object callWithFrame(Frame frame, Object receiver, String method, Object[] arguments) {
        return dispatch(frame, method, RubyArguments.pack(null, null, null, null, null, receiver, nil, arguments));
    }

    public final Object dispatch(Frame frame, String methodName, Object[] rubyArgs) {
        Object receiver = RubyArguments.getSelf(rubyArgs);
        final RubyClass metaclass = metaclassNode.execute(receiver);
        final InternalMethod method = methodLookup.execute(frame, metaclass, methodName, config);

        if (methodMissing.profile(method == null || method.isUndefined())) {
            switch (config.missingBehavior) {
                case RETURN_MISSING:
                    return MISSING;
                case CALL_METHOD_MISSING:
                    // Both branches implicitly profile through lazy node creation
                    final Object block = RubyArguments.getBlock(rubyArgs);
                    if (RubyGuards.isForeignObject(receiver)) { // TODO (eregon, 16 Aug 2021) maybe use a final boolean on the class to know if foreign
                        return callForeign(receiver, methodName, block, rubyArgs);
                    } else {
                        return callMethodMissing(frame, methodName, rubyArgs);
                    }
            }
        }

        RubyArguments.setMethod(rubyArgs, method);
        RubyArguments.setCallerData(rubyArgs, getFrameOrStorageIfRequired(frame));
        return callNode.execute(frame, rubyArgs);
    }

    public final Object dispatch(Frame frame, Object receiver, String methodName, Object block) {
        final Object[] rubyArgs = RubyArguments.allocate(0);
        RubyArguments.setSelf(rubyArgs, receiver);
        RubyArguments.setBlock(rubyArgs, nil);
        return dispatch(frame, methodName, rubyArgs);
    }

    public final Object dispatch(Frame frame, Object receiver, String methodName, Object block, Object[] arguments) {
        return dispatch(frame, methodName,
                RubyArguments.pack(null, null, null, null, null, receiver, block, arguments));
    }

    private Object callMethodMissing(Frame frame, String methodName, Object[] rubyArgs) {
        final RubySymbol symbolName = nameToSymbol(methodName);
        final Object[] newArgs = RubyArguments.repack(rubyArgs, RubyArguments.getSelf(rubyArgs), 0, 1,
                RubyArguments.getArgumentsCount(rubyArgs));

        RubyArguments.setArgument(newArgs, 0, symbolName);
        final Object result = callMethodMissingNode(frame, newArgs);

        if (result == MISSING) {
            methodMissingMissing.enter();
            throw new RaiseException(getContext(), coreExceptions().noMethodErrorFromMethodMissing(
                    ExceptionFormatter.NO_METHOD_ERROR,
                    RubyArguments.getSelf(rubyArgs),
                    methodName,
                    RubyArguments.getArguments(rubyArgs),
                    this));
        }

        return result;
    }

    protected Object callForeign(Object receiver, String methodName, Object block, Object[] rubyArgs) {
        if (callForeign == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            callForeign = insert(CallForeignMethodNode.create());
        }

        final Object[] arguments = RubyArguments.getArguments(rubyArgs);
        return callForeign.execute(receiver, methodName, block, arguments);
    }

    protected Object callMethodMissingNode(Frame frame, Object[] rubyArgs) {
        if (callMethodMissing == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            // #method_missing ignores refinements on CRuby: https://bugs.ruby-lang.org/issues/13129
            callMethodMissing = insert(
                    DispatchNode.create(DispatchConfiguration.PRIVATE_RETURN_MISSING_IGNORE_REFINEMENTS));
        }
        return callMethodMissing.dispatch(frame, "method_missing", rubyArgs);
    }

    protected RubySymbol nameToSymbol(String methodName) {
        if (toSymbol == null) {
            CompilerDirectives.transferToInterpreterAndInvalidate();
            toSymbol = insert(ToSymbolNode.create());
        }
        return toSymbol.execute(methodName);
    }

    /** This will be called from the {@link CallInternalMethodNode} child whenever it creates a new
     * {@link DirectCallNode}. */
    public final void applySplittingInliningStrategy(RootCallTarget callTarget, String methodName,
            DirectCallNode callNode) {


        final Options options = getContext().getOptions();

        // The way that #method_missing is used is usually as an indirection to call some other method, and possibly to
        // modify the arguments. In both cases, but especially the latter, it makes a lot of sense to manually clone the
        // call target and to inline it.
        final boolean isMethodMissing = methodName.equals("method_missing");

        if (callNode.isCallTargetCloningAllowed() &&
                (RubyRootNode.of(callTarget).shouldAlwaysClone() ||
                        isMethodMissing && options.METHODMISSING_ALWAYS_CLONE)) {
            callNode.cloneCallTarget();
        }

        if (callNode.isInlinable() &&
                ((sendingFrames() && options.INLINE_NEEDS_CALLER_FRAME) ||
                        isMethodMissing && options.METHODMISSING_ALWAYS_INLINE)) {
            callNode.forceInlining();
        }
    }

    private static class Uncached extends DispatchNode {

        static final Uncached[] UNCACHED_NODES = new Uncached[DispatchConfiguration.values().length];
        static {
            for (DispatchConfiguration config : DispatchConfiguration.values()) {
                UNCACHED_NODES[config.ordinal()] = new Uncached(config);
            }
        }

        protected Uncached(DispatchConfiguration config) {
            super(
                    config,
                    MetaClassNodeGen.getUncached(),
                    LookupMethodNodeGen.getUncached(),
                    CallInternalMethodNodeGen.getUncached(),
                    ConditionProfile.getUncached(),
                    BranchProfile.getUncached());
        }

        @Override
        protected Object callForeign(Object receiver, String methodName, Object block, Object[] arguments) {
            if (callForeign == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callForeign = insert(CallForeignMethodNode.getUncached());
            }

            return callForeign.execute(receiver, methodName, block, arguments);
        }

        @Override
        protected Object callMethodMissingNode(
                Frame frame, Object[] rubyArgs) {
            if (callMethodMissing == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                callMethodMissing = insert(
                        DispatchNode.getUncached(DispatchConfiguration.PRIVATE_RETURN_MISSING_IGNORE_REFINEMENTS));
            }

            return callMethodMissing.dispatch(frame, "method_missing", rubyArgs);
        }

        @Override
        protected RubySymbol nameToSymbol(String methodName) {
            if (toSymbol == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                toSymbol = insert(ToSymbolNode.getUncached());
            }
            return toSymbol.execute(methodName);
        }

        @Override
        public NodeCost getCost() {
            return NodeCost.MEGAMORPHIC;
        }

        @Override
        public boolean isAdoptable() {
            return false;
        }
    }
}
