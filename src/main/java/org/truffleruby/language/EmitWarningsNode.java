/*
 * Copyright (c) 2021, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.language;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.frame.VirtualFrame;
import org.jcodings.specific.UTF8Encoding;
import org.truffleruby.RubyContext;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.control.RaiseException;
import org.truffleruby.parser.RubyDeferredWarnings;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

public class EmitWarningsNode extends RubyContextSourceNode {

    private final RubyDeferredWarnings warnings;

    public EmitWarningsNode(RubyDeferredWarnings warnings) {
        this.warnings = warnings;
    }

    @Override
    public Object execute(VirtualFrame frame) {
        final RubyContext context = getContext();
        boolean isVerbose = context.getCoreLibrary().isVerbose();
        boolean warningsEnabled = context.getCoreLibrary().warningsEnabled();
        printWarnings(context, isVerbose, warningsEnabled);
        return nil;
    }

    @TruffleBoundary
    private void printWarnings(RubyContext context, boolean isVerbose, boolean warningsEnabled) {
        for (RubyDeferredWarnings.WarningMessage warningMessage : warnings.warnings) {
            if (warningMessage.verbosity == RubyDeferredWarnings.Verbosity.VERBOSE) {
                if (isVerbose) {
                    printWarning(context, warningMessage.message);
                }
            } else {
                if (warningsEnabled) {
                    printWarning(context, warningMessage.message);
                }
            }
        }
    }

    public static void printWarning(RubyContext context, String message) {
        if (context.getCoreLibrary().isLoaded()) {
            final Object warning = context.getCoreLibrary().warningModule;
            final Rope messageRope = StringOperations.encodeRope(message, UTF8Encoding.INSTANCE);
            final RubyString messageString = StringOperations
                    .createString(context, context.getLanguageSlow(), messageRope);
            RubyContext.send(warning, "warn", messageString);
        } else {
            try {
                context.getEnv().err().write(message.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new RaiseException(context, context.getCoreExceptions().ioError(e, null));
            }
        }
    }
}
