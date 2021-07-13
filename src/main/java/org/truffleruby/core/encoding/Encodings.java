/*
 * Copyright (c) 2014, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 *
 * Some of the code in this class is modified from org.jruby.runtime.encoding.EncodingService,
 * licensed under the same EPL 2.0/GPL 2.0/LGPL 2.1 used throughout.
 */
package org.truffleruby.core.encoding;

import com.oracle.truffle.api.CompilerDirectives.CompilationFinal;
import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.jcodings.Encoding;
import org.jcodings.EncodingDB;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.util.CaseInsensitiveBytesHash;
import org.truffleruby.RubyLanguage;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.ImmutableRubyString;

public class Encodings {

    public static final int INITIAL_NUMBER_OF_ENCODINGS = EncodingDB.getEncodings().size();
    @CompilationFinal(dimensions = 1) public final RubyEncoding[] BUILT_IN_ENCODINGS = //
            new RubyEncoding[INITIAL_NUMBER_OF_ENCODINGS];

    private final RubyLanguage language;

    public Encodings(RubyLanguage language) {
        this.language = language;
        initializeRubyEncodings();

    }

    private void initializeRubyEncodings() {
        final CaseInsensitiveBytesHash<EncodingDB.Entry>.CaseInsensitiveBytesHashEntryIterator hei = EncodingDB
                .getEncodings()
                .entryIterator();
        while (hei.hasNext()) {
            final CaseInsensitiveBytesHash.CaseInsensitiveBytesHashEntry<EncodingDB.Entry> e = hei.next();
            final EncodingDB.Entry encodingEntry = e.value;
            final RubyEncoding rubyEncoding = newRubyEncoding(
                    encodingEntry.getEncoding(),
                    encodingEntry.getEncoding().getIndex(),
                    e.bytes,
                    e.p,
                    e.end);
            BUILT_IN_ENCODINGS[encodingEntry.getEncoding().getIndex()] = rubyEncoding;
        }
    }

    @TruffleBoundary
    public RubyEncoding newRubyEncoding(Encoding encoding, int index, byte[] name, int p, int end) {
        assert p == 0 : "Ropes can't be created with non-zero offset: " + p;
        assert end == name.length : "Ropes must have the same exact length as the name array (len = " + end +
                "; name.length = " + name.length + ")";

        final Rope rope = RopeOperations.create(name, USASCIIEncoding.INSTANCE, CodeRange.CR_7BIT);
        final ImmutableRubyString string = language.getFrozenStringLiteral(rope);

        return new RubyEncoding(encoding, string, index);
    }

    public RubyEncoding getBuiltInEncoding(int index) {
        return BUILT_IN_ENCODINGS[index];
    }

}
