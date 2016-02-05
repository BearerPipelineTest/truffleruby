/*
 * Copyright (c) 2013, 2015 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.language.locals;

import com.oracle.truffle.api.frame.FrameSlot;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.source.SourceSection;
import org.jcodings.specific.UTF8Encoding;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.RubyContext;

public class WriteLocalVariableNode extends RubyNode {

    @Child private RubyNode valueNode;
    @Child private WriteFrameSlotNode writeFrameSlotNode;

    public WriteLocalVariableNode(RubyContext context, SourceSection sourceSection, RubyNode valueNode, FrameSlot frameSlot) {
        super(context, sourceSection);
        this.valueNode = valueNode;
        writeFrameSlotNode = WriteFrameSlotNodeGen.create(frameSlot);
    }

    @Override
    public Object execute(VirtualFrame frame) {
        return writeFrameSlotNode.executeWrite(frame, valueNode.execute(frame));
    }

    @Override
    public Object isDefined(VirtualFrame frame) {
        return create7BitString("assignment", UTF8Encoding.INSTANCE);
    }

}
