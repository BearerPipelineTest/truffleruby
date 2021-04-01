/*
 * Copyright (c) 2015, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.pack;

import com.oracle.truffle.api.nodes.Node;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.format.FormatRootNode;
import org.truffleruby.core.format.LoopRecovery;

import com.oracle.truffle.api.RootCallTarget;
import com.oracle.truffle.api.Truffle;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.language.control.DeferredRaiseException;

public class PackCompiler {

    private final RubyLanguage language;
    private final Node currentNode;

    public PackCompiler(RubyLanguage language, Node currentNode) {
        this.language = language;
        this.currentNode = currentNode;
    }

    public RootCallTarget compile(String format) throws DeferredRaiseException {
        if (format.length() > language.options.PACK_RECOVER_LOOP_MIN) {
            format = LoopRecovery.recoverLoop(format);
        }

        final SimplePackTreeBuilder builder = new SimplePackTreeBuilder(language, currentNode);

        builder.enterSequence();

        final SimplePackParser parser = new SimplePackParser(builder, RopeOperations.encodeAsciiBytes(format));
        parser.parse();

        builder.exitSequence();

        return Truffle.getRuntime().createCallTarget(
                new FormatRootNode(
                        language,
                        currentNode.getEncapsulatingSourceSection(),
                        builder.getEncoding(),
                        builder.getNode()));
    }

}
