package com.miku.player.api

/**
 * Hatsune Miku Cyberpunk Web Remote & TV Big-Screen Stage Application.
 * 
 * Embedded zero-dependency HTML5/CSS3/JavaScript SPA served directly by [MikuApiServer].
 * Provides a Spotify-Connect facsimile for phones, tablets, laptops, and TV sound systems.
 */
object MikuWebRemoteHtml {

    fun getRemoteHtml(isTvMode: Boolean = false): String {
        return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
    <meta name="theme-color" content="#030d14">
    <meta name="apple-mobile-web-app-capable" content="yes">
    <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent">
    <title>Miku Connect · ${if (isTvMode) "TV Stage" else "Remote"}</title>
    <link rel="preconnect" href="https://fonts.googleapis.com">
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
    <link href="https://fonts.googleapis.com/css2?family=Audiowide&family=Rajdhani:wght@500;600;700&display=swap" rel="stylesheet">
    <style>
        :root {
            --miku-cyan: #00E5FF;
            --miku-teal: #39C5BB;
            --miku-pink: #FF1774;
            --miku-purple: #9D4EDD;
            --bg-dark: #030D14;
            --card-bg: rgba(6, 22, 33, 0.75);
            --card-border: rgba(0, 229, 255, 0.35);
        }
        * {
            box-sizing: border-box;
            margin: 0;
            padding: 0;
            user-select: none;
            -webkit-user-select: none;
        }
        body {
            background-color: var(--bg-dark);
            color: #ffffff;
            font-family: 'Rajdhani', sans-serif;
            min-height: 100vh;
            display: flex;
            flex-direction: column;
            align-items: center;
            overflow-x: hidden;
            background-image: 
                radial-gradient(circle at 50% 10%, rgba(57, 197, 187, 0.15), transparent 50%),
                radial-gradient(circle at 80% 90%, rgba(255, 23, 116, 0.12), transparent 45%),
                linear-gradient(180deg, #02080D 0%, #030D14 50%, #010406 100%);
        }
        .header {
            width: 100%;
            max-width: ${if (isTvMode) "1200px" else "520px"};
            padding: 16px 20px;
            display: flex;
            justify-content: space-between;
            align-items: center;
            border-bottom: 1px solid rgba(0, 229, 255, 0.15);
            backdrop-filter: blur(12px);
            z-index: 10;
        }
        .brand {
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .brand-logo {
            width: 28px;
            height: 28px;
            border-radius: 50%;
            background: linear-gradient(135deg, var(--miku-teal), var(--miku-pink));
            box-shadow: 0 0 12px var(--miku-cyan);
        }
        .brand-title {
            font-family: 'Audiowide', cursive;
            font-size: 18px;
            letter-spacing: 1.5px;
            background: linear-gradient(90deg, #FFFFFF, var(--miku-cyan));
            -webkit-background-clip: text;
            -webkit-text-fill-color: transparent;
        }
        .device-badge {
            display: flex;
            align-items: center;
            gap: 6px;
            background: rgba(0, 229, 255, 0.12);
            border: 1px solid var(--card-border);
            padding: 4px 10px;
            border-radius: 12px;
            font-size: 12px;
            font-weight: 700;
            color: var(--miku-cyan);
            font-family: 'Audiowide', cursive;
        }
        .device-dot {
            width: 7px;
            height: 7px;
            border-radius: 50%;
            background-color: #00FF88;
            box-shadow: 0 0 8px #00FF88;
            animation: pulse 2s infinite;
        }
        @keyframes pulse {
            0%, 100% { opacity: 1; transform: scale(1); }
            50% { opacity: 0.4; transform: scale(0.85); }
        }
        .container {
            width: 100%;
            max-width: ${if (isTvMode) "1200px" else "520px"};
            padding: 20px;
            display: flex;
            flex-direction: ${if (isTvMode) "row" else "column"};
            align-items: center;
            gap: ${if (isTvMode) "50px" else "20px"};
            flex: 1;
            justify-content: center;
        }
        .art-wrapper {
            position: relative;
            width: ${if (isTvMode) "420px" else "280px"};
            height: ${if (isTvMode) "420px" else "280px"};
            display: flex;
            justify-content: center;
            align-items: center;
        }
        .art-glow {
            position: absolute;
            width: 100%;
            height: 100%;
            border-radius: 20px;
            background: radial-gradient(circle, rgba(0, 229, 255, 0.4), transparent 70%);
            filter: blur(25px);
            z-index: 1;
            transition: all 0.5s ease;
        }
        .art-img {
            position: relative;
            width: 100%;
            height: 100%;
            border-radius: 16px;
            object-fit: cover;
            border: 2px solid var(--card-border);
            box-shadow: 0 10px 30px rgba(0, 0, 0, 0.8);
            z-index: 2;
            background-color: #05141c;
        }
        .details-pane {
            width: 100%;
            display: flex;
            flex-direction: column;
            align-items: ${if (isTvMode) "flex-start" else "center"};
            text-align: ${if (isTvMode) "left" else "center"};
        }
        .track-title {
            font-family: 'Audiowide', cursive;
            font-size: ${if (isTvMode) "36px" else "22px"};
            font-weight: 700;
            color: #FFFFFF;
            margin-bottom: 6px;
            line-height: 1.2;
            max-width: 100%;
            overflow: hidden;
            text-overflow: ellipsis;
            white-space: nowrap;
        }
        .track-artist {
            font-size: ${if (isTvMode) "24px" else "16px"};
            font-weight: 700;
            color: var(--miku-teal);
            margin-bottom: 4px;
        }
        .track-album {
            font-size: ${if (isTvMode) "16px" else "13px"};
            color: rgba(255, 255, 255, 0.6);
            margin-bottom: 12px;
        }
        .badge-row {
            display: flex;
            gap: 8px;
            margin-bottom: 18px;
        }
        .tech-badge {
            background: rgba(0, 229, 255, 0.1);
            border: 1px solid rgba(0, 229, 255, 0.3);
            border-radius: 6px;
            padding: 3px 8px;
            font-size: 11px;
            font-family: 'Audiowide', cursive;
            color: #FFFFFF;
        }
        .tech-badge.hires {
            border-color: var(--miku-pink);
            background: rgba(255, 23, 116, 0.15);
            color: var(--miku-pink);
        }
        .progress-bar-container {
            width: 100%;
            display: flex;
            flex-direction: column;
            gap: 6px;
            margin-bottom: 20px;
        }
        .progress-track {
            width: 100%;
            height: 6px;
            background: rgba(255, 255, 255, 0.15);
            border-radius: 3px;
            position: relative;
            cursor: pointer;
        }
        .progress-fill {
            height: 100%;
            width: 0%;
            background: linear-gradient(90deg, var(--miku-cyan), var(--miku-pink));
            border-radius: 3px;
            position: relative;
        }
        .progress-fill::after {
            content: '';
            position: absolute;
            right: -5px;
            top: -4px;
            width: 14px;
            height: 14px;
            background: #FFFFFF;
            border-radius: 50%;
            box-shadow: 0 0 10px var(--miku-cyan);
        }
        .time-row {
            display: flex;
            justify-content: space-between;
            font-size: 13px;
            font-family: 'Audiowide', cursive;
            color: rgba(255, 255, 255, 0.7);
        }
        .controls-row {
            display: flex;
            align-items: center;
            justify-content: center;
            gap: ${if (isTvMode) "30px" else "18px"};
            margin-bottom: 24px;
        }
        .btn-ctrl {
            background: rgba(255, 255, 255, 0.08);
            border: 1px solid rgba(255, 255, 255, 0.2);
            color: #FFFFFF;
            border-radius: 50%;
            width: ${if (isTvMode) "54px" else "44px"};
            height: ${if (isTvMode) "54px" else "44px"};
            display: flex;
            justify-content: center;
            align-items: center;
            font-size: ${if (isTvMode) "22px" else "18px"};
            cursor: pointer;
            transition: all 0.2s ease;
        }
        .btn-ctrl:hover, .btn-ctrl:focus {
            background: rgba(0, 229, 255, 0.25);
            border-color: var(--miku-cyan);
            transform: scale(1.1);
            outline: none;
        }
        .btn-play {
            width: ${if (isTvMode) "76px" else "62px"};
            height: ${if (isTvMode) "76px" else "62px"};
            background: linear-gradient(135deg, var(--miku-teal), var(--miku-pink));
            border: none;
            box-shadow: 0 0 20px rgba(0, 229, 255, 0.5);
            font-size: ${if (isTvMode) "30px" else "24px"};
        }
        .btn-play:hover, .btn-play:focus {
            transform: scale(1.12);
            box-shadow: 0 0 30px var(--miku-pink);
        }
        .volume-row {
            width: 100%;
            display: flex;
            align-items: center;
            gap: 12px;
        }
        .vol-slider {
            flex: 1;
            -webkit-appearance: none;
            height: 6px;
            border-radius: 3px;
            background: rgba(255, 255, 255, 0.15);
            outline: none;
        }
        .vol-slider::-webkit-slider-thumb {
            -webkit-appearance: none;
            width: 16px;
            height: 16px;
            border-radius: 50%;
            background: var(--miku-cyan);
            box-shadow: 0 0 10px var(--miku-cyan);
            cursor: pointer;
        }
        .spectrum-bar-box {
            display: flex;
            gap: 3px;
            height: 32px;
            align-items: flex-end;
            margin-top: 14px;
        }
        .spectrum-col {
            width: 5px;
            background: linear-gradient(0deg, var(--miku-cyan), var(--miku-pink));
            border-radius: 2px;
            height: 4px;
            transition: height 0.1s ease;
        }
        .mode-nav {
            display: flex;
            gap: 10px;
            margin-top: 20px;
        }
        .nav-link {
            text-decoration: none;
            color: rgba(255, 255, 255, 0.7);
            font-size: 13px;
            padding: 6px 14px;
            border-radius: 20px;
            background: rgba(255, 255, 255, 0.05);
            border: 1px solid rgba(255, 255, 255, 0.15);
            font-family: 'Audiowide', cursive;
        }
        .nav-link.active {
            background: rgba(0, 229, 255, 0.2);
            border-color: var(--miku-cyan);
            color: var(--miku-cyan);
        }
    </style>
</head>
<body>

    <div class="header">
        <div class="brand">
            <div class="brand-logo"></div>
            <div class="brand-title">MIKU CONNECT</div>
        </div>
        <div class="device-badge">
            <div class="device-dot"></div>
            <span>HiBy M500 [DIRECT DTA]</span>
        </div>
    </div>

    <div class="container">
        <div class="art-wrapper">
            <div class="art-glow" id="artGlow"></div>
            <img src="/api/v1/artwork/current" class="art-img" id="artImg" alt="Album Artwork">
        </div>

        <div class="details-pane">
            <div class="track-title" id="trackTitle">Connecting...</div>
            <div class="track-artist" id="trackArtist">MikuOS High-Resolution Audio</div>
            <div class="track-album" id="trackAlbum">Direct Audio Sink (Dual CS43198)</div>

            <div class="badge-row">
                <div class="tech-badge hires" id="rateBadge">24-BIT / 96kHz DTA</div>
                <div class="tech-badge" id="volBadge">VOL 85%</div>
                <div class="tech-badge" id="batteryBadge">BAT 100%</div>
            </div>

            <div class="progress-bar-container">
                <div class="progress-track" id="progressTrack" onclick="seekClick(event)">
                    <div class="progress-fill" id="progressFill"></div>
                </div>
                <div class="time-row">
                    <span id="timeCurrent">00:00</span>
                    <span id="timeTotal">00:00</span>
                </div>
            </div>

            <div class="controls-row">
                <button class="btn-ctrl" onclick="toggleShuffle()" title="Shuffle" id="btnShuffle">🔀</button>
                <button class="btn-ctrl" onclick="prevTrack()" title="Previous" id="btnPrev">⏮</button>
                <button class="btn-ctrl btn-play" onclick="togglePlay()" title="Play/Pause" id="btnPlay">▶</button>
                <button class="btn-ctrl" onclick="nextTrack()" title="Next" id="btnNext">⏭</button>
                <button class="btn-ctrl" onclick="toggleLike()" title="Heart" id="btnLike" style="color: var(--miku-pink);">♥</button>
            </div>

            <div class="volume-row">
                <span style="font-size: 16px;">🔈</span>
                <input type="range" min="0" max="100" value="0" class="vol-slider" id="volSlider" oninput="setVolume(this.value)">
                <span style="font-size: 16px;">🔊</span>
            </div>

            <div class="spectrum-bar-box" id="spectrumBox"></div>

            <div class="mode-nav">
                <a href="/" class="nav-link ${if (!isTvMode) "active" else ""}">📱 Mobile Remote</a>
                <a href="/tv" class="nav-link ${if (isTvMode) "active" else ""}">📺 TV Big Screen</a>
            </div>
        </div>
    </div>

    <audio id="tvAudioElement" preload="auto"></audio>

    <script>
        let isPlaying = false;
        let curDuration = 0;
        let curPosition = 0;
        let lastSyncTime = Date.now();
        let isUserSeeking = false;
        let currentTrackId = -1;
        const isTvMode = ${if (isTvMode) "true" else "false"};
        const tvAudio = document.getElementById('tvAudioElement');
        let tvAudioUnlocked = false;

        function connectTvAudio() {
            if (!tvAudio) return;
            tvAudioUnlocked = true;
            tvAudio.play().then(() => {
                const banner = document.getElementById('tvAudioBanner');
                if (banner) banner.style.display = 'none';
            }).catch(e => {
                console.log("Audio unlock triggered", e);
            });
        }

        // Spectrum bars driven by the device's REAL FFT (/api/v1/levels). When no Visualizer is
        // bound on the device the endpoint reports active=false and the bars stay flat — no
        // Math.random() animation pretending to be audio.
        const specBox = document.getElementById('spectrumBox');
        for (let i = 0; i < 24; i++) {
            const col = document.createElement('div');
            col.className = 'spectrum-col';
            specBox.appendChild(col);
        }
        const specCols = document.querySelectorAll('.spectrum-col');

        async function updateSpectrum() {
            try {
                const res = await fetch('/api/v1/levels', { cache: 'no-store' });
                if (!res.ok) { specCols.forEach(col => col.style.height = '4px'); return; }
                const lv = await res.json();
                if (!lv.active || !lv.is_playing || !Array.isArray(lv.fft) || lv.fft.length === 0) {
                    specCols.forEach(col => col.style.height = '4px');
                    return;
                }
                const per = Math.max(1, Math.floor(lv.fft.length / specCols.length));
                specCols.forEach((col, i) => {
                    let s = 0;
                    for (let k = 0; k < per; k++) s += (lv.fft[i * per + k] || 0);
                    const mag = Math.min(1, s / per);
                    col.style.height = (4 + Math.round(mag * 28)) + 'px';
                });
            } catch (e) {
                specCols.forEach(col => col.style.height = '4px');
            }
        }
        setInterval(updateSpectrum, 150);

        async function pollStatus() {
            try {
                const res = await fetch('/api/v1/status');
                if (!res.ok) return;
                const data = await res.json();
                renderState(data);
            } catch (e) {
                console.error("Poll error", e);
            }
        }

        function renderState(data) {
            if (!data || !data.track) return;

            document.getElementById('trackTitle').innerText = data.track.title || "No Track";
            document.getElementById('trackArtist').innerText = data.track.artist || "Unknown Artist";
            document.getElementById('trackAlbum').innerText = data.track.album || "";

            const dacRate = data.hardware.dac_sample_rate_hz || 0;
            const rateStr = dacRate > 0 ? (dacRate / 1000).toFixed(1) + ' kHz DTA' : 'Direct DTA';
            document.getElementById('rateBadge').innerText = rateStr;
            document.getElementById('volBadge').innerText = 'VOL ' + data.playback.volume_pct + '%';
            document.getElementById('batteryBadge').innerText = 'BAT ' + data.hardware.battery_pct + '%';

            isPlaying = data.playback.is_playing;
            document.getElementById('btnPlay').innerText = isPlaying ? '⏸' : '▶';

            curDuration = data.playback.duration_ms || 0;
            curPosition = data.playback.position_ms || 0;
            lastSyncTime = Date.now();

            document.getElementById('timeTotal').innerText = formatTime(curDuration);
            document.getElementById('volSlider').value = data.playback.volume_pct;

            // Refresh artwork if changed
            if (data.track.id !== currentTrackId) {
                currentTrackId = data.track.id;
                const art = document.getElementById('artImg');
                art.src = '/api/v1/artwork/current?t=' + (data.track.id || Date.now());

                // Sync TV Sound System Audio Stream
                if (isTvMode && tvAudio && currentTrackId > 0) {
                    tvAudio.src = '/api/v1/audio/stream/current?t=' + currentTrackId;
                    if (data.playback.position_ms > 0) {
                        tvAudio.currentTime = data.playback.position_ms / 1000;
                    }
                    if (isPlaying) {
                        tvAudio.play().catch(_ => {});
                    }
                }
            }

            // Sync live audio state in TV Mode
            if (isTvMode && tvAudio && currentTrackId > 0) {
                const targetSec = (data.playback.position_ms || 0) / 1000;
                if (Math.abs(tvAudio.currentTime - targetSec) > 2.5) {
                    tvAudio.currentTime = targetSec;
                }
                tvAudio.volume = Math.min(1.0, Math.max(0.0, (data.playback.volume_pct || 0) / 100.0));
                if (isPlaying && tvAudio.paused) {
                    tvAudio.play().catch(_ => {});
                } else if (!isPlaying && !tvAudio.paused) {
                    tvAudio.pause();
                }
            }
        }

        function formatTime(ms) {
            if (!ms || ms <= 0) return "00:00";
            const totalSec = Math.floor(ms / 1000);
            const m = Math.floor(totalSec / 60);
            const s = totalSec % 60;
            return (m < 10 ? '0' : '') + m + ':' + (s < 10 ? '0' : '') + s;
        }

        // Live smooth progress ticker
        setInterval(() => {
            if (isPlaying && curDuration > 0) {
                const elapsed = curPosition + (Date.now() - lastSyncTime);
                const pct = Math.min(100, (elapsed / curDuration) * 100);
                document.getElementById('progressFill').style.width = pct + '%';
                document.getElementById('timeCurrent').innerText = formatTime(elapsed);
            }
        }, 200);

        async function sendPost(path, body = {}) {
            try {
                const res = await fetch(path, {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify(body)
                });
                const data = await res.json();
                renderState(data);
            } catch (e) {
                console.error("API error", e);
            }
        }

        function togglePlay() { sendPost('/api/v1/playback/toggle'); }
        function nextTrack() { sendPost('/api/v1/playback/next'); }
        function prevTrack() { sendPost('/api/v1/playback/previous'); }
        function setVolume(val) { sendPost('/api/v1/playback/volume', { volume: parseInt(val) }); }
        function toggleShuffle() { sendPost('/api/v1/playback/mode', { shuffle: true }); }
        function toggleLike() { sendPost('/api/v1/playback/like'); }

        function seekClick(e) {
            const rect = document.getElementById('progressTrack').getBoundingClientRect();
            const clickX = e.clientX - rect.left;
            const pct = Math.max(0, Math.min(1, clickX / rect.width));
            const targetMs = Math.floor(pct * curDuration);
            sendPost('/api/v1/playback/seek', { position_ms: targetMs });
        }

        // Keyboard navigation for TV Remote Control
        window.addEventListener('keydown', (e) => {
            switch(e.key) {
                case ' ':
                case 'Enter':
                    togglePlay();
                    break;
                case 'ArrowRight':
                    nextTrack();
                    break;
                case 'ArrowLeft':
                    prevTrack();
                    break;
                case 'ArrowUp':
                    sendPost('/api/v1/playback/volume', { delta: 1 });
                    break;
                case 'ArrowDown':
                    sendPost('/api/v1/playback/volume', { delta: -1 });
                    break;
            }
        });

        // Fast poll loop
        pollStatus();
        setInterval(pollStatus, 1500);
    </script>
</body>
</html>
        """.trimIndent()
    }
}
