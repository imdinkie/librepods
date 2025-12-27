/*
    LibrePods - AirPods liberated from Apple’s ecosystem
    Copyright (C) 2025 LibrePods contributors

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <https://www.gnu.org/licenses/>.
*/

@file:OptIn(ExperimentalEncodingApi::class)

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.KeyEvent
import androidx.annotation.RequiresApi
import me.kavishdevar.librepods.services.ServiceManager
import kotlin.io.encoding.ExperimentalEncodingApi

object MediaController {
    private var initialVolume: Int? = null
    private var pausedByConversationalAwareness: Boolean = false
    private lateinit var audioManager: AudioManager
    var iPausedTheMedia = false
    var userPlayedTheMedia = false
    private lateinit var sharedPreferences: SharedPreferences
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var preferenceChangeListener: SharedPreferences.OnSharedPreferenceChangeListener

    private var playbackStateListener: ((Boolean) -> Unit)? = null
    private var lastNotifiedIsMusicActive: Boolean? = null
    private var lastPlaybackStateNotifyAt: Long = 0L
    private const val PLAYBACK_STATE_NOTIFY_DEBOUNCE_MS = 500L

    var pausedWhileTakingOver = false
    var pausedForOtherDevice = false

    private var lastSelfActionAt: Long = 0L
    private const val SELF_ACTION_IGNORE_MS = 800L
    private const val PLAYBACK_DEBOUNCE_MS = 300L
    private var lastPlaybackCallbackAt: Long = 0L
    private var lastKnownIsMusicActive: Boolean? = null

    private const val PAUSED_FOR_OTHER_DEVICE_CLEAR_MS = 500L
    private val clearPausedForOtherDeviceRunnable = Runnable {
        pausedForOtherDevice = false
        Log.d("MediaController", "Cleared pausedForOtherDevice after timeout, resuming normal playback monitoring")
    }

    private var relativeVolume: Boolean = false
    private var conversationalAwarenessVolume: Int = 2
    private var conversationalAwarenessPauseMusic: Boolean = false

    var recentlyLostOwnership: Boolean = false

    private var lastPlayWithReplay: Boolean = false
    private var lastPlayTime: Long = 0L

    fun setPlaybackStateListener(listener: ((Boolean) -> Unit)?) {
        playbackStateListener = listener
    }

    fun getLastNotifiedPlaybackState(): Boolean? {
        return lastNotifiedIsMusicActive
    }

    fun initialize(audioManager: AudioManager, sharedPreferences: SharedPreferences) {
        if (this::audioManager.isInitialized) {
            return
        }
        this.audioManager = audioManager
        this.sharedPreferences = sharedPreferences
        Log.d("MediaController", "Initializing MediaController")
        relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
        conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) / 0.4).toInt())
        conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)

        preferenceChangeListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            when (key) {
                "relative_conversational_awareness_volume" -> {
                    relativeVolume = sharedPreferences.getBoolean("relative_conversational_awareness_volume", false)
                }
                "conversational_awareness_volume" -> {
                    conversationalAwarenessVolume = sharedPreferences.getInt("conversational_awareness_volume", (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * 0.4).toInt())
                }
                "conversational_awareness_pause_music" -> {
                    conversationalAwarenessPauseMusic = sharedPreferences.getBoolean("conversational_awareness_pause_music", false)
                }
            }
        }

        sharedPreferences.registerOnSharedPreferenceChangeListener(preferenceChangeListener)

        audioManager.registerAudioPlaybackCallback(cb, null)
    }

    val cb = object : AudioManager.AudioPlaybackCallback() {
        @RequiresApi(Build.VERSION_CODES.R)
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) {
            super.onPlaybackConfigChanged(configs)
            val now = SystemClock.uptimeMillis()
            val isActive = audioManager.isMusicActive
            Log.d("MediaController", "Playback config changed, iPausedTheMedia: $iPausedTheMedia, isActive: $isActive, pausedForOtherDevice: $pausedForOtherDevice, lastKnownIsMusicActive: $lastKnownIsMusicActive")

            // audioManager.isMusicActive can stay true even when media is "paused" in some cases.
            // For playback-aware features, prefer a config-based signal for "playing media".
            val isLikelyPlayingMedia = isLikelyPlayingMedia(configs, isActive)
            maybeNotifyPlaybackStateChanged(now, isLikelyPlayingMedia)

            if (!isActive && lastPlayWithReplay && now - lastPlayTime < 2500L) {
                Log.d("MediaController", "Music paused shortly after play with replay; retrying play")
                lastPlayWithReplay = false
                sendPlay()
                lastKnownIsMusicActive = true
                return
            }

            if (now - lastPlaybackCallbackAt < PLAYBACK_DEBOUNCE_MS) {
                Log.d("MediaController", "Ignoring playback callback due to debounce (${now - lastPlaybackCallbackAt}ms)")
                lastPlaybackCallbackAt = now
                return
            }
            lastPlaybackCallbackAt = now

            if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
                Log.d("MediaController", "Ignoring playback callback because it's likely caused by our own action (${now - lastSelfActionAt}ms since last self-action)")
                lastKnownIsMusicActive = isActive
                return
            }

            Log.d("MediaController", "Configs received: ${configs?.size ?: 0} configurations")
            val currentActiveContentTypes = configs?.flatMap { config ->
                Log.d("MediaController", "Processing config: ${config}, audioAttributes: ${config.audioAttributes}")
                config.audioAttributes?.let { attrs ->
                    val contentType = attrs.contentType
                    Log.d("MediaController", "Config content type: $contentType")
                    listOf(contentType)
                } ?: run {
                    Log.d("MediaController", "Config has no audioAttributes")
                    emptyList()
                }
            }?.toSet() ?: emptySet()

            Log.d("MediaController", "Current active content types: $currentActiveContentTypes")

            val hasNewMusicOrMovie = currentActiveContentTypes.any { contentType ->
                contentType == android.media.AudioAttributes.CONTENT_TYPE_MUSIC ||
                contentType == android.media.AudioAttributes.CONTENT_TYPE_MOVIE
            }

            Log.d("MediaController", "Has new music or movie: $hasNewMusicOrMovie")

            if (pausedForOtherDevice) {
                handler.removeCallbacks(clearPausedForOtherDeviceRunnable)
                handler.postDelayed(clearPausedForOtherDeviceRunnable, PAUSED_FOR_OTHER_DEVICE_CLEAR_MS)

                if (isActive) {
                    Log.d("MediaController", "Detected play while pausedForOtherDevice; attempting to take over")
                    if (!recentlyLostOwnership && hasNewMusicOrMovie) {
                        pausedForOtherDevice = false
                        userPlayedTheMedia = true
                        if (!pausedWhileTakingOver) {
                            ServiceManager.getService()?.takeOver("music")
                        }
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss or no new music/movie")
                    }
                } else {
                    Log.d("MediaController", "Still not active while pausedForOtherDevice; will clear state after timeout")
                }

                lastKnownIsMusicActive = isActive
                return
            }

            if (configs != null && !iPausedTheMedia) {
                ServiceManager.getService()?.aacpManager?.sendMediaInformataion(
                    ServiceManager.getService()?.localMac ?: return,
                    isActive
                )
                Log.d("MediaController", "User changed media state themselves; will wait for ear detection pause before auto-play")
                handler.postDelayed({
                    userPlayedTheMedia = audioManager.isMusicActive
                    if (audioManager.isMusicActive) {
                        pausedForOtherDevice = false
                    }
                }, 7)
            }

            Log.d("MediaController", "pausedWhileTakingOver: $pausedWhileTakingOver")
            if (!pausedWhileTakingOver && isActive && hasNewMusicOrMovie) {
                if (lastKnownIsMusicActive != true) {
                    if (!recentlyLostOwnership) {
                        Log.d("MediaController", "Music/movie is active and not pausedWhileTakingOver; requesting takeOver")
                        ServiceManager.getService()?.takeOver("music")
                    } else {
                        Log.d("MediaController", "Skipping take-over due to recent ownership loss")
                    }
                }
            }

            lastKnownIsMusicActive = hasNewMusicOrMovie && isActive
        }
    }

    private fun isLikelyPlayingMedia(
        configs: List<AudioPlaybackConfiguration>?,
        isMusicActive: Boolean,
    ): Boolean {
        if (!isMusicActive) return false
        val list = configs ?: return false
        val result = list.any { config ->
            val attrs = config.audioAttributes ?: return@any false
            val isMediaUsage = attrs.usage == AudioAttributes.USAGE_MEDIA
            val isMusicOrMovie =
                attrs.contentType == AudioAttributes.CONTENT_TYPE_MUSIC ||
                    attrs.contentType == AudioAttributes.CONTENT_TYPE_MOVIE

            // AudioPlaybackConfiguration does not expose player state in the public SDK stubs we
            // compile against, but it *does* include it in toString() (e.g. "state:started").
            // Use that as a best-effort signal to distinguish "playing" vs "paused".
            val started = config.toString().contains("state:started")

            started && isMediaUsage && isMusicOrMovie
        }
        if (!result && isMusicActive && list.isNotEmpty()) {
            Log.d("MediaController", "isMusicActive=true but no started media configs detected; treating as paused (configs=${list.size})")
        }
        return result
    }

    private fun maybeNotifyPlaybackStateChanged(now: Long, isPlayingMedia: Boolean) {
        // Only debounce duplicate state reports; allow fast true transitions (e.g. track switch)
        // so playback-aware features don't get stuck.
        if (lastNotifiedIsMusicActive == isPlayingMedia) {
            if (now - lastPlaybackStateNotifyAt < PLAYBACK_STATE_NOTIFY_DEBOUNCE_MS) {
                return
            }
        }
        lastPlaybackStateNotifyAt = now

        if (now - lastSelfActionAt < SELF_ACTION_IGNORE_MS) {
            return
        }

        if (lastNotifiedIsMusicActive == isPlayingMedia) {
            return
        }

        Log.d("MediaController", "Notifying playback state listener: isPlayingMedia=$isPlayingMedia (prev=$lastNotifiedIsMusicActive)")
        lastNotifiedIsMusicActive = isPlayingMedia
        playbackStateListener?.invoke(isPlayingMedia)
    }

    @Synchronized
    fun getMusicActive(): Boolean {
        return audioManager.isMusicActive
    }

    @Synchronized
    fun sendPlayPause() {
        if (audioManager.isMusicActive) {
            Log.d("MediaController", "Sending pause because music is active")
            sendPause()
        } else {
            Log.d("MediaController", "Sending play because music is not active")
            sendPlay()
        }
    }

    @Synchronized
    fun sendPreviousTrack() {
        Log.d("MediaController", "Sending previous track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_PREVIOUS
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendNextTrack() {
        Log.d("MediaController", "Sending next track")
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_DOWN,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        audioManager.dispatchMediaKeyEvent(
            KeyEvent(
                KeyEvent.ACTION_UP,
                KeyEvent.KEYCODE_MEDIA_NEXT
            )
        )
        lastSelfActionAt = SystemClock.uptimeMillis()
    }

    @Synchronized
    fun sendPause(force: Boolean = false) {
        Log.d("MediaController", "Sending pause with iPausedTheMedia: $iPausedTheMedia, userPlayedTheMedia: $userPlayedTheMedia, isMusicActive: ${audioManager.isMusicActive}, force: $force")
        if ((audioManager.isMusicActive) && (!userPlayedTheMedia || force)) {
            iPausedTheMedia = if (force) audioManager.isMusicActive else true
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PAUSE
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
    }

    @Synchronized
    fun sendPlay(replayWhenPaused: Boolean = false, force: Boolean = false) {
        Log.d("MediaController", "Sending play with iPausedTheMedia: $iPausedTheMedia, replayWhenPaused: $replayWhenPaused, force: $force")
        if (replayWhenPaused) {
            lastPlayWithReplay = true
            lastPlayTime = SystemClock.uptimeMillis()
        }
        if (iPausedTheMedia || force) { // very creative, ik. thanks.
            Log.d("MediaController", "Sending play and setting userPlayedTheMedia to false")
            userPlayedTheMedia = false
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_DOWN,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            audioManager.dispatchMediaKeyEvent(
                KeyEvent(
                    KeyEvent.ACTION_UP,
                    KeyEvent.KEYCODE_MEDIA_PLAY
                )
            )
            lastSelfActionAt = SystemClock.uptimeMillis()
        }
        if (!audioManager.isMusicActive) {
            Log.d("MediaController", "Setting iPausedTheMedia to false")
            iPausedTheMedia = false
        }
        if (pausedWhileTakingOver) {
            Log.d("MediaController", "Setting pausedWhileTakingOver to false")
            pausedWhileTakingOver = false
        }
    }

    @Synchronized
    fun startSpeaking() {
        val isMusicActive = audioManager.isMusicActive
        Log.d(
            "MediaController",
            "CA speaking started: isMusicActive=$isMusicActive pausedByConversationalAwareness=$pausedByConversationalAwareness maxVol=${audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)} currentVol=${audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)} caVol=$conversationalAwarenessVolume relative=$relativeVolume pauseMusic=$conversationalAwarenessPauseMusic"
        )

        if (initialVolume == null) {
            initialVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("MediaController", "Initial Volume: $initialVolume")
            val targetVolume = if (relativeVolume) {
                (initialVolume!! * conversationalAwarenessVolume / 100)
            } else if (initialVolume!! > (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)) {
                (audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC) * conversationalAwarenessVolume / 100)
            } else {
                initialVolume!!
            }
            smoothVolumeTransition(initialVolume!!, targetVolume)
            if (conversationalAwarenessPauseMusic) {
                pausedByConversationalAwareness = isMusicActive
                Log.d(
                    "MediaController",
                    "CA pause decision: isMusicActive=$isMusicActive pausedByConversationalAwareness=$pausedByConversationalAwareness"
                )
                if (pausedByConversationalAwareness) {
                    sendPause(force = true)
                }
            }
        }
        Log.d("MediaController", "Initial Volume: $initialVolume")
    }

    @Synchronized
    fun stopSpeaking() {
        val isMusicActive = audioManager.isMusicActive
        Log.d(
            "MediaController",
            "CA speaking stopped: isMusicActive=$isMusicActive pausedByConversationalAwareness=$pausedByConversationalAwareness initialVolume=$initialVolume pauseMusic=$conversationalAwarenessPauseMusic"
        )
        if (initialVolume != null) {
            smoothVolumeTransition(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC), initialVolume!!)
            if (conversationalAwarenessPauseMusic) {
                if (pausedByConversationalAwareness) {
                    Log.d("MediaController", "CA resuming playback (pausedByConversationalAwareness=true)")
                    sendPlay()
                } else {
                    Log.d("MediaController", "CA not resuming playback (pausedByConversationalAwareness=false)")
                }
            }
            initialVolume = null
            pausedByConversationalAwareness = false
        }
    }

    private fun smoothVolumeTransition(fromVolume: Int, toVolume: Int) {
        Log.d("MediaController", "Smooth volume transition from $fromVolume to $toVolume")
        val step = if (fromVolume < toVolume) 1 else -1
        val delay = 50L
        var currentVolume = fromVolume

        handler.post(object : Runnable {
            override fun run() {
                if (currentVolume != toVolume) {
                    currentVolume += step
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, currentVolume, 0)
                    handler.postDelayed(this, delay)
                }
            }
        })
    }
}
