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
import android.graphics.Canvas;
import android.graphics.Paint;
import android.text.style.ReplacementSpan;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
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
  private String translationText;
  private StaticLayout staticLayout;
  private final List<WordTiming> wordTimings = new ArrayList<>();
  private int textGravity = -1;
  private String speaker;
  private boolean ttml;
  private long ttmlLineEndTime = -1L;
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
    init(paint, width, gravity, paint.getColor());
  }

  void init(TextPaint paint, int width, int gravity, int translationColor) {
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
    staticLayout = new StaticLayout(buildDisplayText(getShowText(), translationColor), paint, width, align, 1f, 0f, false);

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
    textGravity = "v2".equalsIgnoreCase(speaker) ? GRAVITY_RIGHT : GRAVITY_LEFT;
  }

  String getSpeaker() {
    return speaker;
  }

  void setTtml(boolean ttml) {
    this.ttml = ttml;
  }

  boolean isTtml() {
    return ttml;
  }

  void setTtmlLineEndTime(long endTime) {
    this.ttmlLineEndTime = endTime;
  }

  boolean hasWordTimings() {
    return !wordTimings.isEmpty();
  }

  List<WordTiming> getWordTimings() {
    return wordTimings;
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
    SpannableStringBuilder styled = new SpannableStringBuilder(getShowText());
    for (WordTiming word : wordTimings) {
      int start = Math.max(0, Math.min(word.startIndex, styled.length()));
      int end = Math.max(start, Math.min(word.endIndex, styled.length()));
      if (end > start) {
        long duration = Math.max(1L, word.end - word.start);
        float progress = Math.max(0f, Math.min(1f, (time - word.start) / (float) duration));
        if (word.sustained && time >= word.end) progress = 1f;
        styled.setSpan(new ProgressiveFillSpan(
                activeColor, inactiveColor, progress, word.background),
            start, end, SpannableString.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    appendTranslation(styled, inactiveColor);
    staticLayout = new StaticLayout(styled, paint, width, align, 1f, 0f, false);
  }

  /**
   * Draws each timed TTML syllable with a left-to-right karaoke fill, rather than
   * switching the whole word on at its start timestamp.
   */
  static final class ProgressiveFillSpan extends ReplacementSpan {
    private final int activeColor;
    private final int inactiveColor;
    private final float progress;
    private final boolean background;

    ProgressiveFillSpan(int activeColor, int inactiveColor, float progress, boolean background) {
      this.activeColor = activeColor;
      this.inactiveColor = inactiveColor;
      this.progress = Math.max(0f, Math.min(1f, progress));
      this.background = background;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end,
        Paint.FontMetricsInt fm) {
      String value = text.subSequence(start, end).toString();
      TextPaint measurePaint = new TextPaint(paint);
      if (background) {
        measurePaint.setTextSize(measurePaint.getTextSize() * 0.78f);
        measurePaint.setTypeface(Typeface.create(measurePaint.getTypeface(), Typeface.BOLD_ITALIC));
      }
      return Math.round(measurePaint.measureText(value));
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end,
        float x, int top, int y, int bottom, Paint paint) {
      String value = text.subSequence(start, end).toString();
      TextPaint drawPaint = new TextPaint(paint);
      if (background) {
        drawPaint.setTextSize(drawPaint.getTextSize() * 0.78f);
        drawPaint.setTypeface(Typeface.create(drawPaint.getTypeface(), Typeface.BOLD_ITALIC));
      }
      float width = drawPaint.measureText(value);
      int save = canvas.save();
      drawPaint.setColor(inactiveColor);
      canvas.drawText(value, x, y, drawPaint);
      if (progress > 0f) {
        canvas.clipRect(x, top, x + width * progress, bottom);
        drawPaint.setColor(background
            ? (activeColor & 0x00FFFFFF) | ((Math.round(((activeColor >>> 24) & 0xFF) * 0.6f)) << 24)
            : activeColor);
        canvas.drawText(value, x, y, drawPaint);
      }
      canvas.restoreToCount(save);
    }
  }

  static final class WordTiming {
    final long start;
    long end;
    final int startIndex;
    final int endIndex;
    final boolean background;
    final boolean continuesWord;
    boolean sustained;

    WordTiming(long start, long end, int startIndex, int endIndex) {
      this(start, end, startIndex, endIndex, false, false, false);
    }

    WordTiming(long start, long end, int startIndex, int endIndex, boolean background) {
      this(start, end, startIndex, endIndex, background, false, false);
    }

    WordTiming(long start, long end, int startIndex, int endIndex,
        boolean background, boolean continuesWord, boolean sustained) {
      this.start = start;
      this.end = end;
      this.startIndex = startIndex;
      this.endIndex = endIndex;
      this.background = background;
      this.continuesWord = continuesWord;
      this.sustained = sustained;
    }
  }

  long getTime() {
    return time;
  }

  long getWordEndTime() {
    long end = Math.max(time, ttmlLineEndTime);
    for (WordTiming word : wordTimings) {
      if (word.end > end) end = word.end;
    }
    return end;
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

  void setTranslationText(String translationText) {
    this.translationText = translationText;
  }

  private CharSequence buildDisplayText(CharSequence source, int translationColor) {
    SpannableStringBuilder styled = new SpannableStringBuilder(source);
    if (!TextUtils.isEmpty(secondText)) {
      int backgroundStart = text.length() + 1;
      int backgroundEnd = Math.min(styled.length(), backgroundStart + secondText.length());
      if (backgroundEnd > backgroundStart) {
        styled.setSpan(new RelativeSizeSpan(0.78f), backgroundStart, backgroundEnd,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        styled.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), backgroundStart, backgroundEnd,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
        int alpha = Math.min(190, Math.max(96, ((translationColor >>> 24) & 0xFF) * 3 / 4));
        int backgroundColor = (translationColor & 0x00FFFFFF) | (alpha << 24);
        styled.setSpan(new ForegroundColorSpan(backgroundColor), backgroundStart, backgroundEnd,
            SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
      }
    }
    appendTranslation(styled, translationColor);
    return styled;
  }

  private void appendTranslation(SpannableStringBuilder styled, int translationColor) {
    if (TextUtils.isEmpty(translationText)) return;
    styled.append('\n');
    int start = styled.length();
    styled.append(translationText);
    int end = styled.length();
    styled.setSpan(new RelativeSizeSpan(0.78f), start, end,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    styled.setSpan(new StyleSpan(Typeface.BOLD_ITALIC), start, end,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
    int alpha = Math.min(220, Math.max(150, (translationColor >>> 24) & 0xFF));
    int color = (translationColor & 0x00FFFFFF) | (alpha << 24);
    styled.setSpan(new ForegroundColorSpan(color), start, end,
        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE);
  }

  String getSecondText() {
    return secondText;
  }

  String getShowText() {
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
