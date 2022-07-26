/*
 * Copyright (c) 2015, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.format.printf;

import java.util.List;

import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.strings.InternalByteArray;
import com.oracle.truffle.api.strings.TruffleString;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.format.FormatEncoding;
import org.truffleruby.core.format.FormatRootNode;
import org.truffleruby.core.rope.Rope;

import com.oracle.truffle.api.RootCallTarget;
import org.truffleruby.language.library.RubyStringLibrary;

public class PrintfCompiler {

    private final RubyLanguage language;
    private final Node currentNode;

    public PrintfCompiler(RubyLanguage language, Node currentNode) {
        this.language = language;
        this.currentNode = currentNode;
    }

    public RootCallTarget compile(Object format, RubyStringLibrary libFormat, TruffleString.GetInternalByteArrayNode byteArrayNode,  Object[] arguments, boolean isDebug) {
        var formatTString = libFormat.getTString(format);
        var formatEncoding = libFormat.getEncoding(format);
        var byteArray = byteArrayNode.execute(formatTString, formatEncoding.tencoding);

        final PrintfSimpleParser parser = new PrintfSimpleParser(bytesToChars(byteArray), arguments, isDebug);
        final List<SprintfConfig> configs = parser.parse();
        final PrintfSimpleTreeBuilder builder = new PrintfSimpleTreeBuilder(language, configs);

        return new FormatRootNode(
                language,
                currentNode.getEncapsulatingSourceSection(),
                FormatEncoding.find(formatEncoding.jcoding, currentNode),
                builder.getNode()).getCallTarget();
    }

    private static char[] bytesToChars(InternalByteArray byteArray) {
        final int byteLength = byteArray.getLength();
        final char[] chars = new char[byteLength];

        for (int n = 0; n < byteLength; n++) {
            chars[n] = (char) byteArray.get(n);
        }

        return chars;
    }

}
