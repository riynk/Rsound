/*
 * Copyright (c) 2019 Hemanth Savarala.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by
 *  the Free Software Foundation either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 */

package code.name.monkey.retromusic.model.lyrics;

import android.util.SparseArray;

public abstract class AbsSynchronizedLyrics extends Lyrics {

  private static final int TIME_OFFSET_MS =
      500; // time adjustment to display line before it actually starts

  protected final SparseArray<String> lines = new SparseArray<>();

  protected int offset = 0;

  public String getLine(int time) {
    int index = getLineIndex(time);
    return index >= 0 && index < lines.size() ? lines.valueAt(index) : "";
  }

  /** Returns every synchronized lyric row in timestamp order. */
  public java.util.List<String> getAllLines() {
    parse(true);
    java.util.List<String> result = new java.util.ArrayList<>();
    for (int i = 0; i < lines.size(); i++) result.add(lines.valueAt(i));
    return result;
  }

  /** Index of the active row in getAllLines(). */
  public int getLineIndex(int time) {
    parse(true);
    if (lines.size() == 0) return -1;
    time += offset + AbsSynchronizedLyrics.TIME_OFFSET_MS;
    int activeIndex = 0;
    for (int i = 0; i < lines.size(); i++) {
      if (time >= lines.keyAt(i)) activeIndex = i;
      else break;
    }
    return activeIndex;
  }

  @Override
  public String getText() {
    parse(false);

    if (valid) {
      StringBuilder sb = new StringBuilder();

      for (int i = 0; i < lines.size(); i++) {
        String line = lines.valueAt(i);
        sb.append(line).append("\r\n");
      }

      return sb.toString().trim().replaceAll("(\r?\n){3,}", "\r\n\r\n");
    }

    return super.getText();
  }

  public boolean isSynchronized() {
    return true;
  }

  public boolean isValid() {
    parse(true);
    return valid;
  }
}
