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
    /**
     * UI-selected "When Playing" noise control mode.
     *
     * This should remain stable even if learning is enabled (Force UI selection OFF).
     */
    const val ACTIVE_MODE_UI = "playback_aware_active_mode_ui"

    /**
     * Learned/override "When Playing" noise control mode (only used when Force UI selection is OFF).
     * If unset, the controller falls back to [ACTIVE_MODE_UI].
     */
    const val ACTIVE_MODE_LEARNED = "playback_aware_active_mode_learned"

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

    private var transportBaselineUntilMs: Long? = null

    private var pendingPauseEnforceRunnable: Runnable? = null
    private var pendingPauseEnforceAtMs: Long? = null

    private var pendingPlayEnforceRunnable: Runnable? = null
    private var pendingPlayEnforceAtMs: Long? = null

    private var pendingRevertRunnable: Runnable? = null
    private var pendingRevertExpectedIsPlaying: Boolean? = null
    private var pendingRevertMode: Int? = null

    private var lastPlayingBecameTrueAtMs: Long? = null

    fun onConversationAwarenessActiveChanged(active: Boolean) {
        val wasActive = isConversationAwarenessActive
        if (wasActive == active) return

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
        if (enabled && wasActive && isPaused && !pausedManualOverride) {
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
            lastPlayingBecameTrueAtMs = null
            pausedManualOverride = false
            transportBaselineUntilMs = null
            cancelPendingPauseEnforce("disabled")
            cancelPendingPlayEnforce("disabled")
            cancelPendingRevert("disabled")
            return
        }

        val now = SystemClock.uptimeMillis()
        if (lastKnownIsPlaying == isPlaying) {
            return
        }

        Log.d(TAG, "Playback state changed: ${lastKnownIsPlaying} -> $isPlaying source=$source CA=$isConversationAwarenessActive")

        // Debounce transitions so short blips don't thrash ANC modes.
        // Still allow true flips; only suppress repeated flapping.
        val debounceMs = 200L
        if (now - lastTransitionAtMs < debounceMs) {
            Log.d(TAG, "Ignoring playback transition due to debounce (${now - lastTransitionAtMs}ms) source=$source isPlaying=$isPlaying")
            lastKnownIsPlaying = isPlaying
            if (isPlaying) {
                lastPlayingBecameTrueAtMs = now
                // Under heavy flapping, we can miss the "play" enforcement and remain in Transparency.
                // Always schedule a deferred play enforcement if playback is still active.
                schedulePlayEnforceActiveMode(source = "debounced:$source", now = now)
            } else {
                cancelPendingPlayEnforce("debounced pause")
            }
            return
        }
        lastTransitionAtMs = now
        lastKnownIsPlaying = isPlaying
        if (!isPlaying) {
            // New paused session: allow user override tracking to reset.
            pausedManualOverride = false
            lastPlayingBecameTrueAtMs = null
        }
        cancelPendingRevert("playback state changed")

        if (isPlaying) {
            lastPlayingBecameTrueAtMs = now
            cancelPendingPauseEnforce("playback resumed")
            cancelPendingPlayEnforce("playback resumed (reschedule)")
            if (isConversationAwarenessActive) {
                Log.d(TAG, "Skipping mode enforcement during Conversational Awareness source=$source isPlaying=$isPlaying")
                return
            }
            // Schedule (instead of immediate) so we converge after rapid play/pause flapping.
            schedulePlayEnforceActiveMode(source = source, now = now)
        } else {
            cancelPendingPlayEnforce("playback paused")
            // Track switches can produce micro-pauses (empty configs) that should not trigger a mode change.
            // Use a short grace window and cancel if playback resumes quickly.
            schedulePauseEnforceTransparency(source, now)
        }
    }

    fun onTransportReady(isPlaying: Boolean, source: String) {
        val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
        if (!enabled) return

        val now = SystemClock.uptimeMillis()
        transportBaselineUntilMs = now + TRANSPORT_BASELINE_WINDOW_MS

        // New session. Clear any previous timers and paused manual override.
        pausedManualOverride = false
        cancelPendingPauseEnforce("transport ready")
        cancelPendingPlayEnforce("transport ready")
        cancelPendingRevert("transport ready")

        // Update our notion of playback state, but apply policy even if the state didn't change.
        lastKnownIsPlaying = isPlaying
        lastPlayingBecameTrueAtMs = if (isPlaying) now else null
        Log.d(TAG, "Transport ready: baselineWindowMs=$TRANSPORT_BASELINE_WINDOW_MS isPlaying=$isPlaying source=$source")
        applyPolicyNow(source = "transport_ready:$source", immediatePauseEnforce = true)

        handler.postDelayed(
            {
                val stillEnabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
                if (!stillEnabled) return@postDelayed
                val baselineEnd = transportBaselineUntilMs
                if (baselineEnd != null && SystemClock.uptimeMillis() < baselineEnd) return@postDelayed
                transportBaselineUntilMs = null
                Log.d(TAG, "Transport baseline window ended")
                applyPolicyNow(source = "transport_ready_baseline_end:$source", immediatePauseEnforce = false)
            },
            TRANSPORT_BASELINE_WINDOW_MS
        )
    }

    fun onPlaybackAwareSettingsChanged(source: String) {
        // Apply immediately after settings changes so Force UI selection and UI mode updates take effect
        // without requiring a playback transition.
        applyPolicyNow(source = "settings_changed:$source", immediatePauseEnforce = true)
    }

    fun applyPolicyNow(source: String, immediatePauseEnforce: Boolean) {
        val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
        if (!enabled) return

        val isPlaying = lastKnownIsPlaying
        if (isPlaying == null) {
            Log.d(TAG, "applyPolicyNow skipped (unknown playback state) source=$source")
            return
        }

        if (isPlaying) {
            if (isConversationAwarenessActive) {
                Log.d(TAG, "applyPolicyNow: skipping active-mode enforcement during CA source=$source")
                return
            }
            val activeMode = getActiveModeForPolicy()
            Log.d(TAG, "applyPolicyNow: enforcing active mode=$activeMode source=$source")
            setListeningMode(activeMode, "AutoTransparency:apply_play:$source", true)
        } else {
            if (pausedManualOverride) {
                Log.d(TAG, "applyPolicyNow: pausedManualOverride=true -> not enforcing transparency source=$source")
                return
            }

            if (immediatePauseEnforce) {
                Log.d(TAG, "applyPolicyNow: enforcing transparency immediately source=$source")
                setListeningMode(TRANSPARENCY_MODE, "AutoTransparency:apply_pause:$source", true)
            } else {
                schedulePauseEnforceTransparency(source, SystemClock.uptimeMillis())
            }
        }
    }

    fun onListeningModeChanged(newMode: Int, fromAuto: Boolean) {
        val previousMode = lastKnownListeningMode
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
                val desired = getActiveModeForPolicy(forceUiSelectionOverride = true)
                if (newMode != desired) {
                    scheduleRevert(desired, expectedIsPlaying = true, reason = "manual_change_playing")
                } else {
                    cancelPendingRevert("manual change matches desired (playing)")
                }
            } else {
                val now = SystemClock.uptimeMillis()
                cancelPendingPlayEnforce("manual override while playing")
                if (shouldLearnActiveMode(now) && newMode in ACTIVE_MODE_CANDIDATES) {
                    Log.d(TAG, "Learning active mode from manual change: $newMode")
                    sharedPreferences.edit { putInt(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE_LEARNED, newMode) }
                } else {
                    Log.d(TAG, "Skipping learning (not stable enough): mode=$newMode")
                }
            }
        } else {
            val now = SystemClock.uptimeMillis()
            val withinBaseline = transportBaselineUntilMs?.let { now < it } == true
            if (withinBaseline) {
                // During the initial connect churn, AirPods can report their current listening mode
                // (fromAuto=false) even when the user hasn't changed anything. Only treat changes as
                // manual overrides if we already had a known mode and it changed.
                if (previousMode != null && previousMode != newMode) {
                    Log.d(TAG, "Baseline window: treating paused mode change as manual override ($previousMode -> $newMode)")
                } else {
                    Log.d(TAG, "Baseline window: ignoring paused manual override (mode=$newMode)")
                    return
                }
            }

            pausedManualOverride = true
            cancelPendingPauseEnforce("manual override while paused")
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

    private fun schedulePauseEnforceTransparency(source: String, now: Long) {
        val graceMs = PAUSE_ENFORCE_GRACE_MS
        cancelPendingPauseEnforce("reschedule pause enforce")
        val enforceAt = now + graceMs
        pendingPauseEnforceAtMs = enforceAt

        val runnable = Runnable {
            // Mark runnable as consumed so learning/enforcement logic can proceed.
            pendingPauseEnforceRunnable = null
            pendingPauseEnforceAtMs = null

            val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
            if (!enabled) return@Runnable

            if (lastKnownIsPlaying != false) {
                Log.d(TAG, "Pause enforce: skipped because playback resumed (source=$source)")
                return@Runnable
            }

            if (pausedManualOverride) {
                Log.d(TAG, "Pause enforce: skipped because pausedManualOverride=true (source=$source)")
                return@Runnable
            }

            Log.d(TAG, "Playback paused -> enforcing transparency after grace=${graceMs}ms source=$source")
            setListeningMode(TRANSPARENCY_MODE, "AutoTransparency:pause:$source", true)
        }

        pendingPauseEnforceRunnable = runnable
        handler.postDelayed(runnable, graceMs)
        Log.d(TAG, "Pause enforce: scheduled in ${graceMs}ms (at=$enforceAt) source=$source")
    }

    private fun cancelPendingPauseEnforce(reason: String) {
        pendingPauseEnforceRunnable?.let { handler.removeCallbacks(it) }
        if (pendingPauseEnforceRunnable != null) {
            val now = SystemClock.uptimeMillis()
            val scheduledAt = pendingPauseEnforceAtMs
            val remainingMs = scheduledAt?.let { (it - now).coerceAtLeast(0L) }
            Log.d(TAG, "Pause enforce: canceled ($reason) remainingMs=$remainingMs scheduledAtMs=$scheduledAt")
        }
        pendingPauseEnforceRunnable = null
        pendingPauseEnforceAtMs = null
    }

    private fun schedulePlayEnforceActiveMode(source: String, now: Long) {
        cancelPendingPlayEnforce("reschedule play enforce")
        val enforceAt = now + PLAY_ENFORCE_DELAY_MS
        pendingPlayEnforceAtMs = enforceAt

        val runnable = Runnable {
            // Mark runnable as consumed so learning/enforcement logic can proceed.
            pendingPlayEnforceRunnable = null
            pendingPlayEnforceAtMs = null

            val enabled = sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.ENABLED, false)
            if (!enabled) return@Runnable
            if (lastKnownIsPlaying != true) {
                Log.d(TAG, "Play enforce: skipped because playback paused (source=$source)")
                return@Runnable
            }
            if (isConversationAwarenessActive) {
                Log.d(TAG, "Play enforce: skipped during CA (source=$source)")
                return@Runnable
            }

            val activeMode = getActiveModeForPolicy()
            Log.d(TAG, "Playback playing -> enforcing active mode=$activeMode source=$source")
            setListeningMode(activeMode, "AutoTransparency:play:$source", true)
        }

        pendingPlayEnforceRunnable = runnable
        handler.postDelayed(runnable, PLAY_ENFORCE_DELAY_MS)
        Log.d(TAG, "Play enforce: scheduled in ${PLAY_ENFORCE_DELAY_MS}ms (at=$enforceAt) source=$source")
    }

    private fun cancelPendingPlayEnforce(reason: String) {
        pendingPlayEnforceRunnable?.let { handler.removeCallbacks(it) }
        if (pendingPlayEnforceRunnable != null) {
            val now = SystemClock.uptimeMillis()
            val scheduledAt = pendingPlayEnforceAtMs
            val remainingMs = scheduledAt?.let { (it - now).coerceAtLeast(0L) }
            Log.d(TAG, "Play enforce: canceled ($reason) remainingMs=$remainingMs scheduledAtMs=$scheduledAt")
        }
        pendingPlayEnforceRunnable = null
        pendingPlayEnforceAtMs = null
    }

    private fun getActiveModeForPolicy(forceUiSelectionOverride: Boolean? = null): Int {
        val forceUiSelection = forceUiSelectionOverride
            ?: sharedPreferences.getBoolean(PlaybackAwareNoiseControlPrefs.FORCE_UI_SELECTION, false)

        val uiModeRaw = sharedPreferences.getInt(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE_UI, DEFAULT_ACTIVE_MODE)
        val uiMode = if (uiModeRaw in ACTIVE_MODE_CANDIDATES) uiModeRaw else DEFAULT_ACTIVE_MODE

        if (forceUiSelection) return uiMode

        val learnedModeRaw = if (sharedPreferences.contains(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE_LEARNED)) {
            sharedPreferences.getInt(PlaybackAwareNoiseControlPrefs.ACTIVE_MODE_LEARNED, uiMode)
        } else {
            null
        }

        val learnedMode = learnedModeRaw?.takeIf { it in ACTIVE_MODE_CANDIDATES }
        return learnedMode ?: uiMode
    }

    private fun shouldLearnActiveMode(now: Long): Boolean {
        if (isConversationAwarenessActive) return false
        if (lastKnownIsPlaying != true) return false
        if (pendingPauseEnforceRunnable != null) return false

        val playingSince = lastPlayingBecameTrueAtMs ?: return false
        if (now - playingSince < LEARN_ACTIVE_MODE_MIN_PLAYING_MS) return false

        return true
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

        // Track-switch micro-pauses are typically very short; keep this low to preserve responsiveness.
        // Buffering pauses (e.g. YouTube during skips) can be longer; use a slightly larger grace window
        // to avoid spurious switches to Transparency.
        private const val PAUSE_ENFORCE_GRACE_MS = 500L
        private const val PLAY_ENFORCE_DELAY_MS = 120L
        private const val TRANSPORT_BASELINE_WINDOW_MS = 3000L
        private const val LEARN_ACTIVE_MODE_MIN_PLAYING_MS = 500L

        private val ACTIVE_MODE_CANDIDATES = setOf(2, 3, 4)
        private const val DEFAULT_ACTIVE_MODE = 4
        private const val DEFAULT_MANUAL_OVERRIDE_TIMEOUT_MS = 60_000
    }
}
