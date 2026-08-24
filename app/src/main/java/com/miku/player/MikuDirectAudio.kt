package com.miku.player

import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * DTA (Direct Transport Audio) controller — puts Miku Music on the M500's bit-perfect DIRECT
 * output path to the dual CS43198 DACs instead of the default Android 48kHz mixer.
 *
 * How the platform gates this (verified against the device's decompiled framework AudioTrack AND
 * the disassembled QTI AudioPolicyManager — BOTH gates must pass):
 *  - HiBy gate (framework AudioTrack ctor, isDirectEnable()): the app's process name must appear
 *    in Settings.Global "direct_support_app_list" (JSON: {"list":[{"packageName":"..."}]}), the
 *    route must not be A2DP/speaker, and no other app may currently hold the direct flag. When it
 *    passes, the framework tags the track's AudioAttributes with "direct_flag=1" and sets
 *    vendor.audio.hiby.hw.diect_flags_enable=yes / diect_proess_name=<app> into the HAL.
 *  - QTI gate (AudioPolicyManager::getOutputForDevices, "Force direct flags to use pcm offload"):
 *    a linear-PCM STREAM_MUSIC track with usage MEDIA is force-routed to the vendor "direct_pcm"
 *    DIRECT profile (16/24/32-bit int, native rate) ONLY when its requested output flags are
 *    NONE. AudioTrack.shouldEnablePowerSaving() silently adds FLAG_DEEP_BUFFER to any MUSIC
 *    track whose buffer is >= 100ms of audio, which disqualifies it — that (Media3's 250ms+
 *    default buffer) is why playback used to land on the deep_buffer MIXER thread despite the
 *    allow-list. MikuDirectAudioSink therefore requests just under 100ms (see its configure()).
 *  - The factory list contains only com.hiby.music; this object adds com.miku.player so OUR
 *    player gets the exact same hardware path (interop with our own device, same access pattern).
 *
 * Verification: [status] reads the HAL's own flags back via AudioManager.getParameters, so the UI
 * can prove "DIRECT to DAC" vs "Android mixer" truthfully rather than assuming.
 */
object MikuDirectAudio {
    private const val TAG = "MikuDirectAudio"
    private const val KEY_APP_LIST = "direct_support_app_list"
    private const val KEY_VOLUME_LOCK = "vendor.audio.hw.volume_lock"

    data class DirectStatus(
        /** The HAL reports the direct flag is up. */
        val directFlagEnabled: Boolean,
        /** Which process currently holds the direct output (should be us while playing). */
        val holderProcess: String,
        /** We are in the allow-list, so new AudioTracks we open are eligible for DIRECT. */
        val allowListed: Boolean
    ) {
        val weHoldDirect: Boolean get() = directFlagEnabled && holderProcess == "com.miku.player"
    }

    /** Current allow-list JSON (empty string when unset). */
    fun allowList(ctx: Context): String =
        Settings.Global.getString(ctx.contentResolver, KEY_APP_LIST) ?: ""

    fun isAllowListed(ctx: Context): Boolean =
        allowList(ctx).contains(ctx.packageName)

    /**
     * Ensure com.miku.player is in the direct allow-list. Tries a plain Settings write first
     * (works when the ROM grants us WRITE_SECURE_SETTINGS), falls back to root. Preserves any
     * existing entries (keeps com.hiby.music etc). Safe to call every launch — no-ops when
     * already present. Returns true when the list contains us afterwards.
     */
    fun ensureAllowListed(ctx: Context): Boolean {
        val current = allowList(ctx)
        if (current.contains(ctx.packageName)) return true
        val updated = when {
            current.isBlank() ->
                """{"list":[{"packageName":"com.hiby.music"},{"packageName":"${ctx.packageName}"}]}"""
            // Splice our entry into the existing JSON list (the framework check is a plain
            // String.contains, but keep the JSON well-formed for the vendor code that parses it).
            current.contains("\"list\":[") ->
                current.replaceFirst("\"list\":[", "\"list\":[{\"packageName\":\"${ctx.packageName}\"},")
            else ->
                """{"list":[{"packageName":"com.hiby.music"},{"packageName":"${ctx.packageName}"}]}"""
        }
        val direct = runCatching {
            Settings.Global.putString(ctx.contentResolver, KEY_APP_LIST, updated)
        }.getOrDefault(false)
        if (!direct) {
            // Single-quote for the shell; the JSON contains no single quotes.
            RootShell.execFast("settings put global $KEY_APP_LIST '$updated'")
        }
        val ok = allowList(ctx).contains(ctx.packageName)
        Log.i(TAG, "ensureAllowListed: ok=$ok list=${allowList(ctx)}")
        return ok
    }

    /**
     * Unlock the full 0-100 STREAM_MUSIC range on the wired outputs.
     *
     * Root cause of the "volume stuck at 35/100 on the 4.4mm jack" clamp (verified in the
     * decompiled vendor SystemUI/services): it is NOT the audio_policy volume curves and NOT
     * safe-media-volume. HiBy ships a "volume lock" — `ro.vendor.volume_lock_enable=yes` on the
     * M500 arms it, and Settings.Global "vendor.audio.hw.volume_lock" == "yes" makes SystemUI's
     * HibyBarTool/HiByNewVolumeDialog clamp STREAM_MUSIC to getLockMaxVolume(): 35 for the
     * balanced/single-ended phone-out, 40 for h2w/lineout, 80 for USB/SPDIF. Per-jack saved
     * levels live in Settings.Global under the jack's product name ("balance", "h2w", "lineout",
     * "balance_lo", ...) and get re-clamped on plug. Writing "no" here is exactly the switch the
     * vendor's own volume-lock toggle flips: SystemUI re-reads the Global on every volume event,
     * and HibyAudioSettingInitUtils preserves a non-empty value across boots. The vendor MUSIC
     * curve (DEFAULT_MEDIA_VOLUME_CURVE, 0 dB at index 100) then rules the whole range.
     */
    fun ensureFullVolumeRange(ctx: Context) {
        val cr = ctx.contentResolver
        runCatching {
            Settings.Global.putString(cr, KEY_VOLUME_LOCK, "no")
            Settings.Global.putString(cr, "volum_tips_ce_flag", "yes")
            Settings.Global.putInt(cr, "audio_safe_volume_state", 0)
            Settings.System.putInt(cr, "max_volume_value", 100)
            Settings.System.putInt(cr, "max_volume_value_preout", 100)
            Settings.System.putInt(cr, "volume_music_headphone", 100)
            Settings.System.putInt(cr, "volume_music_speaker", 100)
            Settings.System.putInt(cr, "volume_music_headset", 100)
        }
        RootShell.execFast(
            "settings put global $KEY_VOLUME_LOCK no; " +
            "settings put global volum_tips_ce_flag yes; " +
            "settings put global audio_safe_volume_state 0; " +
            "settings put system max_volume_value 100; " +
            "settings put system max_volume_value_preout 100; " +
            "settings put system volume_music_headphone 100; " +
            "settings put system volume_music_speaker 100; " +
            "settings put system volume_music_headset 100; " +
            "setprop $KEY_VOLUME_LOCK no"
        )
        // Live-apply: push the unlock into the audio HAL (what SystemUI's own receiver does) and
        // poke HibyBarTool's registered "volume_lock_state_update" receiver so the running
        // SystemUI drops its 35/40 cap without a reboot.
        pushToHal(ctx, KEY_VOLUME_LOCK, "no")
        pushToHal(ctx, "volum_tips_ce_flag", "yes")
        runCatching {
            ctx.sendBroadcast(Intent("volume_lock_state_update"))
            ctx.sendBroadcast(Intent("volum_tips_ce_flag_change"))
            ctx.sendBroadcast(Intent("com.android.settings.action.MAX_VOLUME_CHANGED"))
        }
    }

    /** Read the HAL's live direct-output state back for truthful verification. */
    fun status(ctx: Context): DirectStatus {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            ?: return DirectStatus(false, "", isAllowListed(ctx))
        fun param(key: String): String = runCatching {
            // HAL returns "key=value"; strip to the value.
            am.getParameters("getprop=$key").substringAfter('=').trim()
        }.getOrDefault("")
        return DirectStatus(
            directFlagEnabled = param("vendor.audio.hiby.hw.diect_flags_enable") == "yes",
            holderProcess = param("vendor.audio.hiby.hw.diect_proess_name"),
            allowListed = isAllowListed(ctx)
        )
    }

    /**
     * Push a vendor.audio.hiby.* value straight into the audio HAL — the same live channel the
     * platform's own audio settings use. Complements CirrusLogicManager's sysfs/setprop/Global
     * writes so a change is heard immediately, not just after the next output reopen.
     */
    fun pushToHal(ctx: Context, key: String, value: String) {
        runCatching {
            val am = ctx.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            am?.setParameters("$key=$value")
        }.onFailure { Log.w(TAG, "pushToHal $key failed", it) }
    }
}
