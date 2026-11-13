/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.player

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.SharedPreferences
import android.os.Bundle
import android.view.View
import androidx.annotation.ColorInt
import androidx.core.animation.doOnEnd
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.PreferenceManager
import androidx.viewpager.widget.ViewPager
import kotlinx.coroutines.launch
import code.name.monkey.appthemehelper.util.ColorUtil
import code.name.monkey.appthemehelper.util.MaterialValueHelper
import code.name.monkey.retromusic.LYRICS_TYPE
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter
import code.name.monkey.retromusic.adapter.album.AlbumCoverPagerAdapter.AlbumCoverFragment
import code.name.monkey.retromusic.databinding.FragmentPlayerAlbumCoverBinding
import code.name.monkey.retromusic.extensions.isColorLight
import code.name.monkey.retromusic.extensions.surfaceColor
import code.name.monkey.retromusic.fragments.NowPlayingScreen.*
import code.name.monkey.retromusic.fragments.base.AbsMusicServiceFragment
import code.name.monkey.retromusic.fragments.base.goToLyrics
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.CoverLrcView
import code.name.monkey.retromusic.lyrics.LyricsTranslation
import code.name.monkey.retromusic.lyrics.TtmlUtils
import code.name.monkey.retromusic.model.lyrics.Lyrics
import code.name.monkey.retromusic.transform.CarousalPagerTransformer
import code.name.monkey.retromusic.transform.ParallaxPagerTransformer
import code.name.monkey.retromusic.util.CoverLyricsType
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job

class PlayerAlbumCoverFragment : AbsMusicServiceFragment(R.layout.fragment_player_album_cover),
    ViewPager.OnPageChangeListener, MusicProgressViewUpdateHelper.Callback,
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentPlayerAlbumCoverBinding? = null
    private val binding get() = _binding!!
    private var callbacks: Callbacks? = null
    private var currentPosition: Int = 0
    val viewPager get() = binding.viewPager

    private val colorReceiver = object : AlbumCoverFragment.ColorReceiver {
        override fun onColorReady(color: MediaNotificationProcessor, request: Int) {
            if (currentPosition == request) {
                notifyColorChange(color)
            }
        }
    }
    private var progressViewUpdateHelper: MusicProgressViewUpdateHelper? = null
    private var useTtmlLyrics = false
    private var translationEnabled = false
    private var translationSource = "en"
    private var translationTarget = "id"
    private var translatedLines: List<String> = emptyList()
    private var translationJob: Job? = null
    private var lyricsLoadToken = 0
    private var hasLrcFormat = false
    private var hasTtmlFormat = false

    private val lrcView: CoverLrcView get() = binding.lyricsView

    var lyrics: Lyrics? = null

    fun removeSlideEffect() {
        val transformer = ParallaxPagerTransformer(R.id.player_image)
        transformer.setSpeed(0.3f)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewPager.setPageTransformer(false, transformer)
            }
        }
    }

    private fun updateLyrics() {
        val song = MusicPlayerRemote.currentSong
        val requestToken = ++lyricsLoadToken
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                val lrcFile = LyricUtil.getLrcLyricsFile(song)
                val ttmlFile = LyricUtil.getTtmlLyricsFile(song)
                val lrcHasTimestamps = lrcFile?.let {
                    Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]")
                        .containsMatchIn(LyricUtil.getStringFromLrc(it))
                } == true
                hasLrcFormat = lrcHasTimestamps
                hasTtmlFormat = ttmlFile != null
                val selectedFile = when {
                    lrcHasTimestamps && ttmlFile != null -> if (useTtmlLyrics) ttmlFile else lrcFile
                    lrcHasTimestamps -> lrcFile
                    ttmlFile != null -> ttmlFile
                    else -> null
                }
                val embedded = if (selectedFile == null) LyricUtil.getEmbeddedSyncedLyrics(song.data).orEmpty() else ""
                val format = when {
                    selectedFile?.extension.equals("ttml", true) -> "TTML"
                    selectedFile != null -> "LRC"
                    TtmlUtils.isTtml(embedded) -> "TTML"
                    embedded.isNotBlank() -> "LRC"
                    else -> ""
                }
                Triple(selectedFile, embedded, format)
            }
            if (_binding == null || requestToken != lyricsLoadToken) return@launch
            binding.playerLyricsFormat.text = result.third
            val replaceCover = PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
            binding.playerLyricsTranslate.isVisible = PreferenceUtil.showLyrics && replaceCover && result.third.isNotBlank()
            binding.playerLyricsFormat.isVisible = PreferenceUtil.showLyrics && replaceCover && result.third.isNotBlank()
            binding.playerLyricsFormat.isClickable = hasLrcFormat && hasTtmlFormat
            val selectedFile = result.first
            if (selectedFile != null) {
                lrcView.loadLrc(selectedFile)
            } else if (result.second.isNotBlank()) {
                lrcView.loadLrc(result.second)
            } else {
                lrcView.reset()
                lrcView.setLabel(getString(R.string.no_lyrics_found))
            }
        }
    }

    private fun prepareTranslations() {
        val rows = lrcView.getAllLineTexts()
        translationJob?.cancel()
        translatedLines = emptyList()
        if (!translationEnabled || rows.isEmpty()) {
            lrcView.setTranslations(emptyList())
            return
        }
        val requestedSong = MusicPlayerRemote.currentSong
        val source = translationSource
        val target = translationTarget
        translationJob = lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { LyricsTranslation.translateLines(rows, source, target) }
            }
            if (_binding == null || requestedSong.data != MusicPlayerRemote.currentSong.data ||
                !translationEnabled || source != translationSource || target != translationTarget) return@launch
            translationJob = null
            translatedLines = result.getOrElse { emptyList() }
            lrcView.setTranslations(translatedLines)
        }
    }

    private fun clearTranslation() {
        translationJob?.cancel()
        translationJob = null
        translatedLines = emptyList()
        if (_binding != null) lrcView.setTranslations(emptyList())
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentPlayerAlbumCoverBinding.bind(view)
        setupViewPager()
        progressViewUpdateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        maybeInitLyrics()
        lrcView.setOnLrcLoadedListener { if (translationEnabled) prepareTranslations() }
        binding.playerLyricsTranslate.setOnClickListener {
            LyricsTranslation.showSettings(requireContext(), translationSource, translationTarget) { source, target ->
                translationSource = source
                translationTarget = target
                translationEnabled = true
                prepareTranslations()
            }
        }
        binding.playerLyricsFormat.setOnClickListener {
            if (hasLrcFormat && hasTtmlFormat) {
                useTtmlLyrics = !useTtmlLyrics
                clearTranslation()
                updateLyrics()
            }
        }
        lrcView.apply {
            setDraggable(true) { time ->
                MusicPlayerRemote.seekTo(time.toInt())
                MusicPlayerRemote.resumePlaying()
                true
            }
            setOnClickListener {
                goToLyrics(requireActivity())
            }
        }
    }

    private fun setupViewPager() {
        binding.viewPager.addOnPageChangeListener(this)
        val nps = PreferenceUtil.nowPlayingScreen

        if (nps == Full || nps == Classic || nps == Fit || nps == Gradient) {
            binding.viewPager.offscreenPageLimit = 2
        } else if (PreferenceUtil.isCarouselEffect) {
            val metrics = resources.displayMetrics
            val ratio = metrics.heightPixels.toFloat() / metrics.widthPixels.toFloat()
            binding.viewPager.clipToPadding = false
            val padding =
                if (ratio >= 1.777f) {
                    40
                } else {
                    100
                }
            binding.viewPager.setPadding(padding, 0, padding, 0)
            binding.viewPager.pageMargin = 0
            binding.viewPager.setPageTransformer(false, CarousalPagerTransformer(requireContext()))
        } else {
            binding.viewPager.offscreenPageLimit = 2
            binding.viewPager.setPageTransformer(
                true,
                PreferenceUtil.albumCoverTransform
            )
        }
    }

    override fun onResume() {
        super.onResume()
        maybeInitLyrics()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        binding.viewPager.removeOnPageChangeListener(this)
        progressViewUpdateHelper?.stop()
        lyricsLoadToken++
        translationJob?.cancel()
        lrcView.setOnLrcLoadedListener(null)
        _binding = null
    }

    override fun onServiceConnected() {
        updatePlayingQueue()
        useTtmlLyrics = false
        clearTranslation()
        updateLyrics()
    }

    override fun onPlayingMetaChanged() {
        if (viewPager.currentItem != MusicPlayerRemote.position) {
            viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        }
        useTtmlLyrics = false
        clearTranslation()
        updateLyrics()
    }

    override fun onQueueChanged() {
        updatePlayingQueue()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String?) {
        when (key) {
            SHOW_LYRICS -> {
                if (PreferenceUtil.showLyrics) {
                    maybeInitLyrics()
                } else {
                    showLyrics(false)
                    progressViewUpdateHelper?.stop()
                }
            }
            LYRICS_TYPE -> {
                maybeInitLyrics()
            }
        }
    }

    private fun setLRCViewColors(@ColorInt primaryColor: Int, @ColorInt secondaryColor: Int) {
        lrcView.apply {
            setCurrentColor(android.graphics.Color.WHITE)
            setTimeTextColor(android.graphics.Color.WHITE)
            setTimelineColor(android.graphics.Color.WHITE)
            setNormalColor(android.graphics.Color.LTGRAY)
            setTimelineTextColor(android.graphics.Color.WHITE)
        }
    }

    private fun showLyrics(visible: Boolean) {
        binding.coverLyrics.isVisible = false
        binding.lyricsView.isVisible = false
        binding.playerLyricsTranslate.isVisible = visible && PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER
        binding.playerLyricsFormat.isVisible = visible && PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER && (hasLrcFormat || hasTtmlFormat)
        binding.viewPager.isVisible = true
        val lyrics: View = if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, if (visible) 0F else 1F).start()
            lrcView
        } else {
            ObjectAnimator.ofFloat(viewPager, View.ALPHA, 1F).start()
            binding.coverLyrics
        }
        ObjectAnimator.ofFloat(lyrics, View.ALPHA, if (visible) 1F else 0F).apply {
            doOnEnd {
                lyrics.isVisible = visible
            }
            start()
        }
    }

    private fun maybeInitLyrics() {
        val nps = PreferenceUtil.nowPlayingScreen
        // Don't show lyrics container for below conditions
        if (lyricViewNpsList.contains(nps) && PreferenceUtil.showLyrics) {
            showLyrics(true)
            if (PreferenceUtil.lyricsType == CoverLyricsType.REPLACE_COVER) {
                progressViewUpdateHelper?.start()
            }
        } else {
            showLyrics(false)
            progressViewUpdateHelper?.stop()
        }
    }

    private fun updatePlayingQueue() {
        val adapter = binding.viewPager.adapter
        if (adapter is AlbumCoverPagerAdapter) {
            adapter.updateData(MusicPlayerRemote.playingQueue)
        } else {
            binding.viewPager.adapter = AlbumCoverPagerAdapter(
                parentFragmentManager,
                MusicPlayerRemote.playingQueue
            )

        }
        binding.viewPager.setCurrentItem(MusicPlayerRemote.position, true)
        onPageSelected(MusicPlayerRemote.position)
    }

    override fun onPageScrolled(position: Int, positionOffset: Float, positionOffsetPixels: Int) {}

    override fun onPageSelected(position: Int) {
        currentPosition = position
        if (binding.viewPager.adapter != null) {
            (binding.viewPager.adapter as AlbumCoverPagerAdapter).receiveColor(
                colorReceiver,
                position
            )
        }
        if (position != MusicPlayerRemote.position) {
            MusicPlayerRemote.playSongAt(position)
        }
    }

    override fun onPageScrollStateChanged(state: Int) {
    }


    private fun notifyColorChange(color: MediaNotificationProcessor) {
        callbacks?.onColorChanged(color)
        val primaryColor = MaterialValueHelper.getPrimaryTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )
        val secondaryColor = MaterialValueHelper.getSecondaryDisabledTextColor(
            requireContext(),
            surfaceColor().isColorLight
        )

        setLRCViewColors(android.graphics.Color.WHITE, android.graphics.Color.LTGRAY)
    }

    fun setCallbacks(listener: Callbacks) {
        callbacks = listener
    }

    interface Callbacks {

        fun onColorChanged(color: MediaNotificationProcessor)

        fun onFavoriteToggled()
    }

    companion object {
        val TAG: String = PlayerAlbumCoverFragment::class.java.simpleName
    }

    private val lyricViewNpsList = listOf(
        Adaptive, Blur, BlurCard, Card, Classic, Color, Fit, Flat, Full, Gradient,
        Material, MD3, Normal, Plain, Simple
    )
}
