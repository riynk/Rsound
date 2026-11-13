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
package code.name.monkey.retromusic.fragments.player.blur

import android.content.SharedPreferences
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceManager
import code.name.monkey.appthemehelper.util.ToolbarContentTintHelper
import code.name.monkey.retromusic.BLUR_ARTWORK_SOURCE
import code.name.monkey.retromusic.LYRICS_TYPE
import code.name.monkey.retromusic.NEW_BLUR_AMOUNT
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.SHOW_LYRICS
import code.name.monkey.retromusic.databinding.FragmentBlurBinding
import code.name.monkey.retromusic.extensions.drawAboveSystemBars
import code.name.monkey.retromusic.extensions.whichFragment
import code.name.monkey.retromusic.fragments.base.AbsPlayerFragment
import code.name.monkey.retromusic.fragments.player.PlayerAlbumCoverFragment
import code.name.monkey.retromusic.glide.BlurTransformation
import code.name.monkey.retromusic.glide.RetroGlideExtension
import code.name.monkey.retromusic.glide.RetroGlideExtension.artistImageOptions
import code.name.monkey.retromusic.glide.RetroGlideExtension.simpleSongCoverOptions
import code.name.monkey.retromusic.glide.crossfadeListener
import code.name.monkey.retromusic.helper.MusicPlayerRemote
import code.name.monkey.retromusic.model.Song
import code.name.monkey.retromusic.util.PreferenceUtil
import code.name.monkey.retromusic.util.PreferenceUtil.blurAmount
import code.name.monkey.retromusic.util.color.MediaNotificationProcessor
import com.bumptech.glide.Glide
import com.bumptech.glide.RequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext


class BlurPlayerFragment : AbsPlayerFragment(R.layout.fragment_blur),
    SharedPreferences.OnSharedPreferenceChangeListener {

    private var lastRequest: RequestBuilder<Drawable>? = null
    private var blurJob: Job? = null
    private var blurRequestId = 0

    override fun playerToolbar(): Toolbar {
        return binding.playerToolbar
    }

    private lateinit var playbackControlsFragment: BlurPlaybackControlsFragment

    private var lastColor: Int = 0

    private var _binding: FragmentBlurBinding? = null
    private val binding get() = _binding!!


    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        _binding = FragmentBlurBinding.bind(view)
        setUpSubFragments()
        setUpPlayerToolbar()
        binding.playerToolbar.drawAboveSystemBars()
        updateLyricsMode()
    }

    private fun setUpSubFragments() {
        playbackControlsFragment = whichFragment(R.id.playbackControlsFragment)
        val playerAlbumCoverFragment: PlayerAlbumCoverFragment =
            whichFragment(R.id.playerAlbumCoverFragment)
        playerAlbumCoverFragment.setCallbacks(this)
    }

    private fun setUpPlayerToolbar() {
        binding.playerToolbar.apply {
            inflateMenu(R.menu.menu_player)
            setNavigationOnClickListener { requireActivity().onBackPressedDispatcher.onBackPressed() }
            ToolbarContentTintHelper.colorizeToolbar(this, Color.WHITE, activity)
        }.setOnMenuItemClickListener(this)
    }

    override fun onFavoriteToggled() {
        toggleFavorite(MusicPlayerRemote.currentSong)
    }

    override fun onColorChanged(color: MediaNotificationProcessor) {
        playbackControlsFragment.setColor(color)
        lastColor = color.backgroundColor
        libraryViewModel.updateColor(color.backgroundColor)
        ToolbarContentTintHelper.colorizeToolbar(binding.playerToolbar, Color.WHITE, activity)
    }

    override fun toggleFavorite(song: Song) {
        super.toggleFavorite(song)
        if (song.id == MusicPlayerRemote.currentSong.id) {
            updateIsFavorite()
        }
    }

    override fun onShow() {
    }

    override fun onHide() {
    }

    override fun toolbarIconColor(): Int {
        return Color.WHITE
    }

    override val paletteColor: Int
        get() = lastColor

    private fun updateBlur() {
        val song = MusicPlayerRemote.currentSong
        val requestId = ++blurRequestId
        blurJob?.cancel()
        blurJob = viewLifecycleOwner.lifecycleScope.launch {
            val artist = if (PreferenceUtil.blurArtworkSource == "artist" && song.artistId > 0) {
                withContext(Dispatchers.IO) { libraryViewModel.artistById(song.artistId) }
            } else {
                null
            }
            if (requestId != blurRequestId || song.id != MusicPlayerRemote.currentSong.id) {
                return@launch
            }

            val request = if (artist != null && artist.albums.isNotEmpty()) {
                Glide.with(this@BlurPlayerFragment)
                    .load(RetroGlideExtension.getArtistModel(artist))
                    .artistImageOptions(artist)
            } else {
                Glide.with(this@BlurPlayerFragment)
                    .load(RetroGlideExtension.getSongModel(song))
                    .simpleSongCoverOptions(song)
            }
            request.transform(
                BlurTransformation.Builder(requireContext()).blurRadius(blurAmount.toFloat())
                    .build()
            ).thumbnail(lastRequest)
                .error(Glide.with(this@BlurPlayerFragment).load(ColorDrawable(Color.DKGRAY)).fitCenter())
                .also {
                    lastRequest = it.clone()
                    it.crossfadeListener().into(binding.colorBackground)
                }
        }
    }

    private fun updateLyricsMode() {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val lyricsMode = PreferenceUtil.showLyrics && !landscape
        playbackControlsFragment.setLyricsMode(lyricsMode)

        if (!landscape) {
            val params = binding.playerAlbumCoverFragment.layoutParams as? ConstraintLayout.LayoutParams
                ?: return
            val topMargin = if (PreferenceUtil.showLyrics) {
                resources.getDimensionPixelSize(R.dimen.lyrics_artwork_top_spacing)
            } else {
                0
            }
            if (params.topMargin != topMargin) {
                params.topMargin = topMargin
                binding.playerAlbumCoverFragment.layoutParams = params
            }
        }
    }

    override fun onServiceConnected() {
        updateIsFavorite()
        updateBlur()
        updateLyricsMode()
    }

    override fun onPlayingMetaChanged() {
        updateIsFavorite()
        updateBlur()
        updateLyricsMode()
    }

    override fun onPause() {
        super.onPause()
        blurJob?.cancel()
        blurRequestId++
        lastRequest = null
    }

    override fun onResume() {
        super.onResume()
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .registerOnSharedPreferenceChangeListener(this)
        updateBlur()
        updateLyricsMode()
    }

    override fun onDestroyView() {
        blurJob?.cancel()
        blurRequestId++
        PreferenceManager.getDefaultSharedPreferences(requireContext())
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroyView()
        _binding = null
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences?, key: String?) {
        if (key == NEW_BLUR_AMOUNT || key == BLUR_ARTWORK_SOURCE) {
            updateBlur()
        }
        if (key == SHOW_LYRICS || key == LYRICS_TYPE) {
            updateLyricsMode()
        }
    }
}
