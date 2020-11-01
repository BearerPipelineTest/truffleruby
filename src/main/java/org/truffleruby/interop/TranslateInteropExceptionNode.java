/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.interop;

import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.language.RubyBaseNode;
import org.truffleruby.language.control.RaiseException;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.CachedContext;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.interop.ArityException;
import com.oracle.truffle.api.interop.InteropException;
import com.oracle.truffle.api.interop.InvalidArrayIndexException;
import com.oracle.truffle.api.interop.UnknownIdentifierException;
import com.oracle.truffle.api.interop.UnsupportedMessageException;
import com.oracle.truffle.api.interop.UnsupportedTypeException;

@GenerateUncached
public abstract class TranslateInteropExceptionNode extends RubyBaseNode {

    public static TranslateInteropExceptionNode getUncached() {
        return TranslateInteropExceptionNodeGen.getUncached();
    }

    public final RuntimeException execute(InteropException exception) {
        return execute(exception, false, null, null);
    }

    public final RuntimeException executeInInvokeMember(InteropException exception, Object receiver, Object[] args) {
        return execute(exception, true, receiver, args);
    }

    protected abstract RuntimeException execute(
            InteropException exception,
            boolean inInvokeMember,
            Object receiver,
            Object[] args);

    @Specialization
    protected RuntimeException handle(
            UnsupportedMessageException exception,
            boolean inInvokeMember,
            Object receiver,
            Object[] args,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return new RaiseException(
                context,
                context.getCoreExceptions().unsupportedMessageError(exception.getMessage(), this),
                exception);
    }

    @Specialization
    protected RuntimeException handle(
            InvalidArrayIndexException exception,
            boolean inInvokeMember,
            Object receiver,
            Object[] args,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return new RaiseException(
                context,
                context.getCoreExceptions().indexErrorInvalidArrayIndexException(exception, this),
                exception);
    }

    @Specialization
    protected RuntimeException handle(
            UnknownIdentifierException exception,
            boolean inInvokeMember,
            Object receiver,
            Object[] args,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        if (inInvokeMember) {
            return new RaiseException(
                    context,
                    context.getCoreExceptions().noMethodErrorUnknownIdentifier(
                            receiver,
                            exception.getUnknownIdentifier(),
                            args,
                            exception,
                            this),
                    exception);
        } else {
            return new RaiseException(
                    context,
                    context.getCoreExceptions().nameErrorUnknownIdentifierException(exception, receiver, this),
                    exception);
        }
    }

    @TruffleBoundary // Throwable#initCause
    @Specialization
    protected RuntimeException handle(
            UnsupportedTypeException exception,
            boolean inInvokeMember,
            Object receiver,
            Object[] args,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return new RaiseException(
                context,
                context.getCoreExceptions().typeErrorUnsupportedTypeException(exception, this),
                exception);
    }

    @Specialization
    protected RuntimeException handle(ArityException exception, boolean inInvokeMember, Object receiver, Object[] args,
            @CachedContext(RubyLanguage.class) RubyContext context) {
        return new RaiseException(
                context,
                context.getCoreExceptions().argumentError(
                        exception.getActualArity(),
                        exception.getExpectedArity(),
                        this),
                exception);
    }

}
