/*
 * Copyright (c) 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */

package org.truffleruby.core.array;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.GenerateUncached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.library.ExportLibrary;
import com.oracle.truffle.api.library.ExportMessage;

import org.truffleruby.core.array.ArrayStoreLibrary.ArrayAllocator;

@ExportLibrary(value = ArrayStoreLibrary.class, receiverType = int[].class)
@GenerateUncached
public class IntegerArrayStore {

    @ExportMessage
    public static int read(int[] store, int index) {
        return store[index];
    }

    @ExportMessage
    public static boolean acceptsValue(int[] store, Object value) {
        return value instanceof Integer;
    }

    @ExportMessage
    static class AcceptsAllValues {

        @Specialization
        protected static boolean acceptsZeroValues(int[] store, ZeroLengthArrayStore otherStore) {
            return true;
        }

        @Specialization
        protected static boolean acceptsIntValues(int[] store, int[] otherStore) {
            return true;
        }

        @Specialization
        protected static boolean acceptsDelegateValues(int[] store, DelegatedArrayStorage otherStore,
                @CachedLibrary("store") ArrayStoreLibrary stores) {
            return stores.acceptsAllValues(store, otherStore.storage);
        }

        @Specialization
        protected static boolean acceptsOtherValues(int[] store, Object otherStore) {
            return false;
        }
    }

    @ExportMessage
    public static boolean isMutable(int[] store) {
        return true;
    }

    @ExportMessage
    public static boolean isPrimitive(int[] store) {
        return true;
    }

    @ExportMessage
    public static String toString(int[] store) {
        return "int[]";
    }

    @ExportMessage
    static class Write {

        @Specialization
        protected static void writeInt(int[] store, int index, int value) {
            store[index] = value;
        }
    }

    @ExportMessage
    public static int capacity(int[] store) {
        return store.length;
    }

    @ExportMessage
    public static int[] expand(int[] store, int newCapacity) {
        int[] newStore = new int[newCapacity];
        System.arraycopy(store, 0, newStore, 0, store.length);
        return newStore;
    }

    @ExportMessage
    static class CopyContents {

        @Specialization
        protected static void copyContents(int[] srcStore, int srcStart, int[] destStore, int destStart, int length) {
            System.arraycopy(srcStore, srcStart, destStore, destStart, length);
        }

        @Specialization
        protected static void copyContents(int[] srcStore, int srcStart, Object destStore, int destStart, int length,
                @CachedLibrary(limit = "5") ArrayStoreLibrary destStores) {
            for (int i = srcStart; i < length; i++) {
                destStores.write(destStore, destStart + i, srcStore[(srcStart + i)]);
            }
        }
    }

    @ExportMessage
    public static int[] copyStore(int[] store, int length) {
        return ArrayUtils.grow(store, length);
    }

    @ExportMessage
    @TruffleBoundary
    public static void sort(int[] store, int size) {
        Arrays.sort(store, 0, size);
    }

    @ExportMessage
    public static Iterable<Object> getIterable(int[] store, int from, int length) {
        return () -> new Iterator<Object>() {

            private int n = from;

            @Override
            public boolean hasNext() {
                return n < from + length;
            }

            @Override
            public Object next() throws NoSuchElementException {
                if (n >= from + length) {
                    throw new NoSuchElementException();
                }

                final Object object = store[n];
                n++;
                return object;
            }

            @Override
            public void remove() {
                throw new UnsupportedOperationException("remove");
            }

        };
    }

    @ExportMessage
    static class GeneralizeForValue {

        @Specialization
        protected static ArrayAllocator generalize(int[] store, int newValue) {
            return IntegerArrayStore.INTEGER_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, long newValue) {
            return LongArrayStore.LONG_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, double newValue) {
            return ObjectArrayStore.OBJECT_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, Object newValue) {
            return ObjectArrayStore.OBJECT_ARRAY_ALLOCATOR;
        }
    }

    @ExportMessage
    static class GeneralizeForStore {

        @Specialization
        protected static ArrayAllocator generalize(int[] store, int[] newStore) {
            return IntegerArrayStore.INTEGER_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, long[] newStore) {
            return LongArrayStore.LONG_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, double[] newStore) {
            return ObjectArrayStore.OBJECT_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, Object[] newStore) {
            return ObjectArrayStore.OBJECT_ARRAY_ALLOCATOR;
        }

        @Specialization
        protected static ArrayAllocator generalize(int[] store, Object newStore,
                @CachedLibrary(limit = "3") ArrayStoreLibrary newStores) {
            return newStores.generalizeForStore(newStore, store);
        }
    }

    @ExportMessage
    public static ArrayAllocator allocator(int[] store) {
        return INTEGER_ARRAY_ALLOCATOR;
    }

    public static final ArrayAllocator INTEGER_ARRAY_ALLOCATOR = new IntegerArrayAllocator();

    private static class IntegerArrayAllocator extends ArrayAllocator {

        @Override
        public int[] allocate(int capacity) {
            return new int[capacity];
        }

        @Override
        public boolean accepts(Object value) {
            return value instanceof Integer;
        }

        @Override
        public boolean specializesFor(Object value) {
            return value instanceof Integer;
        }

        @Override
        public boolean isDefaultValue(Object value) {
            return (int) value == 0;
        }
    }
}
