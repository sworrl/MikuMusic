#include <jni.h>
#include <android/log.h>
#include <string>
#include <exception>
#include <mutex>
#include <projectM-4/projectM.h>
#include <projectM-4/playlist.h>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  "projectM-native", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "projectM-native", __VA_ARGS__)

static projectm_handle          s_pm       = nullptr;
static projectm_playlist_handle s_playlist = nullptr;
// Serializes all access to the globals. During a fullscreen transition two GL threads can
// briefly coexist (old surface tearing down, new one initializing); without this, the old
// thread's render can run while the new thread destroys+recreates the handle → use-after-free.
static std::mutex               s_lock;
// Remembered playlist position so re-creating the handle for a new GL surface (e.g. entering
// fullscreen) can restore the SAME preset instead of jumping to a random one.
static int                      s_lastPos  = -1;
// Frames left on the projectM attribution splash (the built-in "idle://" M logo). Counted down in
// nativeRender; when it reaches zero the playlist takes over. Only armed on a FRESH open, never on
// a surface re-create (fullscreen toggle), so the logo does not reappear every time you toggle.
static int                      s_splashFrames = 0;

extern "C" {

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeInit(JNIEnv* /*env*/, jobject /*thiz*/) {
    std::lock_guard<std::mutex> g(s_lock);
    // A new GL surface (e.g. entering fullscreen) has a fresh GL context; projectM's
    // GL objects are context-bound, so tear down any old instance and rebuild here.
    if (s_pm) {
        if (s_playlist) {
            s_lastPos = (int) projectm_playlist_get_position(s_playlist);   // remember before teardown
            projectm_playlist_destroy(s_playlist); s_playlist = nullptr;
        }
        projectm_destroy(s_pm);
        s_pm = nullptr;
    }
    s_pm = projectm_create();
    if (!s_pm) { LOGE("projectm_create() returned null"); return; }
    // Lower mesh = far fewer per-pixel-equation evaluations per frame → the key perf lever on the
    // M500's weak GPU. 24x18 lets even heavy presets run without dropping any from the pack.
    projectm_set_mesh_size(s_pm, 24, 18);
    projectm_set_fps(s_pm, 60);
    projectm_set_aspect_correction(s_pm, true);
    projectm_set_preset_duration(s_pm, 30.0);
    projectm_set_soft_cut_duration(s_pm, 3.0);

    // Hard cuts are beat-triggered preset changes. They were ON here with the sensitivity
    // THRESHOLD set to 1.0 — half of projectM's 2.0 default, so it fired on half the volume
    // delta — and with beat sensitivity pushed to 1.2, while the minimum-display-time gate
    // (projectm_set_hard_cut_duration) was never set at all. On anything percussive that meant a
    // new preset almost every beat.
    //
    // They stay off. A hard cut costs a full preset load, which on this GPU means recompiling
    // shaders mid-frame — it is both the ugliest and the most expensive way to change preset, and
    // the 30 s soft rotation above already keeps the view moving. The duration is still set above
    // the preset duration because projectM's own docs give that as the way to disable hard cuts,
    // so the gate holds even if something re-enables the flag.
    projectm_set_hard_cut_enabled(s_pm, false);
    projectm_set_hard_cut_duration(s_pm, 60.0);
    projectm_set_hard_cut_sensitivity(s_pm, 3.0f);
    projectm_set_beat_sensitivity(s_pm, 1.0f);

    // ATTRIBUTION SPLASH: load projectM's built-in idle preset first, which draws the projectM "M"
    // logo. projectM 4.2.0 exposes it through the special filename "idle://" (see
    // projectM-4/core.h). It shows for a moment when the visualizer opens, then the playlist takes
    // over on the first advance, so the user is told which engine is rendering this. libprojectM is
    // LGPL and calling it out is the right thing to do as well as a nicer hand-off than a cold cut
    // straight into a preset.
    projectm_load_preset_file(s_pm, "idle://", false);
    s_splashFrames = (s_lastPos >= 0) ? 0 : 120;   // ~2 s at 60 fps on a fresh open only

    s_playlist = projectm_playlist_create(s_pm);
    if (s_playlist) {
        projectm_playlist_set_shuffle(s_playlist, true);
        projectm_playlist_set_retry_count(s_playlist, 5);   // skip presets that fail to load
    }
    LOGI("libprojectM 4.2.0 initialized (idle:// logo splash shown first)");
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeResize(JNIEnv* /*env*/, jobject /*thiz*/, jint w, jint h) {
    std::lock_guard<std::mutex> g(s_lock);
    if (s_pm && w > 0 && h > 0)
        projectm_set_window_size(s_pm, (size_t) w, (size_t) h);
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeFeedAudio(JNIEnv* env, jobject /*thiz*/,
                                                    jfloatArray /*fft*/, jfloatArray pcm) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_pm || pcm == nullptr) return;
    jsize n = env->GetArrayLength(pcm);
    if (n <= 0) return;
    jfloat* samples = env->GetFloatArrayElements(pcm, nullptr);
    if (samples) {
        projectm_pcm_add_float(s_pm, samples, (unsigned int) n, PROJECTM_MONO);
        env->ReleaseFloatArrayElements(pcm, samples, JNI_ABORT);
    }
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeRender(JNIEnv* /*env*/, jobject /*thiz*/,
                                                 jfloat /*t*/, jint /*preset*/,
                                                 jfloat /*bass*/, jfloat /*treble*/) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_pm) return;
    // Hold the projectM logo for its few frames, then hand over to the playlist.
    if (s_splashFrames > 0 && --s_splashFrames == 0 && s_playlist) {
        try { projectm_playlist_play_next(s_playlist, true); } catch (...) { LOGE("splash handoff threw"); }
    }
    try { projectm_opengl_render_frame(s_pm); }
    catch (const std::exception& e) { LOGE("render threw: %s", e.what()); }
    catch (...) { LOGE("render threw (unknown)"); }
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeSetPreset(JNIEnv* /*env*/, jobject /*thiz*/, jint /*preset*/) {
    // No-op by design: preset changes come from the playlist auto-cycle + gestures (next/prev).
    // Advancing here made mounting a surface (e.g. entering fullscreen) jump to a new preset.
}

static std::string basename_noext(const char* p) {
    if (!p) return "";
    std::string s(p);
    auto slash = s.find_last_of("/\\");
    if (slash != std::string::npos) s = s.substr(slash + 1);
    auto dot = s.find_last_of('.');
    if (dot != std::string::npos) s = s.substr(0, dot);
    return s;
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeNext(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist) return;
    try { projectm_playlist_play_next(s_playlist, true); } catch (...) { LOGE("next threw"); }
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativePrev(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist) return;
    try { projectm_playlist_play_previous(s_playlist, true); } catch (...) { LOGE("prev threw"); }
}

JNIEXPORT jboolean JNICALL
Java_com_miku_player_ProjectMNative_nativeToggleLock(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_pm) return JNI_FALSE;
    try {
        bool locked = projectm_get_preset_locked(s_pm);
        projectm_set_preset_locked(s_pm, !locked);
        return (!locked) ? JNI_TRUE : JNI_FALSE;
    } catch (...) { LOGE("toggleLock threw"); return JNI_FALSE; }
}

JNIEXPORT jboolean JNICALL
Java_com_miku_player_ProjectMNative_nativeIsLocked(JNIEnv*, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    return (s_pm && projectm_get_preset_locked(s_pm)) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_miku_player_ProjectMNative_nativePresetName(JNIEnv* env, jobject) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist) return env->NewStringUTF("");
    try {
        uint32_t pos = projectm_playlist_get_position(s_playlist);
        char* path = projectm_playlist_item(s_playlist, pos);
        std::string name = basename_noext(path);
        if (path) projectm_playlist_free_string(path);
        return env->NewStringUTF(name.c_str());
    } catch (...) { return env->NewStringUTF(""); }
}

/**
 * The REAL library version, straight out of libprojectM. Not a constant in our source: a hardcoded
 * "4.2.0" would keep saying 4.2.0 after the vendored submodule moved, and this string is shown to
 * the user as attribution, so it has to be true. projectm_get_version_string() allocates, and the
 * header says to hand the pointer back to projectm_free_string().
 */
JNIEXPORT jstring JNICALL
Java_com_miku_player_ProjectMNative_nativeVersion(JNIEnv* env, jobject) {
    try {
        char* v = projectm_get_version_string();
        std::string out = v ? v : "";
        if (v) projectm_free_string(v);
        return env->NewStringUTF(out.c_str());
    } catch (...) { return env->NewStringUTF(""); }
}

/** Git revision the vendored library was built from. Shown next to the version in the debug row. */
JNIEXPORT jstring JNICALL
Java_com_miku_player_ProjectMNative_nativeVcsVersion(JNIEnv* env, jobject) {
    try {
        char* v = projectm_get_vcs_version_string();
        std::string out = v ? v : "";
        if (v) projectm_free_string(v);
        return env->NewStringUTF(out.c_str());
    } catch (...) { return env->NewStringUTF(""); }
}

JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeSetBeatSensitivity(JNIEnv*, jobject, jfloat s) {
    std::lock_guard<std::mutex> g(s_lock);
    if (s_pm) projectm_set_beat_sensitivity(s_pm, s);
}

// Load a directory of .milk presets into the playlist (called with the extracted assets path).
JNIEXPORT void JNICALL
Java_com_miku_player_ProjectMNative_nativeLoadPresets(JNIEnv* env, jobject /*thiz*/, jstring dir) {
    std::lock_guard<std::mutex> g(s_lock);
    if (!s_playlist || dir == nullptr) return;
    const char* path = env->GetStringUTFChars(dir, nullptr);
    if (path) {
        uint32_t added = projectm_playlist_add_path(s_playlist, path, true, false);
        LOGI("loaded %u presets from %s", added, path);
        env->ReleaseStringUTFChars(dir, path);
        projectm_playlist_set_shuffle(s_playlist, true);
        // Restore the same preset across a surface re-create (fullscreen); else start fresh.
        if (s_lastPos >= 0 && (uint32_t) s_lastPos < added) {
            projectm_playlist_set_position(s_playlist, (uint32_t) s_lastPos, true);
        } else if (s_splashFrames <= 0) {
            projectm_playlist_play_next(s_playlist, true);
        }
        // else: the attribution splash is still up; nativeRender advances when it expires.
        // Without this the playlist would load a preset here and the logo would never be seen.
    }
}

} // extern "C"
