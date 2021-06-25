/*
 * Copyright (c) 2017, 2020 Oracle and/or its affiliates. All rights reserved. This
 * code is released under a tri EPL/GPL/LGPL license. You can use it,
 * redistribute it and/or modify it under the terms of the:
 *
 * Eclipse Public License version 2.0, or
 * GNU General Public License version 2, or
 * GNU Lesser General Public License version 2.1.
 */
package org.truffleruby.core.regexp;

import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.oracle.truffle.api.CompilerDirectives;
import com.oracle.truffle.api.dsl.ImportStatic;
import com.oracle.truffle.api.library.CachedLibrary;
import com.oracle.truffle.api.nodes.LoopNode;
import com.oracle.truffle.api.profiles.BranchProfile;
import com.oracle.truffle.api.profiles.LoopConditionProfile;
import org.jcodings.Encoding;
import org.jcodings.specific.ASCIIEncoding;
import org.jcodings.specific.USASCIIEncoding;
import org.jcodings.specific.UTF8Encoding;
import org.joni.Matcher;
import org.joni.Option;
import org.joni.Regex;
import org.joni.Region;
import org.truffleruby.RubyContext;
import org.truffleruby.RubyLanguage;
import org.truffleruby.builtins.CoreMethod;
import org.truffleruby.builtins.CoreMethodArrayArgumentsNode;
import org.truffleruby.builtins.CoreModule;
import org.truffleruby.builtins.Primitive;
import org.truffleruby.builtins.PrimitiveArrayArgumentsNode;
import org.truffleruby.collections.ConcurrentOperations;
import org.truffleruby.core.Hashing;
import org.truffleruby.core.array.ArrayBuilderNode;
import org.truffleruby.core.array.ArrayBuilderNode.BuilderState;
import org.truffleruby.core.array.RubyArray;
import org.truffleruby.core.encoding.EncodingNodes;
import org.truffleruby.core.encoding.RubyEncoding;
import org.truffleruby.core.encoding.StandardEncodings;
import org.truffleruby.core.kernel.KernelNodes.SameOrEqualNode;
import org.truffleruby.core.regexp.MatchDataNodes.MatchDataCreateNode;
import org.truffleruby.core.regexp.RegexpNodes.ToSNode;
import org.truffleruby.core.regexp.TruffleRegexpNodesFactory.MatchNodeGen;
import org.truffleruby.core.rope.CodeRange;
import org.truffleruby.core.rope.Rope;
import org.truffleruby.core.rope.RopeBuilder;
import org.truffleruby.core.rope.RopeNodes;
import org.truffleruby.core.rope.RopeOperations;
import org.truffleruby.core.string.RubyString;
import org.truffleruby.core.string.StringNodes;
import org.truffleruby.core.string.StringNodes.StringAppendPrimitiveNode;
import org.truffleruby.core.string.StringOperations;
import org.truffleruby.language.RubyContextNode;
import org.truffleruby.language.control.DeferredRaiseException;
import org.truffleruby.language.dispatch.DispatchNode;

import com.oracle.truffle.api.CompilerDirectives.TruffleBoundary;
import com.oracle.truffle.api.dsl.Cached;
import com.oracle.truffle.api.dsl.Specialization;
import com.oracle.truffle.api.frame.VirtualFrame;
import com.oracle.truffle.api.nodes.ExplodeLoop;
import com.oracle.truffle.api.nodes.Node;
import com.oracle.truffle.api.profiles.ConditionProfile;
import org.truffleruby.language.library.RubyStringLibrary;
import org.truffleruby.language.objects.AllocationTracing;
import org.truffleruby.parser.RubyDeferredWarnings;

@CoreModule("Truffle::RegexpOperations")
public class TruffleRegexpNodes {

    // rb_reg_prepare_enc ... mostly. Some of the error checks are performed by callers of this method.
    public abstract static class CheckEncodingNode extends RubyContextNode {

        @Child RopeNodes.CodeRangeNode codeRangeNode = RopeNodes.CodeRangeNode.create();
        @Child RubyStringLibrary stringLibrary = RubyStringLibrary.getFactory().createDispatched(2);

        public static CheckEncodingNode create() {
            return TruffleRegexpNodesFactory.CheckEncodingNodeGen.create();
        }

        public final Encoding executeCheckEncoding(RubyRegexp regexp, Object string) {
            return executeInternal(regexp, stringLibrary.getRope(string));
        }

        public abstract Encoding executeInternal(RubyRegexp regexp, Rope rope);

        @Specialization(guards = {
                "!isSameEncoding(regexp, rope)",
                "isUSASCII(regexp, rope)"
        })
        protected Encoding checkEncodingAsciiOnly(RubyRegexp regexp, Rope rope) {
            return USASCIIEncoding.INSTANCE;
        }

        @Specialization(guards = {
                "isSameEncoding(regexp, rope)"
        })
        protected Encoding checkEncodingSameEncoding(RubyRegexp regexp, Rope rope) {
            return regexp.regex.getEncoding();
        }

        @Specialization(guards = {
                "!isSameEncoding(regexp, rope)",
                "!isUSASCII(regexp, rope)",
                "isFixedEncoding(regexp, rope)",
        })
        protected Encoding checkEncodingFixedEncoding(RubyRegexp regexp, Rope rope) {
            return regexp.regex.getEncoding();
        }

        @Specialization(guards = {
                "!isSameEncoding(regexp, rope)",
                "!isUSASCII(regexp, rope)",
                "!isFixedEncoding(regexp, rope)"
        })
        protected Encoding fallback(RubyRegexp regexp, Rope rope) {
            return rope.encoding;
        }

        protected boolean isSameEncoding(RubyRegexp regexp, Rope rope) {
            return regexp.regex.getEncoding() == rope.encoding;
        }

        protected boolean isUSASCII(RubyRegexp regexp, Rope rope) {
            return regexp.regex.getEncoding() == USASCIIEncoding.INSTANCE &&
                    codeRangeNode.execute(rope) == CodeRange.CR_7BIT;
        }

        protected boolean isFixedEncoding(RubyRegexp regexp, Rope rope) {
            return regexp.options.isFixed() && rope.encoding.isAsciiCompatible();
        }

    }

    @TruffleBoundary
    private static Matcher getMatcher(Regex regex, byte[] stringBytes, int start) {
        return regex.matcher(stringBytes, start, stringBytes.length);
    }

    @TruffleBoundary
    private static Regex makeRegexpForEncoding(RubyContext context, RubyRegexp regexp, Encoding enc, Node currentNode) {
        final Encoding[] fixedEnc = new Encoding[]{ null };
        final Rope sourceRope = regexp.source;
        try {
            final RopeBuilder preprocessed = ClassicRegexp
                    .preprocess(sourceRope, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
            final RegexpOptions options = regexp.options;
            return ClassicRegexp.makeRegexp(context, null, preprocessed, options, enc, sourceRope, currentNode);
        } catch (DeferredRaiseException dre) {
            throw dre.getException(context);
        }
    }

    @CoreMethod(names = "union", onSingleton = true, required = 2, rest = true)
    public abstract static class RegexpUnionNode extends CoreMethodArrayArgumentsNode {

        @Child StringAppendPrimitiveNode appendNode = StringAppendPrimitiveNode.create();
        @Child ToSNode toSNode = ToSNode.create();
        @Child DispatchNode copyNode = DispatchNode.create();
        @Child private SameOrEqualNode sameOrEqualNode = SameOrEqualNode.create();
        @Child private StringNodes.MakeStringNode makeStringNode = StringNodes.MakeStringNode.create();
        @Child private RubyStringLibrary rubyStringLibrary = RubyStringLibrary.getFactory().createDispatched(2);

        @Specialization(
                guards = "argsMatch(frame, cachedArgs, args)",
                limit = "getDefaultCacheLimit()")
        protected Object executeFastUnion(VirtualFrame frame, RubyString str, Object sep, Object[] args,
                @Cached(value = "args", dimensions = 1) Object[] cachedArgs,
                @Cached BranchProfile errorProfile,
                @Cached("buildUnion(str, sep, args, errorProfile)") RubyRegexp union) {
            return copyNode.call(union, "clone");
        }

        @Specialization(replaces = "executeFastUnion")
        protected Object executeSlowUnion(RubyString str, Object sep, Object[] args,
                @Cached BranchProfile errorProfile) {
            return buildUnion(str, sep, args, errorProfile);
        }

        public RubyRegexp buildUnion(RubyString str, Object sep, Object[] args, BranchProfile errorProfile) {
            RubyString regexpString = null;
            for (int i = 0; i < args.length; i++) {
                if (regexpString == null) {
                    regexpString = appendNode.executeStringAppend(str, string(args[i]));
                } else {
                    regexpString = appendNode.executeStringAppend(regexpString, sep);
                    regexpString = appendNode.executeStringAppend(regexpString, string(args[i]));
                }
            }
            try {
                return createRegexp(regexpString.rope);
            } catch (DeferredRaiseException dre) {
                errorProfile.enter();
                throw dre.getException(getContext());
            }
        }

        public Object string(Object obj) {
            if (rubyStringLibrary.isRubyString(obj)) {
                final Rope rope = rubyStringLibrary.getRope(obj);
                return makeStringNode.fromRope(ClassicRegexp.quote19(rope));
            } else {
                return toSNode.execute((RubyRegexp) obj);
            }
        }

        @ExplodeLoop
        protected boolean argsMatch(VirtualFrame frame, Object[] cachedArgs, Object[] args) {
            if (cachedArgs.length != args.length) {
                return false;
            } else {
                for (int i = 0; i < cachedArgs.length; i++) {
                    if (!sameOrEqualNode.executeSameOrEqual(cachedArgs[i], args[i])) {
                        return false;
                    }
                }
                return true;
            }
        }

        @TruffleBoundary
        public RubyRegexp createRegexp(Rope pattern) throws DeferredRaiseException {
            final RegexpOptions regexpOptions = RegexpOptions.fromEmbeddedOptions(0);
            final Regex regex = compile(getLanguage(), null, pattern, regexpOptions, this);

            return new RubyRegexp(
                    regex,
                    (Rope) regex.getUserObject(),
                    regexpOptions,
                    new EncodingCache(),
                    new TRegexCache());
        }
    }

    @CoreMethod(names = "select_encoding", onSingleton = true, required = 2)
    public abstract static class SelectEncodingNode extends CoreMethodArrayArgumentsNode {

        public static SelectEncodingNode create() {
            return TruffleRegexpNodesFactory.SelectEncodingNodeFactory.create(null);
        }

        public abstract RubyEncoding executeSelectEncoding(RubyRegexp regexp, Object str);

        @Specialization(guards = "libString.isRubyString(str)")
        protected RubyEncoding selectEncoding(RubyRegexp regexp, Object str,
                @Cached EncodingNodes.GetRubyEncodingNode getRubyEncodingNode,
                @Cached CheckEncodingNode checkEncodingNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libString) {
            final Encoding encoding = checkEncodingNode.executeCheckEncoding(regexp, str);
            return getRubyEncodingNode.executeGetRubyEncoding(encoding);
        }
    }

    @ImportStatic(StandardEncodings.class)
    @CoreMethod(names = "tregex_compile", onSingleton = true, required = 3)
    public abstract static class TRegexCompileNode extends CoreMethodArrayArgumentsNode {

        public static TRegexCompileNode create() {
            return TruffleRegexpNodesFactory.TRegexCompileNodeFactory.create(null);
        }

        public abstract Object executeTRegexCompile(RubyRegexp regexp, boolean atStart, RubyEncoding encoding);

        @Specialization(guards = "encoding.encoding == US_ASCII")
        protected Object usASCII(RubyRegexp regexp, boolean atStart, RubyEncoding encoding) {
            final Object tregex = regexp.tregexCache.getUSASCIIRegex(atStart);
            if (tregex != null) {
                return tregex;
            } else {
                return regexp.tregexCache.compile(getContext(), regexp, atStart, encoding);
            }
        }

        @Specialization(guards = "encoding.encoding == ISO_8859_1")
        protected Object latin1(RubyRegexp regexp, boolean atStart, RubyEncoding encoding) {
            final Object tregex = regexp.tregexCache.getLatin1Regex(atStart);
            if (tregex != null) {
                return tregex;
            } else {
                return regexp.tregexCache.compile(getContext(), regexp, atStart, encoding);
            }
        }

        @Specialization(guards = "encoding.encoding == UTF_8")
        protected Object utf8(RubyRegexp regexp, boolean atStart, RubyEncoding encoding) {
            final Object tregex = regexp.tregexCache.getUTF8Regex(atStart);
            if (tregex != null) {
                return tregex;
            } else {
                return regexp.tregexCache.compile(getContext(), regexp, atStart, encoding);
            }
        }

        @Specialization(guards = "encoding.encoding == BINARY")
        protected Object binary(RubyRegexp regexp, boolean atStart, RubyEncoding encoding) {
            final Object tregex = regexp.tregexCache.getBinaryRegex(atStart);
            if (tregex != null) {
                return tregex;
            } else {
                return regexp.tregexCache.compile(getContext(), regexp, atStart, encoding);
            }
        }

        @Specialization(
                guards = {
                        "encoding.encoding != US_ASCII",
                        "encoding.encoding != ISO_8859_1",
                        "encoding.encoding != UTF_8",
                        "encoding.encoding != BINARY" })
        protected Object other(RubyRegexp regexp, boolean atStart, RubyEncoding encoding) {
            return nil;
        }

    }

    public abstract static class RegexpStatsNode extends CoreMethodArrayArgumentsNode {

        @TruffleBoundary
        protected <T> RubyArray fillinInstrumentData(Map<T, AtomicInteger> map, ArrayBuilderNode arrayBuilderNode,
                RubyContext context) {
            BuilderState state = arrayBuilderNode.start(COMPILED_REGEXPS.size() * 2);
            int n = 0;
            for (Entry<T, AtomicInteger> e : map.entrySet()) {
                Rope key = StringOperations.encodeRope(e.getKey().toString(), UTF8Encoding.INSTANCE);
                arrayBuilderNode.appendValue(state, n++, StringOperations.createString(context, getLanguage(), key));
                arrayBuilderNode.appendValue(state, n++, e.getValue().get());
            }
            return createArray(arrayBuilderNode.finish(state, n), n);
        }
    }

    @CoreMethod(names = "compilation_stats_array", onSingleton = true, required = 0)
    public abstract static class CompilationStatsArrayNode extends RegexpStatsNode {

        @Specialization
        protected Object buildStatsArray(
                @Cached ArrayBuilderNode arrayBuilderNode) {
            return fillinInstrumentData(COMPILED_REGEXPS, arrayBuilderNode, getContext());
        }
    }

    @CoreMethod(names = "match_stats_array", onSingleton = true, required = 0)
    public abstract static class MatchStatsArrayNode extends RegexpStatsNode {

        @Specialization
        protected Object buildStatsArray(
                @Cached ArrayBuilderNode arrayBuilderNode) {
            return fillinInstrumentData(MATCHED_REGEXPS, arrayBuilderNode, getContext());
        }
    }

    @Primitive(name = "matchdata_fixup_positions", lowerFixnum = { 1 })
    public abstract static class FixupMatchData extends PrimitiveArrayArgumentsNode {

        @Specialization
        protected RubyMatchData fixupMatchData(RubyMatchData matchData, int startPos) {
            RegexpNodes.fixupMatchDataForStart(matchData, startPos);
            return matchData;
        }
    }

    @Primitive(name = "regexp_initialized?")
    public abstract static class InitializedNode extends CoreMethodArrayArgumentsNode {

        @Specialization
        protected boolean initialized(RubyRegexp regexp) {
            return RegexpGuards.isInitialized(regexp);
        }
    }

    @Primitive(name = "regexp_match_in_region", lowerFixnum = { 2, 3, 5 })
    public abstract static class MatchInRegionNode extends PrimitiveArrayArgumentsNode {

        public static MatchInRegionNode create() {
            return TruffleRegexpNodesFactory.MatchInRegionNodeFactory.create(null);
        }

        public abstract Object executeMatchInRegion(RubyRegexp regexp, Object string, int fromPos, int toPos,
                boolean atStart, int startPos);

        /** Matches a regular expression against a string over the specified range of characters.
         *
         * @param regexp The regexp to match
         *
         * @param string The string to match against
         *
         * @param fromPos The position to search from
         *
         * @param toPos The position to search to (if less than from pos then this means search backwards)
         *
         * @param atStart Whether to only match at the beginning of the string, if false then the regexp can have any
         *            amount of prematch.
         *
         * @param startPos The position within the string which the matcher should consider the start. Setting this to
         *            the from position allows scanners to match starting part-way through a string while still setting
         *            atStart and thus forcing the match to be at the specific starting position. */
        @Specialization(guards = "libString.isRubyString(string)")
        protected Object matchInRegion(
                RubyRegexp regexp, Object string, int fromPos, int toPos, boolean atStart, int startPos,
                @Cached ConditionProfile encodingMismatchProfile,
                @Cached RopeNodes.BytesNode bytesNode,
                @Cached MatchNode matchNode,
                @Cached CheckEncodingNode checkEncodingNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libString) {
            final Rope rope = libString.getRope(string);
            final Encoding enc = checkEncodingNode.executeCheckEncoding(regexp, string);
            Regex regex = regexp.regex;

            if (encodingMismatchProfile.profile(regex.getEncoding() != enc)) {
                final EncodingCache encodingCache = regexp.cachedEncodings;
                regex = encodingCache.getOrCreate(enc, e -> makeRegexpForEncoding(getContext(), regexp, e, this));
            }

            final Matcher matcher = getMatcher(regex, bytesNode.execute(rope), startPos);
            return matchNode.execute(regexp, string, matcher, fromPos, toPos, atStart);
        }
    }

    @Primitive(name = "regexp_match_in_region_tregex", lowerFixnum = { 2, 3, 5 })
    public abstract static class MatchInRegionTRegexNode extends PrimitiveArrayArgumentsNode {

        @Child MatchInRegionNode fallbackMatchInRegionNode;
        @Child DispatchNode warnOnFallbackNode;

        @Child DispatchNode stringDupNode;
        @Child DispatchNode tRegexGroupCountNode;
        @Child DispatchNode tRegexGetStartNode;
        @Child DispatchNode tRegexGetEndNode;

        @Specialization(guards = "libString.isRubyString(string)")
        protected Object matchInRegionTRegex(
                RubyRegexp regexp, Object string, int fromPos, int toPos, boolean atStart, int startPos,
                @Cached ConditionProfile matchFoundProfile,
                @Cached ConditionProfile tRegexCouldNotCompileProfile,
                @Cached ConditionProfile tRegexIncompatibleProfile,
                @Cached LoopConditionProfile loopProfile,
                @Cached DispatchNode tRegexExecBytesNode,
                @Cached DispatchNode tRegexIsMatchNode,
                @Cached RopeNodes.BytesNode bytesNode,
                @Cached MatchDataCreateNode matchDataCreateNode,
                @Cached SelectEncodingNode selectEncodingNode,
                @Cached TRegexCompileNode tRegexCompileNode,
                @CachedLibrary(limit = "2") RubyStringLibrary libString) {
            final Rope rope = libString.getRope(string);

            if (tRegexIncompatibleProfile
                    .profile(toPos < fromPos || toPos != rope.byteLength() || startPos != 0 || fromPos < 0)) {
                return fallbackToJoni(regexp, string, fromPos, toPos, atStart, startPos);
            } else {
                final RubyEncoding encoding = selectEncodingNode.executeSelectEncoding(regexp, string);
                final Object compiledRegex = tRegexCompileNode.executeTRegexCompile(regexp, atStart, encoding);

                if (tRegexCouldNotCompileProfile.profile(compiledRegex == nil)) {
                    return fallbackToJoni(regexp, string, fromPos, toPos, atStart, startPos);
                }

                final byte[] bytes = bytesNode.execute(rope);
                final Object regexResult = tRegexExecBytesNode
                        .call(compiledRegex, "execBytes", getContext().getEnv().asGuestValue(bytes), fromPos);

                final boolean isMatch = (boolean) tRegexIsMatchNode.call(regexResult, "isMatch");

                if (matchFoundProfile.profile(isMatch)) {
                    final int groupCount = tRegexGroupCount(compiledRegex);
                    final int[] starts = new int[groupCount];
                    final int[] ends = new int[groupCount];

                    try {
                        loopProfile.profileCounted(groupCount);

                        for (int pos = 0; loopProfile.inject(pos < groupCount); pos++) {
                            starts[pos] = tRegexGetStart(regexResult, pos);
                            ends[pos] = tRegexGetEnd(regexResult, pos);
                        }
                    } finally {
                        LoopNode.reportLoopCount(this, groupCount);
                    }

                    return matchDataCreateNode.execute(regexp, dupString(string), starts, ends);
                } else {
                    return nil;
                }
            }
        }

        private Object fallbackToJoni(RubyRegexp regexp, Object string, int fromPos, int toPos, boolean atStart,
                int startPos) {
            if (getContext().getOptions().WARN_TRUFFLE_REGEX_FALLBACK) {
                if (warnOnFallbackNode == null) {
                    CompilerDirectives.transferToInterpreterAndInvalidate();
                    warnOnFallbackNode = insert(DispatchNode.create());
                }

                warnOnFallbackNode.call(
                        getContext().getCoreLibrary().truffleRegexpOperationsModule,
                        "warn_fallback",
                        regexp,
                        string,
                        fromPos,
                        toPos,
                        atStart,
                        startPos);
            }

            if (fallbackMatchInRegionNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                fallbackMatchInRegionNode = insert(MatchInRegionNode.create());
            }

            return fallbackMatchInRegionNode.executeMatchInRegion(regexp, string, fromPos, toPos, atStart, startPos);
        }

        private int tRegexGroupCount(Object compiledRegex) {
            if (tRegexGroupCountNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                tRegexGroupCountNode = insert(DispatchNode.create());
            }

            return (int) tRegexGroupCountNode.call(compiledRegex, "groupCount");
        }

        private int tRegexGetStart(Object regexResult, int pos) {
            if (tRegexGetStartNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                tRegexGetStartNode = insert(DispatchNode.create());
            }

            return (int) tRegexGetStartNode.call(regexResult, "getStart", pos);
        }

        private int tRegexGetEnd(Object regexResult, int pos) {
            if (tRegexGetEndNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                tRegexGetEndNode = insert(DispatchNode.create());
            }

            return (int) tRegexGetEndNode.call(regexResult, "getEnd", pos);
        }

        private Object dupString(Object string) {
            if (stringDupNode == null) {
                CompilerDirectives.transferToInterpreterAndInvalidate();
                stringDupNode = insert(DispatchNode.create());
            }

            return stringDupNode.call(string, "dup");
        }
    }

    public abstract static class MatchNode extends RubyContextNode {

        @Child private DispatchNode dupNode = DispatchNode.create();

        public static MatchNode create() {
            return MatchNodeGen.create();
        }

        public abstract Object execute(RubyRegexp regexp, Object string, Matcher matcher,
                int startPos, int range, boolean onlyMatchAtStart);

        // Creating a MatchData will store a copy of the source string. It's tempting to use a rope here, but a bit
        // inconvenient because we can't work with ropes directly in Ruby and some MatchData methods are nicely
        // implemented using the source string data. Likewise, we need to taint objects based on the source string's
        // taint state. We mustn't allow the source string's contents to change, however, so we must ensure that we have
        // a private copy of that string. Since the source string would otherwise be a reference to string held outside
        // the MatchData object, it would be possible for the source string to be modified externally.
        //
        // Ex. x = "abc"; x =~ /(.*)/; x.upcase!
        //
        // Without a private copy, the MatchData's source could be modified to be upcased when it should remain the
        // same as when the MatchData was created.
        @Specialization
        protected Object executeMatch(
                RubyRegexp regexp, Object string, Matcher matcher, int startPos, int range, boolean onlyMatchAtStart,
                @Cached ConditionProfile matchesProfile) {
            if (getContext().getOptions().REGEXP_INSTRUMENT_MATCH) {
                instrument(regexp, string, onlyMatchAtStart);
            }

            int match = runMatch(matcher, startPos, range, onlyMatchAtStart);

            if (matchesProfile.profile(match == -1)) {
                return nil;
            }

            assert match >= 0;

            final Region region = matcher.getEagerRegion();
            final RubyString dupedString = (RubyString) dupNode.call(string, "dup");
            RubyMatchData result = new RubyMatchData(
                    coreLibrary().matchDataClass,
                    getLanguage().matchDataShape,
                    regexp,
                    dupedString,
                    region,
                    null);
            AllocationTracing.trace(result, this);
            return result;
        }

        @TruffleBoundary
        private int runMatch(Matcher matcher, int startPos, int range, boolean onlyMatchAtStart) {
            // Keep status as RUN because MRI has an uninterruptible Regexp engine
            int[] result = new int[1];
            if (onlyMatchAtStart) {
                getContext().getThreadManager().runUntilResultKeepStatus(
                        this,
                        r -> r[0] = matcher.matchInterruptible(startPos, range, Option.DEFAULT),
                        result);
            } else {
                getContext().getThreadManager().runUntilResultKeepStatus(
                        this,
                        r -> r[0] = matcher.searchInterruptible(startPos, range, Option.DEFAULT),
                        result);
            }
            return result[0];
        }

        @TruffleBoundary
        protected void instrument(RubyRegexp regexp, Object string, boolean fromStart) {
            Rope source = regexp.source;
            Encoding enc = RubyStringLibrary.getUncached().getRope(string).getEncoding();
            RegexpOptions options = regexp.options;
            MatchInfo matchInfo = new MatchInfo(
                    new RegexpCacheKey(source, enc, options.toJoniOptions(), Hashing.NO_SEED),
                    fromStart);
            ConcurrentOperations.getOrCompute(MATCHED_REGEXPS, matchInfo, x -> new AtomicInteger()).incrementAndGet();
        }
    }

    private static final class MatchInfo {

        private final RegexpCacheKey regexpInfo;
        private final boolean matchStart;

        MatchInfo(RegexpCacheKey regexpInfo, boolean matchStart) {
            assert regexpInfo != null;
            this.regexpInfo = regexpInfo;
            this.matchStart = matchStart;
        }

        @Override
        public int hashCode() {
            return Objects.hash(regexpInfo, matchStart);
        }

        @Override
        public boolean equals(Object obj) {
            if (this == obj) {
                return true;
            }

            if (!(obj instanceof MatchInfo)) {
                return false;
            }

            MatchInfo other = (MatchInfo) obj;
            return matchStart == other.matchStart && regexpInfo.equals(other.regexpInfo);
        }

        @Override
        public String toString() {
            return String.format("Match (%s, fromStart = %s)", regexpInfo, matchStart);
        }
    }

    private static ConcurrentHashMap<RegexpCacheKey, AtomicInteger> COMPILED_REGEXPS = new ConcurrentHashMap<>();
    private static ConcurrentHashMap<MatchInfo, AtomicInteger> MATCHED_REGEXPS = new ConcurrentHashMap<>();

    /** WARNING: computeRegexpEncoding() mutates options, so the caller should make sure it's a copy */
    @TruffleBoundary
    public static Regex compile(RubyLanguage language, RubyDeferredWarnings rubyDeferredWarnings, Rope bytes,
            RegexpOptions options, Node currentNode) throws DeferredRaiseException {
        if (options.isEncodingNone()) {
            bytes = RopeOperations.withEncoding(bytes, ASCIIEncoding.INSTANCE);
        }
        Encoding enc = bytes.getEncoding();
        Encoding[] fixedEnc = new Encoding[]{ null };
        RopeBuilder unescaped = ClassicRegexp
                .preprocess(bytes, enc, fixedEnc, RegexpSupport.ErrorMode.RAISE);
        enc = ClassicRegexp.computeRegexpEncoding(options, enc, fixedEnc);

        Regex regexp = ClassicRegexp
                .makeRegexp(null, rubyDeferredWarnings, unescaped, options, enc, bytes, currentNode);
        regexp.setUserObject(RopeOperations.withEncoding(bytes, enc));

        if (language.options.REGEXP_INSTRUMENT_CREATION) {
            final RegexpCacheKey key = new RegexpCacheKey(bytes, enc, options.toJoniOptions(), Hashing.NO_SEED);
            ConcurrentOperations.getOrCompute(COMPILED_REGEXPS, key, x -> new AtomicInteger()).incrementAndGet();
        }

        return regexp;
    }

}
