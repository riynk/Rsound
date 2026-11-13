/*
 * TTML lyric reader for RetroMusic.
 *
 * Supports line-synchronized TTML and Apple Music word-timed TTML by
 * projecting paragraph timestamps into the existing line-based LRC view.
 * Word-level highlighting requires a separate renderer and is not inferred
 * from paragraph timing.
 */
package code.name.monkey.retromusic.lyrics;

import android.text.TextUtils;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserFactory;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Parses TTML lyric documents without requiring a network connection. */
public final class TtmlUtils {
    private TtmlUtils() {}

    public static boolean isTtml(String content) {
        if (TextUtils.isEmpty(content)) return false;
        String value = content.trim();
        if (value.startsWith("\uFEFF")) value = value.substring(1).trim();
        String lower = value.toLowerCase(Locale.ROOT);
        return lower.startsWith("<?xml") || lower.startsWith("<tt") ||
                lower.contains(":tt ") || lower.contains("<tt ");
    }

    public static List<LrcEntry> parseTtml(String content) {
        List<LrcEntry> entries = new ArrayList<>();
        if (!isTtml(content)) return entries;

        try {
            XmlPullParserFactory factory = XmlPullParserFactory.newInstance();
            factory.setNamespaceAware(true);
            XmlPullParser parser = factory.newPullParser();
            String xml = content.startsWith("\uFEFF") ? content.substring(1) : content;
            try {
                parser.setFeature(XmlPullParser.FEATURE_PROCESS_DOCDECL, false);
            } catch (Exception ignored) {
                // The Android parser may not expose this optional feature.
            }
            parser.setInput(new StringReader(xml));

            int event = parser.getEventType();
            while (event != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG && "p".equals(parser.getName())) {
                    String begin = attribute(parser, "begin");
                    long start = parseTime(begin);
                    StringBuilder text = new StringBuilder();
                    int depth = 1;
                    while (depth > 0) {
                        event = parser.next();
                        if (event == XmlPullParser.END_DOCUMENT) break;
                        if (event == XmlPullParser.START_TAG) {
                            depth++;
                            if ("br".equals(parser.getName())) {
                                text.append('\n');
                            } else if (start < 0) {
                                long nestedStart = parseTime(attribute(parser, "begin"));
                                if (nestedStart >= 0) start = nestedStart;
                            }
                        } else if (event == XmlPullParser.TEXT ||
                                event == XmlPullParser.CDSECT ||
                                event == XmlPullParser.ENTITY_REF) {
                            if (parser.getText() != null) text.append(parser.getText());
                        } else if (event == XmlPullParser.END_TAG) {
                            depth--;
                        }
                    }
                    String line = text.toString().replace('\u00A0', ' ').trim();
                    if (start >= 0 && !line.isEmpty()) {
                        entries.add(new LrcEntry(start, line));
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
