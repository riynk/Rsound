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
package code.name.monkey.retromusic.fragments.lyrics

import android.annotation.SuppressLint
import android.app.Activity
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.view.*
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.isVisible
import androidx.preference.PreferenceManager
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.transition.Fade
import code.name.monkey.appthemehelper.common.ATHToolbarActivity
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.appthemehelper.util.VersionUtils
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.activities.tageditor.TagWriter
import code.name.monkey.retromusic.databinding.FragmentLyricsBinding
import code.name.monkey.retromusic.extensions.accentColor
import code.name.monkey.retromusic.extensions.materialDialog
import code.name.monkey.retromusic.extensions.openUrl
import code.name.monkey.retromusic.extensions.uri
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.helper.MusicProgressViewUpdateHelper
import code.name.monkey.retromusic.lyrics.LrcView
import code.name.monkey.retromusic.lyrics.LyricsTranslation
import code.name.monkey.retromusic.model.AudioTagInfo
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.network.OnlineLyricsRepository
import code.name.monkey.retromusic.util.FileUtils
import code.name.monkey.retromusic.util.LyricUtil
import code.name.monkey.retromusic.util.UriUtil
import code.name.monkey.retromusic.util.PreferenceUtil.blurAmount
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import com.afollestad.materialdialogs.input.input
import kotlinx.coroutines.Job
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jaudiotagger.audio.AudioFileIO
import org.jaudiotagger.tag.FieldKey
import java.io.File
import java.io.FileOutputStream
import java.util.*
import kotlin.collections.set

class LyricsFragment : AbsMainActivityFragment(R.layout.fragment_lyrics),
    MusicProgressViewUpdateHelper.Callback, SharedPreferences.OnSharedPreferenceChangeListener {

    private var _binding: FragmentLyricsBinding? = null
    private val binding get() = _binding!!
    private lateinit var song: Song

    private lateinit var normalLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>
    private lateinit var editSyncedLyricsLauncher: ActivityResultLauncher<IntentSenderRequest>

    private lateinit var cacheFile: File
    private var syncedLyrics: String = ""
    private lateinit var syncedFileUri: Uri

    private var lyricsType: LyricsType = LyricsType.NORMAL_LYRICS
    private var useTtmlLyrics = false
    private var translationEnabled = false
    private var translationSource = "en"
    private var translationTarget = "id"
    private var translatedLines: List<String> = emptyList()
    private var translationAttempted = false
    private var normalLyricsSource = ""
    private var translationJob: Job? = null
    private var lastBackgroundRequest: RequestBuilder<Drawable>? = null

    private val googleSearchLrcUrl: String
        get() {
            var baseUrl = "http://www.google.com/search?"
            var query = song.title + "+" + song.artistName
            query = "q=" + query.replace(" ", "+") + " lyrics"
            baseUrl += query
            return baseUrl
        }

    private lateinit var updateHelper: MusicProgressViewUpdateHelper

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Normal lyrics launcher
        normalLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    FileUtils.copyFileToUri(requireContext(), cacheFile, song.uri)
                }
            }
        editSyncedLyricsLauncher =
            registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) {
                if (it.resultCode == Activity.RESULT_OK) {
                    requireContext().contentResolver.openOutputStream(syncedFileUri)?.use { os ->
                        (os as FileOutputStream).channel.truncate(0)
                        os.write(syncedLyrics.toByteArray())
                        os.flush()
                    }
                }
            }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = Fade()
        exitTransition = Fade()
        _binding = FragmentLyricsBinding.bind(view)
        updateHelper = MusicProgressViewUpdateHelper(this, 500, 1000)
        updateTitleSong()
        setupLyricsView()
        loadLyrics()

        setupWakelock()
        setupViews()
        setupToolbar()
        updateLyricsBackground()
    }

    private fun setupLyricsView() {
        binding.lyricsView.apply {
            setCurrentColor(Color.WHITE)
            setTimeTextColor(Color.WHITE)
            setTimelineColor(Color.WHITE)
            setTimelineTextColor(Color.WHITE)
            setDraggable(true, LrcView.OnPlayClickListener {
                MusicPlayerRemote.seekTo(it.toInt())
                return@OnPlayClickListener true
            })
        }
    }

    override fun onUpdateProgressViews(progress: Int, total: Int) {
        binding.lyricsView.updateTime(progress.toLong())
        if (translationEnabled && !translationAttempted && translationJob == null) {
            val hasRows = if (lyricsType == LyricsType.SYNCED_LYRICS) {
                binding.lyricsView.hasLrc()
            } else {
                normalLyricsSource.isNotBlank()
            }
            if (hasRows) prepareTranslations()
        }
    }

    private fun openTranslationSettings() {
        LyricsTranslation.showSettings(requireContext(), translationSource, translationTarget) { source, target ->
            translationSource = source
            translationTarget = target
            translationEnabled = true
            translationAttempted = false
            prepareTranslations()
        }
    }

    /** Translate all rows before playback selects them by timestamp index. */
    private fun prepareTranslations() {
        val rows = if (lyricsType == LyricsType.SYNCED_LYRICS) {
            binding.lyricsView.allLineTexts
        } else {
            normalLyricsSource.lines()
        }
        translationJob?.cancel()
        translatedLines = emptyList()
        if (!translationEnabled || rows.isEmpty()) return
        translationAttempted = true
        val requestedSong = song
        val requestedType = lyricsType
        val source = translationSource
        val target = translationTarget
        translationJob = viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { LyricsTranslation.translateLines(rows, source, target) }
            }
            if (!isAdded || requestedSong.data != MusicPlayerRemote.currentSong.data ||
                requestedType != lyricsType || !translationEnabled ||
                source != translationSource || target != translationTarget) return@launch
            translationJob = null
            translatedLines = result.getOrElse {
                Toast.makeText(requireContext(), R.string.translation_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (requestedType == LyricsType.SYNCED_LYRICS) {
                binding.lyricsView.setTranslations(translatedLines)
            } else {
                binding.normalLyrics.text = buildTranslatedNormalLyrics(rows, translatedLines)
            }
        }
    }

    private fun buildTranslatedNormalLyrics(rows: List<String>, translations: List<String>): CharSequence {
        return SpannableStringBuilder().apply {
            rows.forEachIndexed { index, sourceLine ->
                if (index > 0) append('\n')
                append(sourceLine)
                val translated = translations.getOrNull(index)
                if (!translated.isNullOrBlank()) {
                    append('\n')
                    val start = length
                    append(translated)
                    setSpan(RelativeSizeSpan(0.78f), start, length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(StyleSpan(Typeface.ITALIC), start, length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                    setSpan(ForegroundColorSpan(Color.WHITE), start, length,
                        SpannableStringBuilder.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
        }
    }

    private fun clearTranslation() {
        translationJob?.cancel()
        translationJob = null
        translatedLines = emptyList()
        translationAttempted = false
        if (_binding != null) {
            binding.lyricsView.setTranslations(emptyList())
            binding.normalLyrics.text = normalLyricsSource
        }
    }

    private fun setupViews() {
        binding.editButton.accentColor()
        binding.editButton.setOnClickListener {
            when (lyricsType) {
                LyricsType.SYNCED_LYRICS -> {
                    editSyncedLyrics()
                }
                LyricsType.NORMAL_LYRICS -> {
                    editNormalLyrics()
                }
            }
        }
    }

    override fun onPlayingMetaChanged() {
        super.onPlayingMetaChanged()
        useTtmlLyrics = false
        clearTranslation()
        updateTitleSong()
        updateLyricsBackground()
        loadLyrics()
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        useTtmlLyrics = false
        clearTranslation()
        updateTitleSong()
        updateLyricsBackground()
        loadLyrics()
    }

    private fun updateTitleSong() {
        song = MusicPlayerRemote.currentSong
    }

    private fun setupToolbar() {
        mainActivity.setSupportActionBar(binding.toolbar)
        ToolbarContentTintHelper.colorBackButton(binding.toolbar)
        binding.toolbar.setNavigationOnClickListener {
            findNavController().navigateUp()
        }
    }


    private fun updateLyricsBackground() {
        val currentSong = MusicPlayerRemote.currentSong
        Glide.with(this)
            .load(RetroGlideExtension.getSongModel(currentSong))
            .simpleSongCoverOptions(currentSong)
            .transform(
                BlurTransformation.Builder(requireContext())
                    .blurRadius(blurAmount.toFloat())
                    .build()
            )
            .thumbnail(lastBackgroundRequest)
            .error(Glide.with(this).load(ColorDrawable(Color.DKGRAY)).fitCenter())
            .also {
                lastBackgroundRequest = it.clone()
                it.crossfadeListener().into(binding.lyricsBackground)
            }
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT && _binding != null) {
            updateLyricsBackground()
        }
    }

    private fun setupWakelock() {
        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun onCreateMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.menu_lyrics, menu)
        updateLyricsFormatAction()
        ToolbarContentTintHelper.handleOnCreateOptionsMenu(
            requireContext(),
            binding.toolbar,
            menu,
            ATHToolbarActivity.getToolbarBackgroundColor(binding.toolbar)
        )
    }

    override fun onMenuItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_search -> {
                openUrl(googleSearchLrcUrl)
                true
            }
            R.id.action_translate_lyrics -> {
                openTranslationSettings()
                true
            }
            R.id.action_lyrics_format -> {
                useTtmlLyrics = !useTtmlLyrics
                loadLRCLyrics()
                true
            }
            R.id.action_download_lyrics -> {
                downloadOnlineLyrics()
                true
            }
            else -> false
        }
    }

    private fun downloadOnlineLyrics() {
        val requestedSong = song
        viewLifecycleOwner.lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                OnlineLyricsRepository.download(requestedSong)
            }
            if (!isAdded || requestedSong.data != MusicPlayerRemote.currentSong.data) return@launch

            when (result) {
                is OnlineLyricsRepository.DownloadResult.Saved -> {
                    useTtmlLyrics = false
                    loadLyrics()
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.lyrics_saved, result.format),
                        Toast.LENGTH_LONG
                    ).show()
                }
                OnlineLyricsRepository.DownloadResult.NotFound -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.lyrics_not_found,
                        Toast.LENGTH_SHORT
                    ).show()
                }
                is OnlineLyricsRepository.DownloadResult.Failed -> {
                    Toast.makeText(
                        requireContext(),
                        R.string.lyrics_download_failed,
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
    }

    @SuppressLint("CheckResult")
    private fun editNormalLyrics(lyrics: String? = null) {
        val file = File(song.data)
        val content = lyrics ?: try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }

        val song = song

        materialDialog().show {
            title(res = R.string.edit_normal_lyrics)
            input(
                hintRes = R.string.paste_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                viewLifecycleOwner.lifecycleScope.launch {
                    if (VersionUtils.hasR()) {
                        cacheFile = TagWriter.writeTagsToFilesR(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )[0]
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                        normalLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        TagWriter.writeTagsToFiles(
                            requireContext(), AudioTagInfo(
                                listOf(song.data), fieldKeyValueMap, null
                            )
                        )
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadNormalLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }


    private fun selectedSyncedLyricsFile(): File? {
        val lrcFile = LyricUtil.getLrcLyricsFile(song)
        val ttmlFile = LyricUtil.getTtmlLyricsFile(song)
        return when {
            lrcFile != null && ttmlFile != null ->
                if (useTtmlLyrics) ttmlFile else lrcFile
            lrcFile != null -> lrcFile
            else -> ttmlFile
        }
    }

    @SuppressLint("CheckResult")
    private fun editSyncedLyrics(lyrics: String? = null) {
        val selectedFile = selectedSyncedLyricsFile()
        val content = lyrics ?: selectedFile?.let { LyricUtil.getStringFromLrc(it) }
            ?: LyricUtil.getEmbeddedSyncedLyrics(song.data).orEmpty()

        val song = song
        materialDialog().show {
            title(res = R.string.edit_synced_lyrics)
            input(
                hintRes = R.string.paste_timeframe_lyrics_here,
                prefill = content,
                inputType = InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_CLASS_TEXT
            ) { _, input ->
                if (VersionUtils.hasR()) {
                    syncedLyrics = input.toString()
                    if (selectedFile?.exists() == true) {
                        syncedFileUri =
                            UriUtil.getUriFromPath(requireContext(), selectedFile.absolutePath)
                        val pendingIntent =
                            MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(syncedFileUri)
                            )
                        editSyncedLyricsLauncher.launch(
                            IntentSenderRequest.Builder(pendingIntent).build()
                        )
                    } else {
                        val fieldKeyValueMap = EnumMap<FieldKey, String>(FieldKey::class.java)
                        fieldKeyValueMap[FieldKey.LYRICS] = input.toString()
                        viewLifecycleOwner.lifecycleScope.launch {
                            cacheFile = TagWriter.writeTagsToFilesR(
                                requireContext(),
                                AudioTagInfo(listOf(song.data), fieldKeyValueMap, null)
                            )[0]
                            val pendingIntent = MediaStore.createWriteRequest(
                                requireContext().contentResolver,
                                listOf(song.uri)
                            )

                            normalLyricsLauncher.launch(
                                IntentSenderRequest.Builder(pendingIntent).build()
                            )
                        }
                    }
                } else {
                    if (selectedFile?.exists() == true) {
                        selectedFile.writeText(input.toString())
                    } else {
                        LyricUtil.writeLrc(song, input.toString())
                    }
                }
            }
            positiveButton(res = R.string.save) {
                loadLRCLyrics()
            }
            negativeButton(res = android.R.string.cancel)
        }
    }

    private fun loadNormalLyrics() {
        val file = File(song.data)
        var lyrics = try {
            AudioFileIO.read(file).tagOrCreateDefault.getFirst(FieldKey.LYRICS)
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
        if (lyrics.isNullOrBlank()) {
            lyrics = LyricUtil.getPlainLyricsFile(song)?.let { file ->
                runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            }.orEmpty()
        }
        binding.normalLyrics.isVisible = !lyrics.isNullOrEmpty()
        binding.noLyricsFound.isVisible = lyrics.isNullOrEmpty()
        normalLyricsSource = lyrics.orEmpty()
        binding.normalLyrics.text = normalLyricsSource
    }

    /**
     * @return success
     */
    private fun updateLyricsFormatAction() {
        val item = binding.toolbar.menu.findItem(R.id.action_lyrics_format) ?: return
        val lrcFile = LyricUtil.getLrcLyricsFile(song)
        val hasLrc = lrcFile?.let { file ->
            Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]")
                .containsMatchIn(LyricUtil.getStringFromLrc(file))
        } == true
        val hasTtml = LyricUtil.getTtmlLyricsFile(song) != null
        item.isVisible = hasLrc && hasTtml
        item.title = getString(
            if (useTtmlLyrics) R.string.switch_to_lrc else R.string.switch_to_ttml
        )
        item.setIcon(if (useTtmlLyrics) R.drawable.ic_lyrics else R.drawable.ic_ttml)
    }

    private fun loadLRCLyrics(): Boolean {
        clearTranslation()
        val lrcFile = LyricUtil.getLrcLyricsFile(song)
        val ttmlFile = LyricUtil.getTtmlLyricsFile(song)
        var selectedFile = when {
            lrcFile != null && ttmlFile != null ->
                if (useTtmlLyrics) ttmlFile else lrcFile
            lrcFile != null -> lrcFile
            else -> ttmlFile
        }

        if (selectedFile != null && selectedFile.extension.equals("lrc", ignoreCase = true)) {
            val content = LyricUtil.getStringFromLrc(selectedFile)
            val hasTimestamps = Regex("(?m)^\\s*\\[\\d{1,3}:\\d{2}(?:[.:]\\d{1,3})?\\]")
                .containsMatchIn(content)
            if (!hasTimestamps && ttmlFile != null) {
                selectedFile = ttmlFile
                useTtmlLyrics = true
            } else if (!hasTimestamps) {
                binding.lyricsView.setLabel(getString(R.string.empty))
                updateLyricsFormatAction()
                return false
            }
        }

        updateLyricsFormatAction()
        if (selectedFile != null) {
            binding.lyricsView.loadLrc(selectedFile)
        } else {
            val embeddedLyrics = LyricUtil.getEmbeddedSyncedLyrics(song.data)
            if (embeddedLyrics != null) {
                binding.lyricsView.loadLrc(embeddedLyrics)
            } else {
                binding.lyricsView.setLabel(getString(R.string.empty))
                return false
            }
        }
        return true
    }

    private fun loadLyrics() {
        lyricsType = if (!loadLRCLyrics()) {
            binding.lyricsView.isVisible = false
            loadNormalLyrics()
            LyricsType.NORMAL_LYRICS
        } else {
            binding.normalLyrics.isVisible = false
            binding.noLyricsFound.isVisible = false
            binding.lyricsView.isVisible = true
            LyricsType.SYNCED_LYRICS
        }
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        updateHelper.start()
        if (_binding != null) updateLyricsBackground()
    }

    override fun onPause() {
        super.onPause()
        updateHelper.stop()
        lastBackgroundRequest = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        lastBackgroundRequest = null
        if (MusicPlayerRemote.playingQueue.isNotEmpty())
            mainActivity.expandPanel()
        translationJob?.cancel()
        translationJob = null
        translatedLines = emptyList()
        translationAttempted = false
        normalLyricsSource = ""
        _binding = null
    }

    enum class LyricsType {
        NORMAL_LYRICS,
        SYNCED_LYRICS
    }
}
