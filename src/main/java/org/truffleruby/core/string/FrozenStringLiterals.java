/*
 * Copyright (c) 2018, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.string;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import org.jcodings.Encoding;
import org.truffleruby.collections.WeakValueCache;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.LeafRope;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeCache;

import java.util.Collection;

import static org.truffleruby.core.encoding.Encodings.ENCODING_NAMES;

public class FrozenStringLiterals {

    private final RopeCache ropeCache;
    private final WeakValueCache<LeafRope, ImmutableRubyString> values = new WeakValueCache<>();

    public FrozenStringLiterals(RopeCache ropeCache) {
        this.ropeCache = ropeCache;
        for (ImmutableRubyString name : ENCODING_NAMES) {
            addFrozenStringLiteral(name);
        }
    }

    @TruffleBoundary
    public ImmutableRubyString getFrozenStringLiteral(Rope rope) {
        return getFrozenStringLiteral(rope.getBytes(), rope.getEncoding(), rope.getCodeRange());
    }

    @TruffleBoundary
    public ImmutableRubyString getFrozenStringLiteral(byte[] bytes, Encoding encoding, CodeRange codeRange) {
        // Ensure all ImmutableRubyString have a Rope from the RopeCache
        final LeafRope cachedRope = ropeCache.getRope(bytes, encoding, codeRange);

        final ImmutableRubyString string = values.get(cachedRope);
        if (string != null) {
            return string;
        } else {
            final RubyEncoding rubyEncoding = Encodings.getBuiltInEncoding(encoding.getIndex());
            return values.addInCacheIfAbsent(cachedRope, new ImmutableRubyString(cachedRope, rubyEncoding));
        }
    }

    private void addFrozenStringLiteral(ImmutableRubyString string) {
        final LeafRope cachedRope = ropeCache.getRope(string.rope);
        values.addInCacheIfAbsent(cachedRope, string);
    }

    @TruffleBoundary
    public Collection<ImmutableRubyString> allFrozenStrings() {
        return values.values();
    }

}
