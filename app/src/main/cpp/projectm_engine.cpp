#include "projectm_engine.h"
#include <android/log.h>
#include <cmath>
#include <cstring>
#include <algorithm>

#define LOG_TAG "ProjectM-Native"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static const MilkdropPreset PRESETS[] = {
    // 0: Cyber Tunnel (Geiss - Tokamak)
    {"Milkdrop: Cyber Tunnel", 1.02f, 0.03f, 0.5f, 0.5f, 0.0f, 0.0f, 0.25f, 1.0f, 1.0f,
     0.22f, 0.77f, 0.73f, 0.9f, 0.5f, 0.5f, 1, 0.96f, 0.01f, 0.2f, 0.77f, 0.73f, 0.01f, 1.0f, 0.81f, 0.41f, 1.0f, 0.3f, 0},
    // 1: Hyperdrive Nebula (Unusual - Solar Flare)
    {"Milkdrop: Hyperdrive Nebula", 0.98f, -0.05f, 0.5f, 0.5f, 0.0f, 0.0f, 0.40f, 1.0f, 1.0f,
     1.00f, 0.37f, 0.63f, 0.95f, 0.5f, 0.5f, 2, 0.94f, 0.02f, 1.0f, 0.37f, 0.63f, 0.02f, 0.22f, 0.77f, 0.73f, 1.0f, 0.4f, 1},
    // 2: Plasma Wave (Rovastar - Harlequin)
    {"Milkdrop: Plasma Wave", 1.00f, 0.00f, 0.5f, 0.5f, 0.0f, 0.0f, 0.10f, 1.0f, 1.0f,
     1.00f, 0.81f, 0.41f, 0.85f, 0.5f, 0.5f, 0, 0.92f, 0.01f, 1.0f, 0.81f, 0.41f, 0.01f, 1.0f, 0.37f, 0.63f, 1.0f, 0.2f, 0},
    // 3: 3D Vortex (Flexi - Starburst)
    {"Milkdrop: 3D Vortex", 1.05f, 0.08f, 0.5f, 0.5f, 0.0f, 0.0f, 0.60f, 1.0f, 1.0f,
     0.22f, 0.77f, 0.73f, 0.9f, 0.5f, 0.5f, 3, 0.95f, 0.02f, 0.22f, 0.77f, 0.73f, 0.02f, 1.0f, 0.81f, 0.41f, 1.0f, 0.5f, 2},
    // 4: Supernova Pulse (Evil - Pulsar)
    {"Milkdrop: Supernova Pulse", 0.95f, 0.00f, 0.5f, 0.5f, 0.0f, 0.0f, 0.80f, 1.0f, 1.0f,
     1.00f, 0.37f, 0.63f, 1.0f, 0.5f, 0.5f, 4, 0.90f, 0.03f, 1.0f, 0.37f, 0.63f, 0.03f, 1.0f, 0.81f, 0.41f, 1.0f, 0.6f, 0},
    // 5: Neon Fluid Waves (Zylot - Fluid Dynamic)
    {"Milkdrop: Neon Fluid Waves", 1.01f, 0.02f, 0.5f, 0.5f, 0.0f, 0.0f, 0.30f, 1.0f, 1.0f,
     0.22f, 0.77f, 0.73f, 0.95f, 0.5f, 0.5f, 5, 0.97f, 0.01f, 0.22f, 0.77f, 0.73f, 0.01f, 1.0f, 0.37f, 0.63f, 1.0f, 0.35f, 1}
};

static const char* VERTEX_SHADER = R"glsl(
attribute vec4 a_Position;
varying vec2 v_UV;
void main() {
    v_UV = (a_Position.xy + 1.0) * 0.5;
    gl_Position = a_Position;
}
)glsl";

static const char* FRAGMENT_SHADER = R"glsl(
precision highp float;
varying vec2 v_UV;
uniform float u_Time;
uniform vec2 u_Resolution;
uniform int u_Preset;
uniform float u_Bass;
uniform float u_Treble;
uniform float u_Decay;
uniform vec4 u_WarpParams; // zoom, rot, warp, unused
uniform vec4 u_WaveParams; // wave_r, wave_g, wave_b, wave_a
uniform sampler2D u_PrevTexture;
uniform float u_FFT[64];
uniform float u_Waveform[64];

const vec3 c_Teal = vec3(0.223, 0.772, 0.733);
const vec3 c_Pink = vec3(1.000, 0.372, 0.635);
const vec3 c_Gold = vec3(1.000, 0.811, 0.419);

void main() {
    vec2 st = (gl_FragCoord.xy - 0.5 * u_Resolution.xy) / u_Resolution.y;
    vec2 uv = v_UV;
    float t = u_Time * 0.8;

    // Milkdrop Per-Pixel Warp Mesh & Motion Decay Feedback
    vec2 d = uv - vec2(0.5);
    float r = length(d);
    float a = atan(d.y, d.x);

    // Dynamic Milkdrop per-pixel displacement
    float zoom = u_WarpParams.x * (1.0 + u_Bass * 0.08);
    float rot = u_WarpParams.y * (1.0 + sin(t * 1.5) * 0.5);
    float warp = u_WarpParams.z * (1.0 + u_Treble * 0.5);

    a += rot * 0.08 * sin(r * 12.0 - t * 2.0);
    r = pow(r, 1.0 - warp * 0.08 * sin(a * 4.0 + t));
    r *= (1.0 / zoom);

    vec2 warpedUV = vec2(0.5) + vec2(cos(a), sin(a)) * r;
    vec4 prevColor = texture2D(u_PrevTexture, clamp(warpedUV, 0.0, 1.0)) * u_Decay;

    vec3 col = prevColor.rgb * 0.96;

    if (u_Preset == 0) {
        // Milkdrop Cyber Tunnel
        float tunnel = 0.15 / (length(st) + 0.04);
        float grid = sin(a * 16.0) * sin(tunnel * 10.0 - t * 5.0);
        grid = smoothstep(0.3, 0.95, grid + u_Bass * 0.5);
        vec3 tunnelColor = mix(c_Teal, c_Pink, sin(tunnel - t) * 0.5 + 0.5);
        col += tunnelColor * grid * (0.8 + u_Bass * 1.2);
    } else if (u_Preset == 1) {
        // Milkdrop Hyperdrive Nebula
        vec2 nuv = st * (2.2 - u_Bass * 0.6);
        float nd = length(nuv);
        for (int i = 1; i < 4; i++) {
            float fi = float(i);
            nuv.x += sin(nuv.y * 3.5 + t * fi) * 0.18;
            nuv.y += cos(nuv.x * 3.5 + t * fi) * 0.18;
        }
        float wave = abs(sin(nuv.x * 10.0 + nuv.y * 10.0 + t * 2.5));
        col += mix(c_Teal, c_Pink, wave) * (0.28 / (nd + 0.18)) * (1.0 + u_Bass * 1.2);
    } else if (u_Preset == 2) {
        // Milkdrop Plasma Wave Spectrum
        float x = uv.x * 63.0;
        int idx = int(clamp(x, 0.0, 63.0));
        float band = u_FFT[idx];
        float specY = (uv.y - 0.5) * 2.0;
        float dist = abs(specY - (band - 0.5));
        float line = smoothstep(0.08, 0.0, dist);
        col += mix(c_Teal, c_Gold, band) * line * 2.2;
    } else if (u_Preset == 3) {
        // Milkdrop 3D Vortex
        float ring = sin(a * 10.0 + length(st) * 24.0 - t * 6.0);
        float glow = smoothstep(0.15, 0.85, ring + u_Treble);
        col += mix(c_Teal, c_Pink, sin(t + length(st) * 6.0) * 0.5 + 0.5) * glow * (0.22 / (length(st) + 0.08));
    } else if (u_Preset == 4) {
        // Milkdrop Supernova Explosion
        float pulse = sin(length(st) * 36.0 - t * 12.0 * (1.0 + u_Bass));
        pulse = smoothstep(0.65, 0.98, pulse);
        col += c_Gold * pulse * u_Bass * 1.8;
    } else {
        // Milkdrop Neon Fluid Waves
        float x = uv.x * 63.0;
        int idx = int(clamp(x, 0.0, 63.0));
        float band = u_FFT[idx];
        float waveY = sin(uv.x * 12.0 + t * 4.0) * 0.15 * (1.0 + u_Bass);
        float specY = (uv.y - 0.5 - waveY) * 2.0;
        float dist = abs(specY - (band * 1.2 - 0.5));
        float bar = smoothstep(0.08, 0.0, dist);
        vec3 waveColor = mix(c_Teal, c_Pink, sin(uv.x * 6.28 + t) * 0.5 + 0.5);
        col += waveColor * bar * 2.2;
    }

    // Audio-reactive Milkdrop Oscilloscope Waveform overlay
    float waveX = uv.x * 63.0;
    int wIdx = int(clamp(waveX, 0.0, 63.0));
    float sampleY = u_Waveform[wIdx];
    float wDist = abs((uv.y - 0.5) * 2.0 - sampleY);
    float wGlow = smoothstep(0.04, 0.0, wDist);
    col += u_WaveParams.rgb * wGlow * u_WaveParams.a * 1.5;

    gl_FragColor = vec4(col, 1.0);
}
)glsl";

ProjectMEngine::ProjectMEngine()
    : m_width(1), m_height(1), m_currentPreset(0),
      m_program(0), m_positionHandle(0), m_timeHandle(0),
      m_resolutionHandle(0), m_presetHandle(0), m_bassHandle(0),
      m_trebleHandle(0), m_fftHandle(0), m_waveformHandle(0),
      m_prevTextureHandle(0), m_warpParamsHandle(0), m_waveParamsHandle(0),
      m_decayHandle(0), m_vertexBuffer(0), m_fboIndex(0) {
    m_fbo[0] = m_fbo[1] = 0;
    m_fboTexture[0] = m_fboTexture[1] = 0;
    m_fftData.resize(64, 0.0f);
    m_pcmData.resize(64, 0.0f);
}

ProjectMEngine::~ProjectMEngine() {
    destroyFBO();
    if (m_program != 0) {
        glDeleteProgram(m_program);
    }
}

void ProjectMEngine::destroyFBO() {
    if (m_fbo[0] != 0) {
        glDeleteFramebuffers(2, m_fbo);
        glDeleteTextures(2, m_fboTexture);
        m_fbo[0] = m_fbo[1] = 0;
        m_fboTexture[0] = m_fboTexture[1] = 0;
    }
}

void ProjectMEngine::initFBO(int width, int height) {
    destroyFBO();
    glGenFramebuffers(2, m_fbo);
    glGenTextures(2, m_fboTexture);

    for (int i = 0; i < 2; i++) {
        glBindTexture(GL_TEXTURE_2D, m_fboTexture[i]);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0, GL_RGBA, GL_UNSIGNED_BYTE, nullptr);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);

        glBindFramebuffer(GL_FRAMEBUFFER, m_fbo[i]);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, m_fboTexture[i], 0);

        glClearColor(0.02f, 0.06f, 0.08f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindTexture(GL_TEXTURE_2D, 0);
    m_fboIndex = 0;
}

GLuint ProjectMEngine::compileShader(GLenum type, const char* source) {
    GLuint shader = glCreateShader(type);
    glShaderSource(shader, 1, &source, nullptr);
    glCompileShader(shader);
    GLint compiled = 0;
    glGetShaderiv(shader, GL_COMPILE_STATUS, &compiled);
    if (!compiled) {
        GLint infoLen = 0;
        glGetShaderiv(shader, GL_INFO_LOG_LENGTH, &infoLen);
        if (infoLen > 0) {
            std::vector<char> buf(infoLen);
            glGetShaderInfoLog(shader, infoLen, nullptr, buf.data());
            LOGE("Shader compilation error: %s", buf.data());
        }
        glDeleteShader(shader);
        return 0;
    }
    return shader;
}

GLuint ProjectMEngine::createProgram(const char* vertSource, const char* fragSource) {
    GLuint vert = compileShader(GL_VERTEX_SHADER, vertSource);
    GLuint frag = compileShader(GL_FRAGMENT_SHADER, fragSource);
    if (vert == 0 || frag == 0) return 0;

    GLuint program = glCreateProgram();
    glAttachShader(program, vert);
    glAttachShader(program, frag);
    glLinkProgram(program);

    GLint linked = 0;
    glGetProgramiv(program, GL_LINK_STATUS, &linked);
    if (!linked) {
        LOGE("Program link error");
        glDeleteProgram(program);
        return 0;
    }
    return program;
}

void ProjectMEngine::init() {
    glClearColor(0.02f, 0.06f, 0.08f, 1.0f);
    m_program = createProgram(VERTEX_SHADER, FRAGMENT_SHADER);

    m_positionHandle = glGetAttribLocation(m_program, "a_Position");
    m_timeHandle = glGetUniformLocation(m_program, "u_Time");
    m_resolutionHandle = glGetUniformLocation(m_program, "u_Resolution");
    m_presetHandle = glGetUniformLocation(m_program, "u_Preset");
    m_bassHandle = glGetUniformLocation(m_program, "u_Bass");
    m_trebleHandle = glGetUniformLocation(m_program, "u_Treble");
    m_decayHandle = glGetUniformLocation(m_program, "u_Decay");
    m_warpParamsHandle = glGetUniformLocation(m_program, "u_WarpParams");
    m_waveParamsHandle = glGetUniformLocation(m_program, "u_WaveParams");
    m_prevTextureHandle = glGetUniformLocation(m_program, "u_PrevTexture");
    m_fftHandle = glGetUniformLocation(m_program, "u_FFT");
    m_waveformHandle = glGetUniformLocation(m_program, "u_Waveform");

    float vertices[] = {
        -1.0f, -1.0f,
         1.0f, -1.0f,
        -1.0f,  1.0f,
         1.0f,  1.0f
    };
    glGenBuffers(1, &m_vertexBuffer);
    glBindBuffer(GL_ARRAY_BUFFER, m_vertexBuffer);
    glBufferData(GL_ARRAY_BUFFER, sizeof(vertices), vertices, GL_STATIC_DRAW);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    LOGI("ProjectM Native Milkdrop Engine initialized successfully");
}

void ProjectMEngine::resize(int width, int height) {
    m_width = width;
    m_height = height;
    glViewport(0, 0, width, height);
    initFBO(width, height);
}

void ProjectMEngine::render(float timeSec, int preset, float bass, float treble) {
    if (m_program == 0) return;

    int pIdx = std::clamp(preset, 0, 5);
    const MilkdropPreset& p = PRESETS[pIdx];

    int currentFBO = m_fboIndex;
    int prevFBO = 1 - m_fboIndex;

    // 1. Render Milkdrop frame to ping-pong FBO
    glBindFramebuffer(GL_FRAMEBUFFER, m_fbo[currentFBO]);
    glViewport(0, 0, m_width, m_height);

    glUseProgram(m_program);

    glBindBuffer(GL_ARRAY_BUFFER, m_vertexBuffer);
    glEnableVertexAttribArray(m_positionHandle);
    glVertexAttribPointer(m_positionHandle, 2, GL_FLOAT, GL_FALSE, 0, (void*)0);

    glUniform1f(m_timeHandle, timeSec);
    glUniform2f(m_resolutionHandle, (float)m_width, (float)m_height);
    glUniform1i(m_presetHandle, pIdx);
    glUniform1f(m_bassHandle, bass);
    glUniform1f(m_trebleHandle, treble);
    glUniform1f(m_decayHandle, p.decay);

    glUniform4f(m_warpParamsHandle, p.zoom, p.rot, p.warp, 0.0f);
    glUniform4f(m_waveParamsHandle, p.wave_r, p.wave_g, p.wave_b, p.wave_a);

    glActiveTexture(GL_TEXTURE0);
    glBindTexture(GL_TEXTURE_2D, m_fboTexture[prevFBO]);
    glUniform1i(m_prevTextureHandle, 0);

    glUniform1fv(m_fftHandle, 64, m_fftData.data());
    glUniform1fv(m_waveformHandle, 64, m_pcmData.data());

    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    // 2. Blit FBO to screen
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glViewport(0, 0, m_width, m_height);
    glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);

    glDisableVertexAttribArray(m_positionHandle);
    glBindBuffer(GL_ARRAY_BUFFER, 0);

    // Swap FBO ping-pong index for next frame motion decay
    m_fboIndex = prevFBO;
}

void ProjectMEngine::setPreset(int preset) {
    m_currentPreset = std::clamp(preset, 0, 5);
}

void ProjectMEngine::feedAudio(const float* fft, int fftSize, const float* pcm, int pcmSize) {
    if (fft && fftSize > 0) {
        int count = std::min(64, fftSize);
        std::memcpy(m_fftData.data(), fft, count * sizeof(float));
    }
    if (pcm && pcmSize > 0) {
        int count = std::min(64, pcmSize);
        std::memcpy(m_pcmData.data(), pcm, count * sizeof(float));
    }
}
