/*
 * Copyright (C) 2017 wangchenyan
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file
 * except in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the
 * License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 */

package code.name.monkey.retromusic.lyrics;

import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Typeface;

import java.util.ArrayList;
import java.util.List;

/**
 * 一行歌词实体
 */
class LrcEntry implements Comparable<LrcEntry> {
  public static final int GRAVITY_CENTER = 0;
  public static final int GRAVITY_LEFT = 1;
  public static final int GRAVITY_RIGHT = 2;
  private final long time;
  private final String text;
  private String secondText;
  private StaticLayout staticLayout;
  private final List<WordTiming> wordTimings = new ArrayList<>();
  private int textGravity = -1;
  private String speaker;
  /** 歌词距离视图顶部的距离 */
  private float offset = Float.MIN_VALUE;

  LrcEntry(long time, String text) {
    this.time = time;
    this.text = text;
  }

  LrcEntry(long time, String text, String secondText) {
    this.time = time;
    this.text = text;
    this.secondText = secondText;
  }

  void init(TextPaint paint, int width, int gravity) {
    gravity = getTextGravity(gravity);
    Layout.Alignment align;
    switch (gravity) {
      case GRAVITY_LEFT:
        align = Layout.Alignment.ALIGN_NORMAL;
        break;

      default:
      case GRAVITY_CENTER:
        align = Layout.Alignment.ALIGN_CENTER;
        break;

      case GRAVITY_RIGHT:
        align = Layout.Alignment.ALIGN_OPPOSITE;
        break;
    }
    staticLayout = new StaticLayout(getShowText(), paint, width, align, 1f, 0f, false);

    offset = Float.MIN_VALUE;
  }


  void setWordTimings(List<WordTiming> timings) {
    wordTimings.clear();
    if (timings != null) wordTimings.addAll(timings);
  }

  void setTextGravity(int gravity) {
    textGravity = gravity;
  }

  int getTextGravity(int fallback) {
    return textGravity >= 0 ? textGravity : fallback;
  }

  void setSpeaker(String speaker) {
    this.speaker = speaker;
    if ("v2".equalsIgnoreCase(speaker)) textGravity = GRAVITY_RIGHT;
  }

  String getSpeaker() {
    return speaker;
  }

  boolean hasWordTimings() {
    return !wordTimings.isEmpty();
  }

  void updateWordHighlight(TextPaint paint, int width, int gravity, long time,
      int activeColor, int inactiveColor) {
    if (wordTimings.isEmpty()) return;
    gravity = getTextGravity(gravity);
    Layout.Alignment align;
    switch (gravity) {
      case GRAVITY_LEFT:
        align = Layout.Alignment.ALIGN_NORMAL;
        break;
      case GRAVITY_RIGHT:
        align = Layout.Alignment.ALIGN_OPPOSITE;
        break;
      default:
        align = Layout.Alignment.ALIGN_CENTER;
        break;
    }
    SpannableString styled = new SpannableString(getShowText());
    for (WordTiming word : wordTimings) {
      int color = time >= word.start && time < word.end ? activeColor : inactiveColor;
      int start = Math.max(0, Math.min(word.startIndex, styled.length()));
      int end = Math.max(start, Math.min(word.endIndex, styled.length()));
      if (end > start) {
        styled.setSpan(new ForegroundColorSpan(color), start, end,
            SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        if (word.background) {
          styled.setSpan(new StyleSpan(Typeface.ITALIC), start, end,
              SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
      }
    }
    staticLayout = new StaticLayout(styled, paint, width, align, 1f, 0f, false);
  }

  static final class WordTiming {
    final long start;
    long end;
    final int startIndex;
    final int endIndex;
    final boolean background;

    WordTiming(long start, long end, int startIndex, int endIndex) {
      this(start, end, startIndex, endIndex, false);
    }

    WordTiming(long start, long end, int startIndex, int endIndex, boolean background) {
      this.start = start;
      this.end = end;
      this.startIndex = startIndex;
      this.endIndex = endIndex;
      this.background = background;
    }
  }

  long getTime() {
    return time;
  }

  StaticLayout getStaticLayout() {
    return staticLayout;
  }

  int getHeight() {
    if (staticLayout == null) {
      return 0;
    }
    return staticLayout.getHeight();
  }

  public float getOffset() {
    return offset;
  }

  public void setOffset(float offset) {
    this.offset = offset;
  }

  String getText() {
    return text;
  }

  void setSecondText(String secondText) {
    this.secondText = secondText;
  }

  private String getShowText() {
    if (!TextUtils.isEmpty(secondText)) {
      return text + "\n" + secondText;
    } else {
      return text;
    }
  }

  @Override
  public int compareTo(LrcEntry entry) {
    if (entry == null) {
      return -1;
    }
    return (int) (time - entry.getTime());
  }
}
