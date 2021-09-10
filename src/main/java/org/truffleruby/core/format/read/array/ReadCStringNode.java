/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.read.array;

import org.truffleruby.cext.UnwrapNode;
import org.truffleruby.core.array.ArrayGuards;
import org.truffleruby.core.array.library.ArrayStoreLibrary;
import org.truffleruby.core.format.FormatNode;
import org.truffleruby.core.format.read.SourceNode;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.interop.InteropNodes;
import org.truffleruby.interop.TranslateInteropExceptionNode;
import org.truffleruby.language.library.RubyStringLibrary;

import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.InteropLibrary;
import com.oracle.truffle.api.library.CachedLibrary;

@NodeChild(value = "source", type = SourceNode.class)
@ImportStatic(ArrayGuards.class)
public abstract class ReadCStringNode extends FormatNode {

    protected final Object stringReader;

    public ReadCStringNode(Object stringReader) {
        this.stringReader = stringReader;
    }

    @Specialization(limit = "storageStrategyLimit()")
    protected Object read(VirtualFrame frame, Object source,
            @Cached UnwrapNode unwrapNode,
            @Cached TranslateInteropExceptionNode translateInteropExceptionNode,
            @CachedLibrary(limit = "2") RubyStringLibrary libString,
            @CachedLibrary("stringReader") InteropLibrary stringReaders,
            @CachedLibrary("source") ArrayStoreLibrary sources,
            @Cached RopeNodes.BytesNode bytesNode) {
        Object pointer = sources.read(source, advanceSourcePosition(frame));
        Object string = unwrapNode.execute(InteropNodes.execute(
                stringReader,
                new Object[]{ pointer },
                stringReaders,
                translateInteropExceptionNode));
        return bytesNode.execute(libString.getRope(string));
    }

}
