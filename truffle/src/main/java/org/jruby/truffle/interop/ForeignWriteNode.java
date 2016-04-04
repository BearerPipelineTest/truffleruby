/*
 * Copyright (c) 2013, 2016 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 1.0
 * GNU General Public License version 2
 * GNU Lesser General Public License version 2.1
 */
package org.jruby.truffle.interop;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.dsl.NodeChild;
import com.oracle.truffle.api.dsl.NodeChildren;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.interop.AcceptMessage;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.nodes.Node.Child;
import com.oracle.truffle.api.object.DynamicObject;
import com.oracle.truffle.api.source.SourceSection;
import org.jruby.truffle.RubyContext;
import org.jruby.truffle.RubyLanguage;
import org.jruby.truffle.core.rope.Rope;
import org.jruby.truffle.core.rope.RopeOperations;
import org.jruby.truffle.core.string.StringCachingGuards;
import org.jruby.truffle.language.RubyNode;
import org.jruby.truffle.language.RubyObjectType;
import org.jruby.truffle.language.dispatch.CallDispatchHeadNode;
import org.jruby.truffle.language.dispatch.DispatchHeadNodeFactory;
import org.jruby.truffle.language.dispatch.DoesRespondDispatchHeadNode;
import org.jruby.truffle.language.objects.WriteObjectFieldNode;
import org.jruby.truffle.language.objects.WriteObjectFieldNodeGen;

@AcceptMessage(value = "WRITE", receiverType = RubyObjectType.class, language = RubyLanguage.class)
public final class ForeignWriteNode extends ForeignWriteBaseNode {

    @Child private Node findContextNode;
    @Child private StringCachingHelperNode helperNode;

    @Override
    public Object access(VirtualFrame frame, DynamicObject object, Object name, Object value) {
        return getHelperNode().executeStringCachingHelper(frame, object, name, value);
    }

    private StringCachingHelperNode getHelperNode() {
        if (helperNode == null) {
            CompilerDirectives.transferToInterpreter();
            findContextNode = insert(RubyLanguage.INSTANCE.unprotectedCreateFindContextNode());
            final RubyContext context = RubyLanguage.INSTANCE.unprotectedFindContext(findContextNode);
            helperNode = insert(ForeignWriteNodeFactory.StringCachingHelperNodeGen.create(
                    context, null, null, null, null));
        }

        return helperNode;
    }

    @ImportStatic(StringCachingGuards.class)
    @NodeChildren({
            @NodeChild("receiver"),
            @NodeChild("label"),
            @NodeChild("value")
    })
    protected static abstract class StringCachingHelperNode extends RubyNode {

        public StringCachingHelperNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public abstract Object executeStringCachingHelper(VirtualFrame frame, DynamicObject receiver,
                                                          Object label, Object value);

        @Specialization(
                guards = {
                        "isRubyString(label)",
                        "ropesEqual(label, cachedRope)"
                },
                limit = "getCacheLimit()"
        )
        public Object cacheStringAndForward(VirtualFrame frame,
                                            DynamicObject receiver,
                                            DynamicObject label,
                                            Object value,
                                            @Cached("privatizeRope(label)") Rope cachedRope,
                                            @Cached("ropeToString(cachedRope)") String cachedString,
                                            @Cached("startsWithAt(cachedString)") boolean cachedStartsWithAt,
                                            @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            return nextHelper.executeStringCachedHelper(frame, receiver, label, cachedString, cachedStartsWithAt, value);
        }

        @Specialization(
                guards = "isRubyString(label)",
                contains = "cacheStringAndForward"
        )
        public Object uncachedStringAndForward(VirtualFrame frame,
                                               DynamicObject receiver,
                                               DynamicObject label,
                                               Object value,
                                               @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            final String labelString = objectToString(label);
            return nextHelper.executeStringCachedHelper(frame, receiver, label, labelString,
                    startsWithAt(labelString), value);
        }

        @Specialization(
                guards = {
                        "isRubySymbol(label)",
                        "label == cachedLabel"
                },
                limit = "getCacheLimit()"
        )
        public Object cacheSymbolAndForward(VirtualFrame frame,
                                            DynamicObject receiver,
                                            DynamicObject label,
                                            Object value,
                                            @Cached("label") DynamicObject cachedLabel,
                                            @Cached("objectToString(cachedLabel)") String cachedString,
                                            @Cached("startsWithAt(cachedString)") boolean cachedStartsWithAt,
                                            @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            return nextHelper.executeStringCachedHelper(frame, receiver, cachedLabel, cachedString,
                    cachedStartsWithAt, value);
        }

        @Specialization(
                guards = "isRubySymbol(label)",
                contains = "cacheSymbolAndForward"
        )
        public Object uncachedSymbolAndForward(VirtualFrame frame,
                                               DynamicObject receiver,
                                               DynamicObject label,
                                               Object value,
                                               @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            final String labelString = objectToString(label);
            return nextHelper.executeStringCachedHelper(frame, receiver, label, labelString,
                    startsWithAt(labelString), value);
        }

        @Specialization(
                guards = "label == cachedLabel",
                limit = "getCacheLimit()"
        )
        public Object cacheJavaStringAndForward(VirtualFrame frame,
                                                DynamicObject receiver,
                                                String label,
                                                Object value,
                                                @Cached("label") String cachedLabel,
                                                @Cached("startsWithAt(cachedLabel)") boolean cachedStartsWithAt,
                                                @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            return nextHelper.executeStringCachedHelper(frame, receiver, cachedLabel, cachedLabel,
                    cachedStartsWithAt, value);
        }

        @Specialization(contains = "cacheJavaStringAndForward")
        public Object uncachedJavaStringAndForward(VirtualFrame frame,
                                                   DynamicObject receiver,
                                                   String label,
                                                   Object value,
                                                   @Cached("createNextHelper()") StringCachedHelperNode nextHelper) {
            return nextHelper.executeStringCachedHelper(frame, receiver, label, label, startsWithAt(label), value);
        }

        protected StringCachedHelperNode createNextHelper() {
            return ForeignWriteNodeFactory.StringCachedHelperNodeGen.create(
                    getContext(), null, null, null, null, null, null);
        }

        @CompilerDirectives.TruffleBoundary
        protected String objectToString(DynamicObject string) {
            return string.toString();
        }

        protected String ropeToString(Rope rope) {
            return RopeOperations.decodeRope(getContext().getJRubyRuntime(), rope);
        }

        @CompilerDirectives.TruffleBoundary
        protected boolean startsWithAt(String label) {
            return !label.isEmpty() && label.charAt(0) == '@';
        }

        protected int getCacheLimit() {
            return getContext().getOptions().INTEROP_WRITE_CACHE;
        }

    }

    @NodeChildren({
            @NodeChild("receiver"),
            @NodeChild("label"),
            @NodeChild("stringLabel"),
            @NodeChild("startsAt"),
            @NodeChild("value")
    })
    protected static abstract class StringCachedHelperNode extends RubyNode {

        @Child private DoesRespondDispatchHeadNode definedNode;
        @Child private DoesRespondDispatchHeadNode indexDefinedNode;
        @Child private CallDispatchHeadNode callNode;

        protected final static String INDEX_METHOD_NAME = "[]=";

        public StringCachedHelperNode(RubyContext context, SourceSection sourceSection) {
            super(context, sourceSection);
        }

        public abstract Object executeStringCachedHelper(VirtualFrame frame, DynamicObject receiver, Object label,
                                                         String stringLabel, boolean startsAt, Object value);

        @Specialization(guards = "startsAt(startsAt)")
        public Object readInstanceVariable(DynamicObject receiver,
                                           Object label,
                                           String stringLabel,
                                           boolean startsAt,
                                           Object value,
                                           @Cached("createWriteObjectFieldNode(stringLabel)") WriteObjectFieldNode writeObjectFieldNode) {
            writeObjectFieldNode.execute(receiver, value);
            return value;
        }

        protected boolean startsAt(boolean startsAt) {
            return startsAt;
        }

        protected WriteObjectFieldNode createWriteObjectFieldNode(String label) {
            return WriteObjectFieldNodeGen.create(getContext(), label);
        }

        @Specialization(
                guards = {
                        "notStartsAt(startsAt)",
                        "methodDefined(frame, receiver, writeMethodName, getDefinedNode())"
                }
        )
        public Object callMethod(VirtualFrame frame,
                                 DynamicObject receiver,
                                 Object label,
                                 String stringLabel,
                                 boolean startsAt,
                                 Object value,
                                 @Cached("createWriteMethodName(stringLabel)") String writeMethodName) {
            return getCallNode().call(frame, receiver, writeMethodName, null, value);
        }

        protected String createWriteMethodName(String label) {
            return label + "=";
        }

        @Specialization(
                guards = {
                        "notStartsAt(startsAt)",
                        "!methodDefined(frame, receiver, writeMethodName, getDefinedNode())",
                        "methodDefined(frame, receiver, INDEX_METHOD_NAME, getIndexDefinedNode())"
                }
        )
        public Object index(VirtualFrame frame,
                            DynamicObject receiver,
                            Object label,
                            String stringLabel,
                            boolean startsAt,
                            Object value,
                            @Cached("createWriteMethodName(stringLabel)") String writeMethodName) {
            return getCallNode().call(frame, receiver, "[]", null, label, value);
        }

        protected boolean notStartsAt(boolean startsAt) {
            return !startsAt;
        }

        protected DoesRespondDispatchHeadNode getDefinedNode() {
            if (definedNode == null) {
                CompilerDirectives.transferToInterpreter();
                definedNode = insert(new DoesRespondDispatchHeadNode(getContext(), true));
            }

            return definedNode;
        }

        protected DoesRespondDispatchHeadNode getIndexDefinedNode() {
            if (indexDefinedNode == null) {
                CompilerDirectives.transferToInterpreter();
                indexDefinedNode = insert(new DoesRespondDispatchHeadNode(getContext(), true));
            }

            return indexDefinedNode;
        }

        protected boolean methodDefined(VirtualFrame frame, DynamicObject receiver, String stringLabel,
                                        DoesRespondDispatchHeadNode definedNode) {
            return definedNode.doesRespondTo(frame, stringLabel, receiver);
        }

        protected CallDispatchHeadNode getCallNode() {
            if (callNode == null) {
                CompilerDirectives.transferToInterpreter();
                callNode = insert(DispatchHeadNodeFactory.createMethodCall(getContext(), true));
            }

            return callNode;
        }

    }

}
