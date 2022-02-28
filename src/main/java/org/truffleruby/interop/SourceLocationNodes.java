/*
 * Copyright (c) 2022 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.interop;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.UnaryCoreMethodNode;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes;

import com.oracle.truffle.api.source.SourceSection;
import org.truffleruby.core.thread.ThreadBacktraceLocationNodes;

@CoreModule(value = "Truffle::Interop::SourceLocation", isClass = true)
public class SourceLocationNodes {

    @CoreMethod(names = "absolute_path")
    public abstract static class AbsolutePathNode extends UnaryCoreMethodNode {
        @TruffleBoundary
        @Specialization
        protected Object absolutePath(RubySourceLocation location,
                @Cached StringNodes.MakeStringNode makeStringNode) {
            final SourceSection sourceSection = location.sourceSection;
            if (!sourceSection.isAvailable()) {
                return coreStrings().UNKNOWN.createInstance(getContext());
            }

            return ThreadBacktraceLocationNodes.AbsolutePathNode.getAbsolutePath(sourceSection, makeStringNode, this);
        }
    }

    @CoreMethod(names = "path")
    public abstract static class PathNode extends UnaryCoreMethodNode {
        @TruffleBoundary
        @Specialization
        protected RubyString path(RubySourceLocation location,
                @Cached StringNodes.MakeStringNode makeStringNode) {
            final SourceSection sourceSection = location.sourceSection;

            if (!sourceSection.isAvailable()) {
                return coreStrings().UNKNOWN.createInstance(getContext());
            } else {
                final Rope path = getLanguage().getPathToRopeCache().getCachedPath(sourceSection.getSource());
                return makeStringNode.fromRope(path, Encodings.UTF_8);
            }
        }
    }

    @CoreMethod(names = { "first_lineno", "lineno" })
    public abstract static class LinenoNode extends UnaryCoreMethodNode {
        @TruffleBoundary
        @Specialization
        protected int lineno(RubySourceLocation location) {
            return location.sourceSection.getStartLine();
        }
    }

    @CoreMethod(names = "available?")
    public abstract static class IsAvailableNode extends UnaryCoreMethodNode {
        @TruffleBoundary
        @Specialization
        protected boolean isAvailable(RubySourceLocation location) {
            return location.sourceSection.isAvailable();
        }
    }

    @CoreMethod(names = "internal?")
    public abstract static class IsInternalNode extends UnaryCoreMethodNode {
        @TruffleBoundary
        @Specialization
        protected boolean isInternal(RubySourceLocation location) {
            return location.sourceSection.getSource().isInternal();
        }
    }

}
