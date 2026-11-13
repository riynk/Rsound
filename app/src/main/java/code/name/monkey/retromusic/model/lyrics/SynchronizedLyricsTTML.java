/*
 * Copyright (C) 2026 RetroMusic contributors.
 *
 * Licensed under the GNU General Public License v3 or later.
 */
package code.name.monkey.retromusic.lyrics;

import java.util.List;

import code.name.monkey.retromusic.model.lyrics.AbsSynchronizedLyrics;

/** Synchronized lyric adapter for TTML documents. */
public class SynchronizedLyricsTTML extends AbsSynchronizedLyrics {
    @Override
    public SynchronizedLyricsTTML parse(boolean check) {
        if (parsed || data == null || data.isEmpty()) return this;

        List<LrcEntry> entries = TtmlUtils.parseTtml(data);
        if (entries == null || entries.isEmpty()) {
            parsed = true;
            return this;
        }

        for (LrcEntry entry : entries) {
            if (check) {
                valid = true;
                return this;
            }
            long time = entry.getTime();
            if (time >= 0 && time <= Integer.MAX_VALUE) {
                lines.append((int) time, entry.getText());
                valid = true;
            }
        }
        parsed = true;
        return this;
    }
}
