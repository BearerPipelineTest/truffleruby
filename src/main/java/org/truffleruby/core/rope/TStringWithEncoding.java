/*
 * Copyright (c) 2021 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.rope;

import java.util.Objects;

import com.oracle.truffle.api.CompilerAsserts;
import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.strings.AbstractTruffleString;
import com.oracle.truffle.api.strings.InternalByteArray;
import org.truffleruby.core.encoding.Encodings;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.encoding.TStringGuards;
import org.truffleruby.core.encoding.TStringUtils;
import org.truffleruby.language.library.RubyStringLibrary;

public final class TStringWithEncoding {

    public final AbstractTruffleString tstring;
    public final RubyEncoding encoding;

    public TStringWithEncoding(AbstractTruffleString tstring, RubyEncoding encoding) {
        assert tstring.isCompatibleTo(encoding.tencoding);
        this.tstring = tstring;
        this.encoding = encoding;
    }

    public TStringWithEncoding(RubyStringLibrary stringLib, Object string) {
        this(stringLib.getTString(string), stringLib.getEncoding(string));
    }

    public RubyEncoding getEncoding() {
        return encoding;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof TStringWithEncoding)) {
            return false;
        }
        TStringWithEncoding that = (TStringWithEncoding) o;
        return encoding == that.encoding && tstring.equals(that.tstring);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tstring, encoding);
    }

    public Rope toRope() {
        return TStringUtils.toRope(tstring, encoding);
    }

    public int byteLength() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return tstring.byteLength(encoding.tencoding);
    }

    public InternalByteArray getInternalByteArray() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return tstring.getInternalByteArrayUncached(encoding.tencoding);
    }

    public CodeRange getCodeRange() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return TStringUtils.toCodeRange(tstring.getByteCodeRangeUncached(encoding.tencoding));
    }

    public TStringWithEncoding forceEncoding(RubyEncoding newEncoding) {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        if (encoding == newEncoding) {
            return this;
        } else {
            return new TStringWithEncoding(tstring.forceEncodingUncached(encoding.tencoding, newEncoding.tencoding),
                    newEncoding);
        }
    }

    public boolean isAsciiOnly() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return TStringGuards.is7BitUncached(tstring, encoding);
    }

    public int get(int index) {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return tstring.readByteUncached(index, encoding.tencoding);
    }

    public int getByte(int index) {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return (byte) tstring.readByteUncached(index, encoding.tencoding);
    }

    public TStringWithEncoding substring(int byteOffset, int length) {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return new TStringWithEncoding(tstring.substringByteIndexUncached(byteOffset, length, encoding.tencoding, true),
                encoding);
    }

    public String toJavaString() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return tstring.toJavaStringUncached();
    }

    public String toJavaStringOrThrow() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        if (encoding == Encodings.BINARY && !isAsciiOnly()) {
            var byteArray = getInternalByteArray();
            for (int i = byteArray.getOffset(); i < byteArray.getEnd(); i++) {
                if (byteArray.getArray()[i] < 0) {
                    throw new CannotConvertBinaryRubyStringToJavaString(Byte.toUnsignedInt(byteArray.getArray()[i]));
                }
            }
            throw CompilerDirectives.shouldNotReachHere();
        } else {
            return toJavaString();
        }
    }

    // TODO: should return InternalByteArray and use that instead
    public byte[] getBytes() {
        CompilerAsserts.neverPartOfCompilation("Only behind @TruffleBoundary");
        return TStringUtils.getBytesOrCopy(tstring, encoding);
    }
}
