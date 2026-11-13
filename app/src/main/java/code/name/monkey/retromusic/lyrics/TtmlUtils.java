/*
 * TTML lyric reader for RetroMusic.
 *
 * Supports line-synchronized TTML and Apple Music word-timed TTML by
 * projecting paragraph timestamps into the existing lyric view while
 * retaining word timing, singer identity, and background-vocal roles.
 */
package code.name.monkey.retromusic.lyrics;

import android.text.TextUtils;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Layout;
import android.text.style.AlignmentSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parses TTML lyric documents without requiring a network connection. */
public final class TtmlUtils {
    private static final int CACHE_LIMIT = 8;
    private static final Map<String, ParsedDocument> CACHE =
        new LinkedHashMap<String, ParsedDocument>(CACHE_LIMIT, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ParsedDocument> eldest) {
                return size() > CACHE_LIMIT;
            }
        };

    private TtmlUtils() {}

    public static boolean isTtml(String content) {
        if (TextUtils.isEmpty(content)) return false;
        String value = content.trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            } catch (Exception ignored) {
                // Parser implementations may not expose this optional feature.
            }
            parser.setInput(new StringReader(value));
            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG) {
                    return "tt".equals(parser.getName());
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
            // Invalid XML is not a TTML lyric document.
        }
        return false;
    }

    public static boolean hasValidTtml(String content) {
        return isTtml(content) && !parseDocument(content).entries.isEmpty();
    }

    public static List<LrcEntry> parseTtml(String content) {
        return copyEntries(parseDocument(content).entries);
    }

    /** Return independent view entries; translations/layouts must never mutate cached parse data. */
    private static List<LrcEntry> copyEntries(List<LrcEntry> source) {
        List<LrcEntry> copies = new ArrayList<>(source.size());
        for (LrcEntry original : source) {
            LrcEntry copy = new LrcEntry(original.getTime(), original.getText(), original.getSecondText());
            copy.setSpeaker(original.getSpeaker());
            copy.setTtml(original.isTtml());
            copy.setBackgroundOnly(original.isBackgroundOnly());
            copy.setTtmlLineEndTime(original.getWordEndTime() > original.getTime()
                ? original.getWordEndTime() : -1L);
            List<LrcEntry.WordTiming> timings = new ArrayList<>();
            for (LrcEntry.WordTiming word : original.getWordTimings()) {
                timings.add(new LrcEntry.WordTiming(
                    word.start, word.end, word.startIndex, word.endIndex,
                    word.background, word.continuesWord, word.sustained
                ));
            }
            copy.setWordTimings(timings);
            copies.add(copy);
        }
        return copies;
    }

    private static List<LrcEntry> parseTtmlUncached(String content) {
        List<LrcEntry> entries = new ArrayList<>();
        if (!isTtml(content)) return entries;

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            String xml = content.trim();
            if (xml.startsWith("\uFEFF")) xml = xml.substring(1).trim();
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            } catch (Exception ignored) {
                // Optional parser feature; external entities are never resolved by this reader.
            }
            parser.setInput(new StringReader(xml));

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "p".equals(parser.getName())) {
                    long parsedParagraphStart = parseTime(attribute(parser, "begin"));
                    long parsedParagraphEnd = parseTime(attribute(parser, "end"));
                    long paragraphStart = Math.max(0L, parsedParagraphStart);
                    String speaker = attribute(parser, "agent");
                    boolean paragraphBackground = "x-bg".equals(attribute(parser, "role"));

                    StringBuilder text = new StringBuilder();
                    List<LrcEntry.WordTiming> words = new ArrayList<>();
                    java.util.ArrayDeque<SpanFrame> spans = new java.util.ArrayDeque<>();
                    int paragraphDepth = 1;

                    while (paragraphDepth > 0) {
                        event = parser.next();
                        if (event == XmlPullParser.END_DOCUMENT) break;

                        if (event == XmlPullParser.START_TAG) {
                            paragraphDepth++;
                            String tag = parser.getName();
                            if ("span".equals(tag)) {
                                long relativeBegin = parseTime(attribute(parser, "begin"));
                                long parentOffset = spans.isEmpty() ? 0L : spans.peek().timeOffset;
                                long timeOffset = parentOffset + Math.max(0L, relativeBegin);
                                spans.push(new SpanFrame(
                                    text.length(),
                                    relativeBegin,
                                    parseTime(attribute(parser, "end")),
                                    parseTime(attribute(parser, "dur")),
                                    parentOffset,
                                    timeOffset,
                                    paragraphBackground ||
                                        "x-bg".equals(attribute(parser, "role")) ||
                                        (!spans.isEmpty() && spans.peek().background)
                                ));
                            } else if ("br".equals(tag)) {
                                text.append('\n');
                            }
                        } else if (event == XmlPullParser.TEXT ||
                                event == XmlPullParser.CDSECT ||
                                event == XmlPullParser.ENTITY_REF) {
                            String rawText = parser.getText();
                            if (rawText != null &&
                                (!rawText.trim().isEmpty() || " ".equals(rawText) || "\u00A0".equals(rawText))) {
                                text.append(rawText.replace('\u00A0', ' '));
                            }
                        } else if (event == XmlPullParser.END_TAG) {
                            if ("span".equals(parser.getName()) && !spans.isEmpty()) {
                                SpanFrame span = spans.pop();
                                int spanEnd = text.length();
                                if (span.begin >= 0 && !span.hasTimedChild &&
                                    spanEnd > span.startIndex) {
                                    long wordStart = paragraphStart + span.timeOffset;
                                    long wordEnd = span.end >= 0
                                        ? paragraphStart + span.parentOffset + span.end
                                        : (span.duration >= 0 ? wordStart + span.duration : -1L);
                                    String segmentText = text.substring(span.startIndex, spanEnd);
                                    java.util.regex.Matcher tokenMatcher =
                                        java.util.regex.Pattern.compile("[^\\s\\u00A0\\-\\u2010\\u2013\\u2014]+")
                                            .matcher(segmentText);
                                    List<int[]> tokens = new ArrayList<>();
                                    while (tokenMatcher.find()) {
                                        tokens.add(new int[] { tokenMatcher.start(), tokenMatcher.end() });
                                    }
                                    long segmentDuration = Math.max(0L, wordEnd - wordStart);
                                    for (int tokenIndex = 0; tokenIndex < tokens.size(); tokenIndex++) {
                                        int[] token = tokens.get(tokenIndex);
                                        long tokenStart = wordStart;
                                        long tokenEnd = wordEnd;
                                        if (tokens.size() > 1 && segmentDuration > 0L) {
                                            tokenStart = wordStart + (segmentDuration * tokenIndex / tokens.size());
                                            tokenEnd = wordStart + (segmentDuration * (tokenIndex + 1) / tokens.size());
                                        }
                                        boolean continuesWord = tokens.size() == 1 &&
                                            !segmentText.isEmpty() &&
                                            !Character.isWhitespace(segmentText.charAt(segmentText.length() - 1)) &&
                                            "-–—‐".indexOf(segmentText.charAt(segmentText.length() - 1)) < 0;
                                        words.add(new LrcEntry.WordTiming(
                                            tokenStart, tokenEnd,
                                            span.startIndex + token[0], span.startIndex + token[1],
                                            span.background, continuesWord, false
                                        ));
                                    }
                                    if (!spans.isEmpty()) spans.peek().hasTimedChild = true;
                                }
                            }
                            paragraphDepth--;
                        }
                    }

                    String line = text.toString().replace('\u00A0', ' ').trim();
                    if (!line.isEmpty() && (parsedParagraphStart >= 0 || !words.isEmpty())) {
                        int trimStart = 0;
                        while (trimStart < text.length() &&
                            Character.isWhitespace(text.charAt(trimStart))) trimStart++;
                        int trimEnd = text.length();
                        while (trimEnd > trimStart &&
                            Character.isWhitespace(text.charAt(trimEnd - 1))) trimEnd--;

                        List<LrcEntry.WordTiming> normalized = new ArrayList<>();
                        for (LrcEntry.WordTiming word : words) {
                            int from = Math.max(0, word.startIndex - trimStart);
                            int to = Math.min(line.length(), word.endIndex - trimStart);
                            if (to > from) {
                                normalized.add(new LrcEntry.WordTiming(
                                    word.start, word.end, from, to, word.background,
                                    word.continuesWord, false
                                ));
                            }
                        }
                        Collections.sort(normalized, (a, b) -> Long.compare(a.start, b.start));
                        // Apple TTML may encode backing vocals with parentheses instead of ttm:role="x-bg".
                        boolean insideBackgroundParentheses = false;
                        List<LrcEntry.WordTiming> parenthesisMarked = new ArrayList<>();
                        for (LrcEntry.WordTiming word : normalized) {
                            int from = Math.max(0, Math.min(word.startIndex, line.length()));
                            int to = Math.max(from, Math.min(word.endIndex, line.length()));
                            String token = line.substring(from, to);
                            if (token.contains("(")) insideBackgroundParentheses = true;
                            boolean background = word.background || insideBackgroundParentheses;
                            parenthesisMarked.add(new LrcEntry.WordTiming(
                                word.start, word.end, word.startIndex, word.endIndex,
                                background, word.continuesWord, word.sustained
                            ));
                            if (token.contains(")")) insideBackgroundParentheses = false;
                        }
                        normalized = parenthesisMarked;
                        for (int i = 0; i < normalized.size(); i++) {
                            LrcEntry.WordTiming word = normalized.get(i);
                            if (word.end <= word.start) {
                                word.end = i + 1 < normalized.size()
                                    ? normalized.get(i + 1).start
                                    : word.start + 1000L;
                            }
                            if (i + 1 < normalized.size()) {
                                long gap = normalized.get(i + 1).start - word.end;
                                if (gap > 50L) word.sustained = true;
                            }
                        }

                        long lineStart = parsedParagraphStart >= 0
                            ? paragraphStart
                            : (!normalized.isEmpty() ? normalized.get(0).start : 0L);
                        boolean hasBackground = paragraphBackground;
                        for (LrcEntry.WordTiming word : normalized) {
                            if (word.background) {
                                hasBackground = true;
                                break;
                            }
                        }

                        LrcEntry entry;
                        if (hasBackground) {
                            LinePart mainPart = buildLinePart(line, normalized, false);
                            LinePart backgroundPart = buildLinePart(line, normalized, true);
                            if (mainPart.text.isEmpty()) {
                                String backgroundText = backgroundPart.text.isEmpty() ? line : backgroundPart.text;
                                entry = new LrcEntry(lineStart, backgroundText);
                                entry.setBackgroundOnly(true);
                                entry.setWordTimings(backgroundPart.words);
                            } else {
                                entry = new LrcEntry(lineStart, mainPart.text);
                                entry.setSecondText(backgroundPart.text);
                                List<LrcEntry.WordTiming> combined = new ArrayList<>(mainPart.words);
                                int backgroundOffset = mainPart.text.length() + 1;
                                for (LrcEntry.WordTiming word : backgroundPart.words) {
                                    combined.add(new LrcEntry.WordTiming(
                                        word.start, word.end,
                                        word.startIndex + backgroundOffset,
                                        word.endIndex + backgroundOffset,
                                        true, word.continuesWord, word.sustained
                                    ));
                                }
                                entry.setWordTimings(combined);
                            }
                        } else {
                            entry = new LrcEntry(lineStart, line);
                            entry.setWordTimings(normalized);
                        }
                        entry.setSpeaker(speaker);
                        entry.setTtml(true);
                        entry.setTtmlLineEndTime(parsedParagraphEnd);
                        entries.add(entry);
                    }
                }
                event = parser.next();
            }
        } catch (Exception ignored) {
            entries.clear();
        }
        Collections.sort(entries);
        return entries;
    }

    /** Parsed TTML document reusable by both the full lyrics page and Now Playing. */
    public static final class ParsedDocument {
        private final List<LrcEntry> entries;

        private ParsedDocument(List<LrcEntry> entries) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
        }

        private long findLineTime(long timeMs) {
            long lineTime = -1L;
            for (LrcEntry entry : entries) {
                if (entry.getTime() > timeMs) break;
                lineTime = entry.getTime();
            }
            return lineTime >= 0 ? lineTime : (entries.isEmpty() ? -1L : entries.get(0).getTime());
        }

        public CharSequence getKaraokeText(long timeMs, int activeColor, int inactiveColor) {
            long lineTime = findLineTime(timeMs);
            if (lineTime < 0) return "";
            SpannableStringBuilder result = new SpannableStringBuilder();
            for (LrcEntry entry : entries) {
                if (entry.getTime() != lineTime) continue;
                if (result.length() > 0) result.append('\n');
                int lineStart = result.length();
                result.append(entry.getShowText());
                int lineEnd = result.length();
                Layout.Alignment alignment = "v2".equalsIgnoreCase(entry.getSpeaker())
                    ? Layout.Alignment.ALIGN_OPPOSITE : Layout.Alignment.ALIGN_NORMAL;
                result.setSpan(new AlignmentSpan.Standard(alignment), lineStart, lineEnd,
                    SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                if (entry.isBackgroundOnly() && entry.getWordTimings().isEmpty() && lineEnd > lineStart) {
                    int alpha = Math.min(190, Math.max(96, ((activeColor >>> 24) & 0xFF) * 3 / 4));
                    int backgroundColor = (activeColor & 0x00FFFFFF) | (alpha << 24);
                    result.setSpan(new RelativeSizeSpan(0.78f), lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                    result.setSpan(new ForegroundColorSpan(backgroundColor), lineStart, lineEnd,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                }

                for (LrcEntry.WordTiming word : entry.getWordTimings()) {
                    int start = lineStart + Math.max(0, Math.min(word.startIndex, entry.getShowText().length()));
                    int end = lineStart + Math.max(0, Math.min(word.endIndex, entry.getShowText().length()));
                    if (end <= start) continue;
                    long duration = Math.max(1L, word.end - word.start);
                    float progress = Math.max(0f, Math.min(1f,
                        (timeMs - word.start) / (float) duration));
                    result.setSpan(new LrcEntry.ProgressiveFillSpan(
                            activeColor, inactiveColor, progress, word.background),
                        start, end, SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                    if (word.background) {
                        result.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
                            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
                    }
                }
            }
            return result;
        }

        public boolean isSecondaryVoice(long timeMs) {
            long lineTime = findLineTime(timeMs);
            for (LrcEntry entry : entries) {
                if (entry.getTime() == lineTime && "v2".equalsIgnoreCase(entry.getSpeaker())) {
                    return true;
                }
            }
            return false;
        }

        /** All TTML timestamp groups in playback order. */
        public List<String> getAllPlainLines() {
            List<String> result = new ArrayList<>();
            long previousTime = Long.MIN_VALUE;
            StringBuilder row = new StringBuilder();
            for (LrcEntry entry : entries) {
                if (entry.isBackgroundOnly()) continue;
                if (entry.getTime() != previousTime) {
                    if (row.length() > 0) result.add(row.toString());
                    row.setLength(0);
                    previousTime = entry.getTime();
                } else if (row.length() > 0) row.append('\n');
                row.append(entry.getText());
            }
            if (row.length() > 0) result.add(row.toString());
            return result;
        }

        /** Index of the active timestamp group in getAllPlainLines(). */
        public int getLineIndex(long timeMs) {
            long activeTime = findLineTime(timeMs);
            if (activeTime < 0) return -1;
            int index = -1;
            long previousTime = Long.MIN_VALUE;
            for (LrcEntry entry : entries) {
                if (entry.getTime() != previousTime) { index++; previousTime = entry.getTime(); }
                if (entry.getTime() == activeTime) return index;
            }
            return -1;
        }

        public String getPlainLine(long timeMs) {
            long lineTime = findLineTime(timeMs);
            StringBuilder result = new StringBuilder();
            for (LrcEntry entry : entries) {
                if (entry.getTime() != lineTime || entry.isBackgroundOnly()) continue;
                if (result.length() > 0) result.append('\n');
                result.append(entry.getText());
            }
            return result.toString();
        }
    }

    public static ParsedDocument parseDocument(String content) {
        if (TextUtils.isEmpty(content)) return new ParsedDocument(new ArrayList<>());
        String key = fingerprint(content);
        synchronized (CACHE) {
            ParsedDocument cached = CACHE.get(key);
            if (cached != null) return cached;
        }
        ParsedDocument parsed = new ParsedDocument(parseTtmlUncached(content));
        synchronized (CACHE) {
            CACHE.put(key, parsed);
        }
        return parsed;
    }

    private static String fingerprint(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder(digest.length * 2);
            for (byte value : digest) key.append(String.format(Locale.ROOT, "%02x", value));
            return content.length() + ":" + key;
        } catch (Exception ignored) {
            return content.length() + ":" + content.hashCode();
        }
    }

    private static LinePart buildLinePart(String source,
        List<LrcEntry.WordTiming> sourceWords, boolean background) {
        StringBuilder text = new StringBuilder();
        List<LrcEntry.WordTiming> timings = new ArrayList<>();
        boolean previousContinuesWord = false;
        for (LrcEntry.WordTiming word : sourceWords) {
            if (word.background != background) continue;
            int start = Math.max(0, Math.min(word.startIndex, source.length()));
            int end = Math.max(start, Math.min(word.endIndex, source.length()));
            if (end <= start) continue;
            String syllable = source.substring(start, end).replace("(", "").replace(")", "");
            if (syllable.isEmpty()) continue;
            if (text.length() > 0 && !previousContinuesWord) text.append(' ');
            int textStart = text.length();
            text.append(syllable);
            timings.add(new LrcEntry.WordTiming(
                word.start, word.end, textStart, text.length(),
                background, word.continuesWord, word.sustained
            ));
            previousContinuesWord = word.continuesWord;
        }
        return new LinePart(text.toString(), timings);
    }

    private static final class LinePart {
        final String text;
        final List<LrcEntry.WordTiming> words;

        LinePart(String text, List<LrcEntry.WordTiming> words) {
            this.text = text;
            this.words = words;
        }
    }

    private static final class SpanFrame {
        final int startIndex;
        final long begin;
        final long end;
        final long duration;
        final long parentOffset;
        final long timeOffset;
        final boolean background;
        boolean hasTimedChild;

        SpanFrame(int startIndex, long begin, long end, long duration,
            long parentOffset, long timeOffset, boolean background) {
            this.startIndex = startIndex;
            this.begin = begin;
            this.end = end;
            this.duration = duration;
            this.parentOffset = parentOffset;
            this.timeOffset = timeOffset;
            this.background = background;
        }
    }

    private static String attribute(XmlPullParser parser, String name) {
        String value = parser.getAttributeValue(null, name);
        if (value != null) return value;
        for (int i = 0; i < parser.getAttributeCount(); i++) {
            if (name.equals(parser.getAttributeName(i))) return parser.getAttributeValue(i);
        }
        return null;
    }

    /** Supports TTML clock time, offset seconds, and millisecond offsets. */
    static long parseTime(String value) {
        if (value == null) return -1L;
        String time = value.trim().toLowerCase(Locale.ROOT);
        try {
            if (time.endsWith("ms")) {
                return Math.round(Double.parseDouble(time.substring(0, time.length() - 2)) );
            }
            if (time.endsWith("s") && !time.contains(":")) {
                return Math.round(Double.parseDouble(time.substring(0, time.length() - 1)) * 1000d);
            }
            if (time.endsWith("m") && !time.contains(":")) {
                return Math.round(Double.parseDouble(time.substring(0, time.length() - 1)) * 60000d);
            }
            if (time.endsWith("h") && !time.contains(":")) {
                return Math.round(Double.parseDouble(time.substring(0, time.length() - 1)) * 3600000d);
            }
            if (time.contains(":")) {
                String[] parts = time.split(":");
                if (parts.length == 3) {
                    double hours = Double.parseDouble(parts[0]);
                    double minutes = Double.parseDouble(parts[1]);
                    double seconds = Double.parseDouble(parts[2]);
                    return Math.round((hours * 3600d + minutes * 60d + seconds) * 1000d);
                }
                if (parts.length == 2) {
                    double minutes = Double.parseDouble(parts[0]);
                    double seconds = Double.parseDouble(parts[1]);
                    return Math.round((minutes * 60d + seconds) * 1000d);
                }
            }
            return Math.round(Double.parseDouble(time) * 1000d);
        } catch (NumberFormatException ignored) {
            return -1L;
        }
    }
}
