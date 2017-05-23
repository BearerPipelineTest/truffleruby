/*
 * Copyright (c) 2015, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.truffleruby.core.rubinius;

import com.oracle.truffle.api.dsl.Specialization;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.platform.RubiniusTypes;

public abstract class NativeFunctionNodes {

    @Primitive(name = "nativefunction_type_size", needsSelf = false)
    public static abstract class NativeFunctionTypeSizePrimitiveNode extends PrimitiveArrayArgumentsNode {

        @Specialization
        public long typeSize(int type) {
            switch (type) {
                case RubiniusTypes.TYPE_CHAR:
                case RubiniusTypes.TYPE_UCHAR:
                    return 1;

                case RubiniusTypes.TYPE_SHORT:
                case RubiniusTypes.TYPE_USHORT:
                    return 2;

                case RubiniusTypes.TYPE_INT:
                case RubiniusTypes.TYPE_UINT:
                    return 4;

                case RubiniusTypes.TYPE_LONG:
                case RubiniusTypes.TYPE_ULONG:
                case RubiniusTypes.TYPE_LL:
                case RubiniusTypes.TYPE_ULL:
                    return 8;

                case RubiniusTypes.TYPE_FLOAT:
                    return 4;

                case RubiniusTypes.TYPE_DOUBLE:
                    return 8;

                case RubiniusTypes.TYPE_PTR:
                case RubiniusTypes.TYPE_STRPTR:
                case RubiniusTypes.TYPE_STRING:
                case RubiniusTypes.TYPE_CHARARR:
                    return 8;

                case RubiniusTypes.TYPE_BOOL:
                case RubiniusTypes.TYPE_VOID:
                case RubiniusTypes.TYPE_ENUM:
                case RubiniusTypes.TYPE_VARARGS:
                default:
                    throw new UnsupportedOperationException("no type size for: "+ type);
            }
        }

        @Specialization(guards = "!isInteger(type)")
        public Object fallback(Object type) {
            return null; // Primitive failure
        }

    }

}
