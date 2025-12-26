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

package me.kavishdevar.librepods.utils

import android.content.SharedPreferences
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.edit

object PlaybackAwareNoiseControlPrefs {
    const val ENABLED = "playback_aware_noise_control_enabled"
    const val FORCE_UI_SELECTION = "playback_aware_force_ui_selection"
    const val ACTIVE_MODE = "playback_aware_active_mode"
    const val MANUAL_OVERRIDE_TIMEOUT_MS = "playback_aware_manual_override_timeout_ms"
}

/**
 * Playback‑Aware Noise Control ("Auto Transparency"):
 * - Transition to paused -> switch to Transparency once
 * - Transition to playing -> switch to user-selected active mode
 * - If Force UI selection is on: manual changes are temporary and revert after a timeout
 * - If Force UI selection is off: manual changes while playing "learn" the active mode
 *
 * This is intentionally minimal and does not try to override AirPods' own state machines.
 */
class PlaybackAwareNoiseControlController(
    private val sharedPreferences: SharedPreferences,
    private val handler: Handler,
    private val setListeningMode: (mode: Int, source: String, isAuto: Boolean) -> Boolean,
) {
    private var lastKnownIsPlaying: Boolean? = null
    private var lastTransitionAtMs: Long = 0L
    private var isConversationAwarenessActive: Boolean = false
    private var lastKnownListeningMode: Int? = null
    private var pausedManualOverride: Boolean = false

    private var pendingRevertRunnable: Runnable? = null
    private var pendingRevertExpectedIsPlaying: Boolean? = null
    private var pendingRevertMode: Int? = null

    fun onConversationAwarenessActiveChanged(active: Boolean) {
        isConversationAwarenessActive = active
        Log.d(TAG, "CA active changed: $active (lastKnownIsPlaying=$lastKnownIsPlaying pausedManualOverride=$pausedManualOverride lastKnownMode=$lastKnownListeningMode)")

        if (active) {
            cancelPendingRevert("CA active")
            return
        }

        // If CA ended while playback is paused, keep Transparency enforced unless the user
        // explicitly changed modes while paused.
        val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
        val isPaused = lastKnownIsPlaying == false
        if (enabled && isPaused && !pausedManualOverride) {
            if (lastKnownListeningMode != TRANSPARENCY_MODE) {
                Log.d(TAG, "CA ended while paused -> re-enforcing transparency")
                setListeningMode(TRANSPARENCY_MODE, "AutoTransparency:ca_end", true)
            }
        }
    }

    fun onPlaybackStateChanged(isPlaying: Boolean, source: String = "MediaController") {
        val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
        if (!enabled) {
            lastKnownIsPlaying = isPlaying
            pausedManualOverride = false
            cancelPendingRevert("disabled")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (lastKnownIsPlaying == isPlaying) {
            return
        }

        // Debounce transitions so short blips don't thrash ANC modes.
        val debounceMs = 500L
        if (now - lastTransitionAtMs < debounceMs) {
            Log.d(TAG, "Ignoring playback transition due to debounce (${now - lastTransitionAtMs}ms) source=$source")
            lastKnownIsPlaying = isPlaying
            return
        }
        lastTransitionAtMs = now
        lastKnownIsPlaying = isPlaying
        if (!isPlaying) {
            // New paused session: allow user override tracking to reset.
            pausedManualOverride = false
        }
        cancelPendingRevert("playback state changed")

        if (isPlaying) {
            if (isConversationAwarenessActive) {
                Log.d(TAG, "Skipping mode enforcement during Conversational Awareness source=$source isPlaying=$isPlaying")
                return
            }
            val activeMode = getPreferredActiveMode()
            Log.d(TAG, "Playback started -> enforcing active mode=$activeMode source=$source")
            setListeningMode(activeMode, "AutoTransparency:play:$source", true)
        } else {
            Log.d(TAG, "Playback paused -> enforcing transparency source=$source")
            setListeningMode(TRANSPARENCY_MODE, "AutoTransparency:pause:$source", true)
        }
    }

    fun onListeningModeChanged(newMode: Int, fromAuto: Boolean) {
        lastKnownListeningMode = newMode
        val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
        if (!enabled) return
        if (fromAuto) return
        if (isConversationAwarenessActive) return

        val isPlaying = lastKnownIsPlaying ?: return
        val forceUiSelection =
            sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.FORCE_UI_SELECTION, false)

        if (isPlaying) {
            if (forceUiSelection) {
                val desired = getPreferredActiveMode()
                if (newMode != desired) {
                    scheduleRevert(desired, expectedIsPlaying = true, reason = "manual_change_playing")
                } else {
                    cancelPendingRevert("manual change matches desired (playing)")
                }
            } else {
                if (newMode in ACTIVE_MODE_CANDIDATES) {
                    Log.d(TAG, "Learning active mode from manual change: $newMode")
                    sharedPreferences.edit { putInt(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE, newMode) }
                }
            }
        } else {
            pausedManualOverride = true
            if (forceUiSelection) {
                val desired = TRANSPARENCY_MODE
                if (newMode != desired) {
                    scheduleRevert(desired, expectedIsPlaying = false, reason = "manual_change_paused")
                } else {
                    cancelPendingRevert("manual change matches desired (paused)")
                }
            }
        }
    }

    private fun getPreferredActiveMode(): Int {
        val mode = sharedPreferences.getInt(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE, DEFAULT_ACTIVE_MODE)
        return if (mode in ACTIVE_MODE_CANDIDATES) mode else DEFAULT_ACTIVE_MODE
    }

    private fun scheduleRevert(mode: Int, expectedIsPlaying: Boolean, reason: String) {
        cancelPendingRevert("reschedule:$reason")

        val timeoutMs = sharedPreferences.getInt(
            PlaybackAwareNoiseControlPrefs.MANUAL_OVERRIDE_TIMEOUT_MS,
            DEFAULT_MANUAL_OVERRIDE_TIMEOUT_MS
        ).toLong().coerceAtLeast(1000L)

        pendingRevertExpectedIsPlaying = expectedIsPlaying
        pendingRevertMode = mode

        val runnable = Runnable {
            val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
            if (!enabled) return@Runnable
            if (isConversationAwarenessActive) return@Runnable

            if (lastKnownIsPlaying != expectedIsPlaying) {
                Log.d(TAG, "Skipping revert; playback state changed (expected=$expectedIsPlaying actual=$lastKnownIsPlaying)")
                return@Runnable
            }

            Log.d(TAG, "Reverting listening mode to $mode after manual override timeout ($timeoutMs ms) reason=$reason")
            setListeningMode(mode, "AutoTransparency:revert:$reason", true)
        }

        pendingRevertRunnable = runnable
        handler.postDelayed(runnable, timeoutMs)
        Log.d(TAG, "Scheduled revert to $mode in ${timeoutMs}ms (expectedIsPlaying=$expectedIsPlaying reason=$reason)")
    }

    private fun cancelPendingRevert(reason: String) {
        pendingRevertRunnable?.let { handler.removeCallbacks(it) }
        pendingRevertRunnable = null
        pendingRevertExpectedIsPlaying = null
        pendingRevertMode = null
        Log.d(TAG, "Canceled pending revert ($reason)")
    }

    companion object {
        private const val TAG = "PlaybackAwareNC"

        // AACP LISTENING_MODE values: 1=Off, 2=ANC, 3=Transparency, 4=Adaptive
        const val TRANSPARENCY_MODE = 3

        private val ACTIVE_MODE_CANDIDATES = setOf(2, 3, 4)
        private const val DEFAULT_ACTIVE_MODE = 4
        private const val DEFAULT_MANUAL_OVERRIDE_TIMEOUT_MS = 60_000
    }
}
