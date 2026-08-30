package com.miku.player.visualizer

/**
 * Milkdrop-inspired GLSL ES 2.0 fragment presets for [MikuShaderVisualizerView]. Each preset is a
 * single full-screen fragment shader driven by:
 *   uAudio   — a 64x2 LUMINANCE texture: row 0 (v=0.25) = FFT magnitudes 0..1 across the band,
 *              row 1 (v=0.75) = the raw waveform, 0.5 = silence (decoded by wave()).
 *   uBass / uMid / uTreble — smoothed band energies 0..~1.5; uBeat — bass-onset flash 0..1 that decays.
 *   uAccent / uAccent2     — the Now Playing palette (album-art dynamic color, Miku teal fallback).
 * Every preset is cheap on the SM6225's Adreno 610 at the reduced render-buffer size the view
 * picks (see MikuShaderGLSurfaceView.onSizeChanged): a few sin()/texture taps per pixel, no loops
 * past 4 iterations, no dependent branching.
 */
enum class ShaderPreset(val title: String, val fragment: String) {
    SPECTRUM_BARS("Spectrum Bars", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            float nb = 48.0;
            float bx = floor(uv.x * nb);
            float f = fft((bx + 0.5) / nb * 0.85);
            float h = pow(f, 0.8) * 0.92 + 0.025;
            float cy = abs(uv.y - 0.5) * 2.0;
            float inBar = step(cy, h);
            float gx = fract(uv.x * nb);
            float edge = smoothstep(0.0, 0.14, gx) * smoothstep(1.0, 0.86, gx);
            float t = clamp(cy / max(h, 0.01), 0.0, 1.0);
            vec3 col = mix(uAccent, uAccent2, t);
            float glow = exp(-max(cy - h, 0.0) * 14.0) * 0.4;
            float cap = smoothstep(0.03, 0.0, abs(cy - h)) * inBar;
            vec3 c = col * (inBar * edge * (0.55 + 0.45 * (1.0 - t))) + col * glow + vec3(1.0) * cap * 0.5;
            c += vec3(0.010, 0.030, 0.035) + uAccent * uBeat * 0.07;
            gl_FragColor = vec4(c, 1.0);
        }
    """),

    RADIAL_WAVES("Radial Waves", """
        void main() {
            vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y;
            float r = length(p);
            float a = atan(p.y, p.x);
            float ang = a / 6.2831853 + 0.5;
            float w = wave(fract(ang * 2.0)) * (0.05 + uBass * 0.09);
            float f = fft(fract(ang + uTime * 0.02)) * 0.22;
            float ring = abs(r - (0.27 + w + f));
            float line = smoothstep(0.018, 0.0, ring) + exp(-ring * 24.0) * 0.55;
            float rings = sin(r * 42.0 - uTime * 3.0 + uBass * 5.0) * 0.5 + 0.5;
            rings = pow(rings, 6.0) * smoothstep(0.6, 0.08, r) * 0.28;
            vec3 col = mix(uAccent2, uAccent, ang);
            vec3 c = col * line + uAccent * rings * (0.4 + uMid) + vec3(0.02, 0.04, 0.05) * (1.0 - r);
            c += uAccent * uBeat * exp(-r * 4.5) * 0.45;
            gl_FragColor = vec4(c, 1.0);
        }
    """),

    PLASMA_TUNNEL("Plasma Tunnel", """
        void main() {
            vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y;
            float r = length(p) + 0.001;
            float a = atan(p.y, p.x);
            float depth = 0.25 / r + uTime * (0.8 + uBass * 1.6);
            float band = fft(fract(a / 6.2831853 + 0.5) * 0.9);
            float pl = sin(depth * 6.0 + a * 4.0) + sin(depth * 3.0 - a * 3.0 + uTime) + sin(a * 6.0 + depth * 2.0 + band * 6.0);
            pl = pl / 3.0 * 0.5 + 0.5;
            vec3 col = mix(uAccent2, uAccent, pl);
            float vig = smoothstep(0.0, 0.26, r);
            float bright = (0.32 + band * 0.95 + uMid * 0.4) * vig;
            vec3 c = col * bright + uAccent * uBeat * 0.16 * vig;
            gl_FragColor = vec4(c * min(r * 3.0, 1.0), 1.0);
        }
    """),

    MIKU_PARTICLES("Miku Particle Field", """
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        void main() {
            vec2 p = (gl_FragCoord.xy - 0.5 * uRes) / uRes.y;
            vec3 c = vec3(0.010, 0.025, 0.030);
            for (int layer = 0; layer < 3; layer++) {
                float fl = float(layer);
                float scale = 7.0 + fl * 5.0;
                float speed = 0.05 + fl * 0.04 + uBass * 0.16;
                vec2 q = p * scale + vec2(uTime * speed * 0.3, uTime * speed);
                vec2 cell = floor(q);
                vec2 fr = fract(q) - 0.5;
                float h = hash(cell + fl * 7.0);
                vec2 off = (vec2(hash(cell + 1.3), hash(cell + 2.7)) - 0.5) * 0.6;
                float twinkle = 0.5 + 0.5 * sin(uTime * (1.0 + h * 3.0) + h * 20.0);
                float e = fft(h * 0.8);
                float d = length(fr - off);
                float size = 0.03 + e * 0.12 + uBeat * 0.03 * step(0.7, h);
                float glow = size / (d * d + 0.002) * 0.02;
                vec3 col = mix(uAccent, uAccent2, step(0.75, h));
                c += col * glow * (0.4 + twinkle * 0.6) * (0.6 + fl * 0.2);
            }
            c *= smoothstep(1.25, 0.3, length(p));
            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """),

    AURORA_RIBBONS("Aurora Ribbons", """
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            vec3 c = vec3(0.010, 0.020, 0.030);
            for (int i = 0; i < 4; i++) {
                float fi = float(i);
                float y = 0.5 + wave(fract(uv.x + fi * 0.13 + uTime * 0.03)) * (0.08 + uBass * 0.12)
                        + sin(uv.x * 6.0 + uTime * (0.6 + fi * 0.2) + fi) * 0.08 * (1.0 + uMid);
                float d = abs(uv.y - y);
                float ribbon = exp(-d * (30.0 + fi * 10.0)) * (0.6 + fft(fi * 0.2 + 0.05) * 1.2);
                vec3 col = mix(uAccent, uAccent2, fi / 3.0);
                c += col * ribbon;
            }
            c += uAccent * uBeat * 0.06;
            gl_FragColor = vec4(min(c, vec3(1.0)), 1.0);
        }
    """);

    companion object {
        const val VERTEX = """
            attribute vec2 aPos;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); }
        """

        /** Shared header prepended to every preset's fragment source. */
        const val HEADER = """
            precision mediump float;
            uniform vec2 uRes;
            uniform float uTime;
            uniform float uBass;
            uniform float uMid;
            uniform float uTreble;
            uniform float uBeat;
            uniform vec3 uAccent;
            uniform vec3 uAccent2;
            uniform sampler2D uAudio;
            float fft(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.25)).r; }
            float wave(float x) { return texture2D(uAudio, vec2(clamp(x, 0.0, 1.0), 0.75)).r * 2.0 - 1.0; }
        """
    }
}
