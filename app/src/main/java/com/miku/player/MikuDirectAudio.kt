package com.miku.player

import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import android.util.Log

/**
 * DTA (Direct Transport Audio) controller — puts Miku Music on the M500's bit-perfect DIRECT
 * output path to the dual CS43198 DACs instead of the default Android 48kHz mixer.
 *
 * How the platform gates this (verified against the device's framework AudioTrack):
 *  - Every AudioTrack constructor calls isDirectEnable(): the app's process name must appear in
 *    Settings.Global "direct_support_app_list" (JSON: {"list":[{"packageName":"..."}]}), the
 *    route must not be A2DP/speaker, and no other app may currently hold the direct flag.
 *  - When granted, the framework tags the track's AudioAttributes with "direct_flag=1" and sets
 *    vendor.audio.hiby.hw.diect_flags_enable=yes / diect_proess_name=<app> into the HAL — the
 *    track then opens as a DIRECT AudioFlinger thread at the file's native rate (no SRC, no mix).
 *  - The factory list contains only com.hiby.music; this object adds com.miku.player so OUR
 *    player gets the exact same hardware path (interop with our own device, same access pattern).
 *
 * Verification: [status] reads the HAL's own flags back via AudioManager.getParameters, so the UI
 * can prove "DIRECT to DAC" vs "Android mixer" truthfully rather than assuming.
 */
object MikuDirectAudio {
    private const val TAG = "MikuDirectAudio"
    private const val KEY_APP_LIST = "direct_support_app_list"

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
