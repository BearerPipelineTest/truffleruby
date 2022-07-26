/*
 * Copyright (c) 2021, 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.string;

import com.oracle.truffle.api.strings.TruffleString;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.encoding.TStringUtils;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.LeafRope;
import org.truffleruby.core.rope.RopeConstants;

import java.util.ArrayList;
import java.util.List;

public class FrozenStrings {

    public static final List<LeafRope> ROPES = new ArrayList<>();
    public static final List<TruffleString> TSTRINGS = new ArrayList<>();

    public static final ImmutableRubyString EMPTY_US_ASCII = FrozenStringLiterals.createStringAndCacheLater(
            RopeConstants.EMPTY_US_ASCII_TSTRING,
            RopeConstants.EMPTY_US_ASCII_ROPE,
            Encodings.US_ASCII);

    public static final ImmutableRubyString YIELD = createFrozenStaticBinaryString("yield");
    public static final ImmutableRubyString ASSIGNMENT = createFrozenStaticBinaryString("assignment");
    public static final ImmutableRubyString CLASS_VARIABLE = createFrozenStaticBinaryString("class variable");
    public static final ImmutableRubyString CONSTANT = createFrozenStaticBinaryString("constant");
    public static final ImmutableRubyString EXPRESSION = createFrozenStaticBinaryString("expression");
    public static final ImmutableRubyString FALSE = createFrozenStaticBinaryString("false");
    public static final ImmutableRubyString GLOBAL_VARIABLE = createFrozenStaticBinaryString("global-variable");
    public static final ImmutableRubyString INSTANCE_VARIABLE = createFrozenStaticBinaryString("instance-variable");
    public static final ImmutableRubyString LOCAL_VARIABLE = createFrozenStaticBinaryString("local-variable");
    public static final ImmutableRubyString METHOD = createFrozenStaticBinaryString("method");
    public static final ImmutableRubyString NIL = createFrozenStaticBinaryString("nil");
    public static final ImmutableRubyString SELF = createFrozenStaticBinaryString("self");
    public static final ImmutableRubyString SUPER = createFrozenStaticBinaryString("super");
    public static final ImmutableRubyString TRUE = createFrozenStaticBinaryString("true");

    private static ImmutableRubyString createFrozenStaticBinaryString(String string) {
        // defined?(...) returns frozen strings with a binary encoding
        return createFrozenStaticString(string, Encodings.BINARY);
    }

    private static ImmutableRubyString createFrozenStaticString(String string, RubyEncoding encoding) {
        // defined?(...) returns frozen strings with a binary encoding
        final LeafRope rope = StringOperations.encodeRope(string, encoding.jcoding, CodeRange.CR_7BIT);
        ROPES.add(rope);
        var tstring = TStringUtils.fromRope(rope, encoding);
        TSTRINGS.add(tstring);
        return FrozenStringLiterals.createStringAndCacheLater(tstring, rope, encoding);
    }

}
