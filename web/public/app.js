const audioElement = document.getElementById('audio-element');
const btnPlayPause = document.getElementById('btn-play-pause');
const btnPrev = document.getElementById('btn-prev');
const btnNext = document.getElementById('btn-next');
const seekBar = document.getElementById('seek-bar');
const volumeBar = document.getElementById('volume-bar');
const timeCurrent = document.getElementById('time-current');
const timeTotal = document.getElementById('time-total');
const playerTitle = document.getElementById('player-title');
const playerArtist = document.getElementById('player-artist');
const currentArt = document.getElementById('current-art');
const tracksContainer = document.getElementById('tracks-container');
const searchBar = document.getElementById('search-bar');
const fileInput = document.getElementById('file-input');
const btnUpload = document.getElementById('btn-upload');

let songs = [];
let currentSongIndex = -1;
let audioCtx = null;
let analyser = null;
let dataArray = null;

// Initialize layout
window.addEventListener('load', async () => {
    await loadLibrary();
    setupAudioListeners();
});

async function loadLibrary() {
    try {
        const response = await fetch('/api/songs');
        songs = await response.json();
        renderTracks(songs);
    } catch (err) {
        tracksContainer.innerHTML = `<p class="status-msg">Failed to connect to VibeStation library server.</p>`;
    }
}

function renderTracks(trackList) {
    if (trackList.length === 0) {
        tracksContainer.innerHTML = `<p class="status-msg">No tracks found in the folder. Please add MP3 files.</p>`;
        return;
    }

    tracksContainer.innerHTML = trackList.map((song, index) => `
        <div class="track-row" onclick="playTrack(${index})">
            <img class="row-art" src="/api/artwork/${song.id}" alt="Cover">
            <div class="row-title">${song.title}</div>
            <div class="row-artist">${song.artist}</div>
            <div class="row-album">${song.album}</div>
        </div>
    `).join('');
}

// Client-side dominant color extractor to drive layout vibes dynamically
function updateDynamicVibes(artworkUrl) {
    const img = new Image();
    img.crossOrigin = "Anonymous";
    img.onload = function() {
        const canvas = document.createElement('canvas');
        const ctx = canvas.getContext('2d');
        canvas.width = 1;
        canvas.height = 1;
        ctx.drawImage(img, 0, 0, 1, 1);
        const [r, g, b] = ctx.getImageData(0, 0, 1, 1).data;
        
        // Discard extreme black values to avoid mud, default to pastel fallback
        const brightness = (r * 299 + g * 587 + b * 114) / 1000;
        const color = brightness < 15 ? 'rgb(160, 132, 220)' : `rgb(${r}, ${g}, ${b})`;
        
        document.documentElement.style.setProperty('--vibe-color', color);
        document.documentElement.style.setProperty('--vibe-color-glow', color.replace('rgb', 'rgba').replace(')', ', 0.15)'));
    };
    img.src = artworkUrl;
}

function playTrack(index) {
    if (index < 0 || index >= songs.length) return;
    currentSongIndex = index;
    const song = songs[currentSongIndex];

    playerTitle.textContent = song.title;
    playerArtist.textContent = song.artist;
    
    const artworkUrl = `/api/artwork/${song.id}`;
    currentArt.src = artworkUrl;
    updateDynamicVibes(artworkUrl);

    audioElement.src = `/api/stream/${song.id}`;
    audioElement.play();
    btnPlayPause.textContent = '⏸';

    initVisualizer();
}

function setupAudioListeners() {
    btnPlayPause.addEventListener('click', () => {
        if (currentSongIndex === -1 && songs.length > 0) {
            playTrack(0);
            return;
        }
        if (audioElement.paused) {
            audioElement.play();
            btnPlayPause.textContent = '⏸';
        } else {
            audioElement.pause();
            btnPlayPause.textContent = '▶';
        }
    });

    btnNext.addEventListener('click', () => {
        if (songs.length > 0) {
            playTrack((currentSongIndex + 1) % songs.length);
        }
    });

    btnPrev.addEventListener('click', () => {
        if (songs.length > 0) {
            playTrack((currentSongIndex - 1 + songs.length) % songs.length);
        }
    });

    audioElement.addEventListener('timeupdate', () => {
        if (!audioElement.duration) return;
        const pct = (audioElement.currentTime / audioElement.duration) * 100;
        seekBar.value = pct;
        timeCurrent.textContent = formatTime(audioElement.currentTime);
    });

    audioElement.addEventListener('loadedmetadata', () => {
        timeTotal.textContent = formatTime(audioElement.duration);
    });

    audioElement.addEventListener('ended', () => {
        btnNext.click();
    });

    seekBar.addEventListener('input', () => {
        if (!audioElement.duration) return;
        audioElement.currentTime = (seekBar.value / 100) * audioElement.duration;
    });

    volumeBar.addEventListener('input', () => {
        audioElement.volume = volumeBar.value / 100;
    });

    searchBar.addEventListener('input', () => {
        const query = searchBar.value.toLowerCase().trim();
        const filtered = songs.filter(s => 
            s.title.toLowerCase().includes(query) || 
            s.artist.toLowerCase().includes(query) || 
            s.album.toLowerCase().includes(query)
        );
        renderTracks(filtered);
    });

    // Manual file selector trigger
    btnUpload.addEventListener('click', () => {
        fileInput.click();
    });

    fileInput.addEventListener('change', async () => {
        const files = fileInput.files;
        if (files.length === 0) return;
        await handleFileUpload(files);
    });

    // Drag and drop events
    const mainContent = document.querySelector('.main-content');
    mainContent.addEventListener('dragover', (e) => {
        e.preventDefault();
        mainContent.classList.add('drag-over');
    });
    mainContent.addEventListener('dragleave', () => {
        mainContent.classList.remove('drag-over');
    });
    mainContent.addEventListener('drop', async (e) => {
        e.preventDefault();
        mainContent.classList.remove('drag-over');
        const files = e.dataTransfer.files;
        if (files.length > 0) {
            await handleFileUpload(files);
        }
    });
}

async function handleFileUpload(files) {
    const mp3Files = Array.from(files).filter(file => file.name.toLowerCase().endsWith('.mp3'));
    if (mp3Files.length === 0) return;

    const formData = new FormData();
    mp3Files.forEach(file => formData.append('files', file));

    btnUpload.textContent = 'Uploading...';
    try {
        const response = await fetch('/api/upload', { method: 'POST', body: formData });
        const result = await response.json();
        if (result.success) {
            btnUpload.textContent = `Uploaded ${result.count} Tracks!`;
            setTimeout(() => btnUpload.textContent = 'Upload Tracks', 3000);
            await loadLibrary();
        }
    } catch (err) {
        btnUpload.textContent = 'Upload Failed';
        setTimeout(() => btnUpload.textContent = 'Upload Tracks', 3000);
    }
}

function formatTime(secs) {
    const m = Math.floor(secs / 60);
    const s = Math.floor(secs % 60);
    return `${m}:${s < 10 ? '0' : ''}${s}`;
}

// Web Audio frequency visualizer
function initVisualizer() {
    if (audioCtx) return; // Prevent dual mappings
    
    audioCtx = new (window.AudioContext || window.webkitAudioContext)();
    analyser = audioCtx.createAnalyser();
    const source = audioCtx.createMediaElementSource(audioElement);
    
    source.connect(analyser);
    analyser.connect(audioCtx.destination);
    
    analyser.fftSize = 256;
    const bufferLength = analyser.frequencyBinCount;
    dataArray = new Uint8Array(bufferLength);

    const canvas = document.getElementById('visualizer-canvas');
    const ctx = canvas.getContext('2d');
    
    // Scale canvas pixels
    function resizeCanvas() {
        canvas.width = canvas.parentElement.clientWidth;
        canvas.height = canvas.parentElement.clientHeight;
    }
    resizeCanvas();
    window.addEventListener('resize', resizeCanvas);

    function draw() {
        requestAnimationFrame(draw);
        if (!analyser) return;

        analyser.getByteFrequencyData(dataArray);

        ctx.clearRect(0, 0, canvas.width, canvas.height);
        
        const vibeColor = getComputedStyle(document.documentElement).getPropertyValue('--vibe-color').trim();
        ctx.fillStyle = vibeColor;
        ctx.globalAlpha = 0.05;

        // Custom curve visualizer
        ctx.beginPath();
        ctx.moveTo(0, canvas.height);

        const sliceWidth = canvas.width / (bufferLength - 1);
        for (let i = 0; i < bufferLength; i++) {
            const v = dataArray[i] / 128.0;
            const y = canvas.height - (v * (canvas.height / 2));
            const x = i * sliceWidth;

            if (i === 0) {
                ctx.lineTo(x, y);
            } else {
                const prevX = (i - 1) * sliceWidth;
                const prevY = canvas.height - ((dataArray[i-1]/128.0) * (canvas.height / 2));
                ctx.bezierCurveTo(
                    prevX + sliceWidth / 2, prevY, 
                    x - sliceWidth / 2, y, 
                    x, y
                );
            }
        }
        ctx.lineTo(canvas.width, canvas.height);
        ctx.closePath();
        ctx.fill();
    }
    
    draw();
}
