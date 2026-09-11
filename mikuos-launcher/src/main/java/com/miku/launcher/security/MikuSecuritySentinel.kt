package com.miku.launcher.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Debug
import android.util.Base64
import java.io.BufferedReader
import java.io.File
import java.io.FileReader
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * MikuOS Production Cryptography Engine.
 * Provides AES-256-GCM authenticated encryption and runtime XOR string shielding.
 */
object MikuCrypto {
    private const val AES_KEY_STRING = "39MikuOS_SecureProdKey_2026_DAP!" // 32 bytes AES-256 key

    fun decryptString(cipherTextBase64: String): String {
        return try {
            val combined = Base64.decode(cipherTextBase64, Base64.DEFAULT)
            if (combined.size < 12) return cipherTextBase64
            val iv = combined.copyOfRange(0, 12)
            val cipherBytes = combined.copyOfRange(12, combined.size)
            val key = SecretKeySpec(AES_KEY_STRING.toByteArray(Charsets.UTF_8), "AES")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            String(cipher.doFinal(cipherBytes), Charsets.UTF_8)
        } catch (_: Throwable) {
            cipherTextBase64
        }
    }

    fun xorUnshield(encoded: ByteArray, key: Byte = 0x39): String {
        val result = ByteArray(encoded.size)
        for (i in encoded.indices) {
            result[i] = (encoded[i].toInt() xor key.toInt()).toByte()
        }
        return String(result, Charsets.UTF_8)
    }
}

/**
 * MikuOS Production Anti-Tamper & Security Sentinel.
 * Runtime integrity guardian protecting against APK repackaging, Frida hooking,
 * dynamic debugger attachment, and binary tampering.
 */
object MikuSecuritySentinel {
    private const val TAG = "MikuOS_Security"

    // Official MikuOS Production Certificate SHA-256 Fingerprints
    private val OFFICIAL_SIGNATURE_HASHES = setOf(
        "DFE220D2C2E64F8F50D1D4BA1EB5D6B699750EAA20B0D14ADCE24660CBFF7C61", // Production JKS
        "27196E386B875E76ADF700E7EA84E4C6EEE33DFFA9C724126E5E11E44D6042EE"  // AOSP Platform Key
    )

    /**
     * Tri-state signature check. It used to return `true` both when the hash loop found NO match and
     * from the catch block, so `signatureValid` was hardcoded-true: any UI rendering a
     * "SIGNATURE VALID ✓" chip off it would have been asserting a check that never actually passed.
     */
    enum class SignatureState { VALID, UNRECOGNISED_KEY, CHECK_FAILED }

    fun verifyApkSignatureState(context: Context): SignatureState {
        try {
            val pm = context.packageManager
            val pkg = context.packageName
            val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
                pi.signingInfo?.apkContentsSigners
            } else {
                @Suppress("DEPRECATION")
                val pi = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
                pi.signatures
            } ?: return SignatureState.CHECK_FAILED

            val md = MessageDigest.getInstance("SHA-256")
            for (sig in signatures) {
                val digest = md.digest(sig.toByteArray())
                val hex = digest.joinToString("") { "%02X".format(it) }
                if (OFFICIAL_SIGNATURE_HASHES.contains(hex)) {
                    return SignatureState.VALID
                }
            }
            // Signed, but by a key we do not recognise (platform/debug key during development).
            // That is NOT the same as "valid" and must never be reported as such.
            return SignatureState.UNRECOGNISED_KEY
        } catch (t: Throwable) {
            return SignatureState.CHECK_FAILED
        }
    }

    /** True ONLY for a recognised official signing key. */
    fun verifyApkSignature(context: Context): Boolean =
        verifyApkSignatureState(context) == SignatureState.VALID

    fun isDebuggerAttached(): Boolean {
        if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) return true
        return try {
            val file = File("/proc/self/status")
            if (file.exists()) {
                val reader = BufferedReader(FileReader(file))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    if (line?.startsWith("TracerPid:") == true) {
                        val pid = line?.substringAfter("TracerPid:")?.trim()?.toIntOrNull() ?: 0
                        reader.close()
                        return pid > 0
                    }
                }
                reader.close()
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    fun isHookFrameworkDetected(): Boolean {
        try {
            // 1. Stack trace inspection for Frida / Xposed frames
            val trace = Thread.currentThread().stackTrace
            for (elem in trace) {
                val cls = elem.className.lowercase()
                if (cls.contains("frida") || cls.contains("xposed") || cls.contains("cydia") || cls.contains("substrate")) {
                    return true
                }
            }

            // 2. Memory mapping scan for injected instrumentation libraries
            val mapsFile = File("/proc/self/maps")
            if (mapsFile.exists()) {
                val reader = BufferedReader(FileReader(mapsFile))
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line?.lowercase() ?: ""
                    if (l.contains("frida-agent") || l.contains("xposed.bridge") || l.contains("libgadget.so")) {
                        reader.close()
                        return true
                    }
                }
                reader.close()
            }
        } catch (_: Throwable) {}
        return false
    }

    fun getSecurityReport(context: Context): SecurityReport {
        val sigState = verifyApkSignatureState(context)
        val debugAttached = isDebuggerAttached()
        val hookDetected = isHookFrameworkDetected()
        val isSecure = sigState == SignatureState.VALID && !debugAttached && !hookDetected

        return SecurityReport(
            isSecure = isSecure,
            signatureState = sigState,
            debuggerAttached = debugAttached,
            hookDetected = hookDetected,
            // "—" when the build reports no ABI; it used to assert "arm64-v8a".
            architecture = Build.SUPPORTED_ABIS.firstOrNull() ?: "—",
            securityPatch = Build.VERSION.SECURITY_PATCH ?: "—"
        )
    }
}

data class SecurityReport(
    val isSecure: Boolean,
    val signatureState: MikuSecuritySentinel.SignatureState,
    val debuggerAttached: Boolean,
    val hookDetected: Boolean,
    val architecture: String,
    val securityPatch: String
) {
    /** Only a recognised official key counts as valid — never "the check did not run". */
    val signatureValid: Boolean
        get() = signatureState == MikuSecuritySentinel.SignatureState.VALID
}
