#ifndef PROJECTM_ENGINE_H
#define PROJECTM_ENGINE_H

#include <GLES2/gl2.h>
#include <vector>

struct MilkdropPreset {
    const char* name;
    float zoom, rot, cx, cy, dx, dy, warp, sx, sy;
    float wave_r, wave_g, wave_b, wave_a, wave_x, wave_y;
    int wave_mode;
    float decay;
    float ob_size, ob_r, ob_g, ob_b;
    float ib_size, ib_r, ib_g, ib_b;
    float echo_zoom, echo_alpha;
    int echo_orient;
};

class ProjectMEngine {
public:
    ProjectMEngine();
    ~ProjectMEngine();

    void init();
    void resize(int width, int height);
    void render(float timeSec, int preset, float bass, float treble);
    void setPreset(int preset);
    void feedAudio(const float* fft, int fftSize, const float* pcm, int pcmSize);

private:
    int m_width;
    int m_height;
    int m_currentPreset;

    GLuint m_program;
    GLuint m_positionHandle;
    GLuint m_timeHandle;
    GLuint m_resolutionHandle;
    GLuint m_presetHandle;
    GLuint m_bassHandle;
    GLuint m_trebleHandle;
    GLuint m_fftHandle;
    GLuint m_waveformHandle;
    GLuint m_prevTextureHandle;
    GLuint m_warpParamsHandle;
    GLuint m_waveParamsHandle;
    GLuint m_decayHandle;

    GLuint m_vertexBuffer;

    // Milkdrop FBO Ping-Pong Feedback loop
    GLuint m_fbo[2];
    GLuint m_fboTexture[2];
    int m_fboIndex;

    std::vector<float> m_fftData;
    std::vector<float> m_pcmData;

    void initFBO(int width, int height);
    void destroyFBO();

    GLuint compileShader(GLenum type, const char* source);
    GLuint createProgram(const char* vertSource, const char* fragSource);
};

#endif // PROJECTM_ENGINE_H
