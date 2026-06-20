// Mobile device detection
function checkMobile() {
    const isMobileUA = /Android|webOS|iPhone|iPad|iPod|BlackBerry|IEMobile|Opera Mini/i.test(navigator.userAgent);
    const isSmallScreen = window.innerWidth <= 768;
    if (isMobileUA || isSmallScreen) {
        document.body.classList.add('is-mobile');
    } else {
        document.body.classList.remove('is-mobile');
    }
}
checkMobile();
window.addEventListener('resize', checkMobile);

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
const btnShowLibrary = document.getElementById('btn-show-library');
const btnShowAlbums = document.getElementById('btn-show-albums');
const btnShowArtists = document.getElementById('btn-show-artists');
const btnShowPlaylists = document.getElementById('btn-show-playlists');
const btnShowSettings = document.getElementById('btn-show-settings');

// Fullscreen player elements
const fullscreenPlayer = document.getElementById('fullscreen-player');
const fpCloseBtn = document.getElementById('fp-close-btn');
const fpArt = document.getElementById('fp-art');
const fpBg = document.getElementById('fp-bg');
const fpTitle = document.getElementById('fp-title');
const fpArtist = document.getElementById('fp-artist');
const fpSeekBar = document.getElementById('fp-seek-bar');
const fpTimeCurrent = document.getElementById('fp-time-current');
const fpTimeTotal = document.getElementById('fp-time-total');
const fpBtnPrev = document.getElementById('fp-btn-prev');
const fpBtnPlayPause = document.getElementById('fp-btn-play-pause');
const fpBtnNext = document.getElementById('fp-btn-next');

// Lyrics elements
const lyricsPanel = document.getElementById('lyrics-panel');
const lyricsCloseBtn = document.getElementById('lyrics-close-btn');
const lyricsContent = document.getElementById('lyrics-content');
const btnLyrics = document.getElementById('btn-lyrics');
const fpBtnLyrics = document.getElementById('fp-btn-lyrics');

let songs = [];
let currentQueue = [];
let currentSongIndex = -1;
let audioCtx = null;
let analyser = null;
let dataArray = null;
let playlists = [];
let consolidateArtists = localStorage.getItem('consolidateArtists') !== 'false';
let currentView = 'library';
let currentDetailItem = null;

// Initialize layout
window.addEventListener('load', async () => {
    await loadLibrary();
    setupAudioListeners();
    setupFullscreenPlayer();
    setupLyricsListeners();
});

function setupFullscreenPlayer() {
    document.querySelector('.artwork-container').addEventListener('click', () => {
        fullscreenPlayer.classList.add('active');
    });
    fpCloseBtn.addEventListener('click', () => {
        fullscreenPlayer.classList.remove('active');
    });
    fpBtnPlayPause.addEventListener('click', () => {
        btnPlayPause.click();
    });
    fpBtnPrev.addEventListener('click', () => {
        btnPrev.click();
    });
    fpBtnNext.addEventListener('click', () => {
        btnNext.click();
    });
    fpSeekBar.addEventListener('input', () => {
        if (!audioElement.duration) return;
        audioElement.currentTime = (fpSeekBar.value / 100) * audioElement.duration;
    });
}

function setupLyricsListeners() {
    lyricsCloseBtn.addEventListener('click', () => {
        lyricsPanel.classList.remove('active');
    });
    btnLyrics.addEventListener('click', toggleLyrics);
    if (fpBtnLyrics) {
        fpBtnLyrics.addEventListener('click', toggleLyrics);
    }
}

function toggleLyrics() {
    if (lyricsPanel.classList.contains('active')) {
        lyricsPanel.classList.remove('active');
    } else {
        lyricsPanel.classList.add('active');
        loadLyrics();
    }
}

async function loadLyrics() {
    if (currentSongIndex === -1) {
        lyricsContent.innerHTML = '<p class="status-msg">No song playing</p>';
        return;
    }
    const song = currentQueue[currentSongIndex];
    lyricsContent.innerHTML = '<p class="status-msg">Searching lyrics...</p>';
    try {
        const response = await fetch(`/api/metadata/lyrics?artist=${encodeURIComponent(song.artist)}&title=${encodeURIComponent(song.title)}`);
        const data = await response.json();
        if (data && data.lyrics) {
            lyricsContent.innerHTML = data.lyrics.replace(/\r\n/g, '<br>').replace(/\n/g, '<br>');
        } else {
            lyricsContent.innerHTML = '<p class="status-msg">Lyrics not found for this track.</p>';
        }
    } catch (e) {
        lyricsContent.innerHTML = '<p class="status-msg">Lyrics not found for this track.</p>';
    }
}

function shufflePlay() {
    if (currentQueue.length === 0) return;
    const shuffled = [...currentQueue];
    for (let i = shuffled.length - 1; i > 0; i--) {
        const j = Math.floor(Math.random() * (i + 1));
        [shuffled[i], shuffled[j]] = [shuffled[j], shuffled[i]];
    }
    currentQueue = shuffled;
    playTrack(0);
}

async function loadLibrary() {
    try {
        const response = await fetch('/api/songs');
        if (!response.ok) {
            const errorText = await response.text();
            throw new Error(`Server returned ${response.status}: ${errorText}`);
        }
        songs = await response.json();
        try {
            const plResponse = await fetch('/api/playlists');
            if (plResponse.ok) {
                playlists = await plResponse.json();
            }
        } catch (e) {
            console.error('Failed to load playlists', e);
        }
        currentQueue = [...songs];
        showLibrary();
    } catch (err) {
        console.error('Library connection error:', err);
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
            <button class="btn-add-to-playlist" onclick="event.stopPropagation(); openAddToPlaylistModal('${song.id}')">+</button>
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
    if (index < 0 || index >= currentQueue.length) return;
    currentSongIndex = index;
    const song = currentQueue[currentSongIndex];

    playerTitle.textContent = song.title;
    playerArtist.textContent = song.artist;
    
    const artworkUrl = `/api/artwork/${song.id}`;
    currentArt.src = artworkUrl;
    updateDynamicVibes(artworkUrl);

    audioElement.src = `/api/stream/${song.id}`;
    audioElement.play();
    const playPausePath = document.getElementById('play-pause-path');
    if (playPausePath) {
        playPausePath.setAttribute('d', 'M6 19h4V5H6v14zm8-14v14h4V5h-4z'); // Pause bars
    }
    const fpPlayPausePath = document.getElementById('fp-play-pause-path');
    if (fpPlayPausePath) {
        fpPlayPausePath.setAttribute('d', 'M6 19h4V5H6v14zm8-14v14h4V5h-4z'); // Pause bars
    }

    // Update fullscreen player
    fpTitle.textContent = song.title;
    fpArtist.textContent = song.artist;
    fpArt.src = artworkUrl;
    fpBg.style.backgroundImage = `url('${artworkUrl}')`;

    // Media Session Metadata & Browser Title Masking
    if ('mediaSession' in navigator) {
        document.title = `VibeStation - ${song.title}`;
        navigator.mediaSession.metadata = new MediaMetadata({
            title: song.title,
            artist: song.artist,
            album: song.album || 'VibeStation',
            artwork: [
                { src: artworkUrl, sizes: '96x96', type: 'image/jpeg' },
                { src: artworkUrl, sizes: '128x128', type: 'image/jpeg' },
                { src: artworkUrl, sizes: '192x192', type: 'image/jpeg' },
                { src: artworkUrl, sizes: '256x256', type: 'image/jpeg' },
                { src: artworkUrl, sizes: '384x384', type: 'image/jpeg' },
                { src: artworkUrl, sizes: '512x512', type: 'image/jpeg' }
            ]
        });
    }

    if (lyricsPanel.classList.contains('active')) {
        loadLyrics();
    }

    initVisualizer();
}

function setupAudioListeners() {
    btnPlayPause.addEventListener('click', () => {
        const playPausePath = document.getElementById('play-pause-path');
        const fpPlayPausePath = document.getElementById('fp-play-pause-path');
        if (currentSongIndex === -1 && currentQueue.length > 0) {
            playTrack(0);
            return;
        }
        if (audioElement.paused) {
            audioElement.play();
            if (playPausePath) playPausePath.setAttribute('d', 'M6 19h4V5H6v14zm8-14v14h4V5h-4z'); // Pause bars
            if (fpPlayPausePath) fpPlayPausePath.setAttribute('d', 'M6 19h4V5H6v14zm8-14v14h4V5h-4z');
        } else {
            audioElement.pause();
            if (playPausePath) playPausePath.setAttribute('d', 'M8 5v14l11-7z'); // Play triangle
            if (fpPlayPausePath) fpPlayPausePath.setAttribute('d', 'M8 5v14l11-7z');
        }
    });

    if ('mediaSession' in navigator) {
        navigator.mediaSession.setActionHandler('play', () => {
            btnPlayPause.click();
        });
        navigator.mediaSession.setActionHandler('pause', () => {
            btnPlayPause.click();
        });
        navigator.mediaSession.setActionHandler('previoustrack', () => {
            btnPrev.click();
        });
        navigator.mediaSession.setActionHandler('nexttrack', () => {
            btnNext.click();
        });
    }

    btnNext.addEventListener('click', () => {
        if (currentQueue.length > 0) {
            playTrack((currentSongIndex + 1) % currentQueue.length);
        }
    });

    btnPrev.addEventListener('click', () => {
        if (currentQueue.length > 0) {
            playTrack((currentSongIndex - 1 + currentQueue.length) % currentQueue.length);
        }
    });

    audioElement.addEventListener('timeupdate', () => {
        if (!audioElement.duration) return;
        const pct = (audioElement.currentTime / audioElement.duration) * 100;
        seekBar.value = pct;
        timeCurrent.textContent = formatTime(audioElement.currentTime);
        fpSeekBar.value = pct;
        fpTimeCurrent.textContent = formatTime(audioElement.currentTime);
    });

    audioElement.addEventListener('loadedmetadata', () => {
        const durationStr = formatTime(audioElement.duration);
        timeTotal.textContent = durationStr;
        fpTimeTotal.textContent = durationStr;
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
        if (currentView === 'library') {
            showLibrary();
        } else if (currentView === 'albums') {
            showAlbums();
        } else if (currentView === 'artists') {
            showArtists();
        } else if (currentView === 'playlists') {
            showPlaylists();
        } else if (currentView === 'album-detail') {
            viewAlbumDetail(encodeURIComponent(currentDetailItem));
        } else if (currentView === 'artist-detail') {
            viewArtistDetail(encodeURIComponent(currentDetailItem));
        } else if (currentView === 'playlist-detail') {
            viewPlaylistDetail(encodeURIComponent(currentDetailItem));
        }
    });

    // Tab switching event listeners
    btnShowLibrary.addEventListener('click', () => {
        searchBar.value = '';
        showLibrary();
    });
    btnShowAlbums.addEventListener('click', () => {
        searchBar.value = '';
        showAlbums();
    });
    btnShowArtists.addEventListener('click', () => {
        searchBar.value = '';
        showArtists();
    });
    btnShowPlaylists.addEventListener('click', () => {
        searchBar.value = '';
        showPlaylists();
    });

    btnShowSettings.addEventListener('click', () => {
        searchBar.value = '';
        showSettings();
    });

    // Add to playlist modal event listeners
    const btnModalCreatePlaylist = document.getElementById('btn-modal-create-playlist');
    if (btnModalCreatePlaylist) {
        btnModalCreatePlaylist.addEventListener('click', modalCreatePlaylist);
    }
    const closePlaylistModalBtn = document.getElementById('close-playlist-modal');
    if (closePlaylistModalBtn) {
        closePlaylistModalBtn.addEventListener('click', closePlaylistModal);
    }
    window.addEventListener('click', (event) => {
        const modal = document.getElementById('playlist-modal');
        if (event.target === modal) {
            closePlaylistModal();
        }
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

    // Keyboard Shortcuts (Spacebar to toggle play/pause)
    window.addEventListener('keydown', (e) => {
        if (e.code === 'Space') {
            if (document.activeElement.tagName === 'INPUT' || document.activeElement.tagName === 'TEXTAREA') {
                return;
            }
            e.preventDefault();
            btnPlayPause.click();
        }
    });

    // Fingerprint Scanner visual/color effects
    const btnFingerprint = document.getElementById('btn-fingerprint');
    btnFingerprint.addEventListener('click', () => {
        btnFingerprint.classList.add('scanning');
        
        if (analyser) {
            analyser.fftSize = 512;
        }
        
        const bgGlow = document.getElementById('vibe-bg-glow');
        if (bgGlow) {
            bgGlow.style.transform = 'translate(-50%, -50%) scale(1.6)';
            bgGlow.style.filter = 'blur(40px)';
        }
        
        setTimeout(() => {
            btnFingerprint.classList.remove('scanning');
            if (analyser) {
                analyser.fftSize = 256;
            }
            if (bgGlow) {
                bgGlow.style.transform = 'translate(-50%, -50%) scale(1)';
                bgGlow.style.filter = 'blur(80px)';
            }
            
            const randomHue = Math.floor(Math.random() * 360);
            const newColor = `hsl(${randomHue}, 70%, 65%)`;
            const newColorGlow = `hsla(${randomHue}, 70%, 65%, 0.15)`;
            document.documentElement.style.setProperty('--vibe-color', newColor);
            document.documentElement.style.setProperty('--vibe-color-glow', newColorGlow);
        }, 1200);
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

function showLibrary() {
    currentView = 'library';
    updateActiveNavItem('btn-show-library');
    document.getElementById('view-title').textContent = 'Your Collection';
    
    const query = searchBar.value.toLowerCase().trim();
    currentQueue = songs.filter(s => 
        s.title.toLowerCase().includes(query) || 
        s.artist.toLowerCase().includes(query) || 
        s.album.toLowerCase().includes(query)
    );
    renderTracks(currentQueue);
}

function updateActiveNavItem(activeId) {
    const items = document.querySelectorAll('.nav-item');
    items.forEach(item => {
        if (item.id === activeId) {
            item.classList.add('active');
        } else if (item.id !== 'btn-upload') {
            item.classList.remove('active');
        }
    });
}

function getAlbums() {
    const albumMap = new Map();
    songs.forEach(song => {
        const key = song.album || 'Unknown Album';
        if (!albumMap.has(key)) {
            albumMap.set(key, {
                name: key,
                artist: song.artist || 'Unknown Artist',
                tracks: [],
                artworkSongId: song.id
            });
        }
        albumMap.get(key).tracks.push(song);
    });
    return Array.from(albumMap.values());
}

function showAlbums() {
    currentView = 'albums';
    updateActiveNavItem('btn-show-albums');
    document.getElementById('view-title').textContent = 'Albums';
    
    const albums = getAlbums();
    const query = searchBar.value.toLowerCase().trim();
    const filteredAlbums = albums.filter(a => 
        a.name.toLowerCase().includes(query) || 
        a.artist.toLowerCase().includes(query)
    );

    if (filteredAlbums.length === 0) {
        tracksContainer.innerHTML = `<p class="status-msg">No albums found.</p>`;
        return;
    }

    tracksContainer.innerHTML = `
        <div class="grid-container">
            ${filteredAlbums.map(album => `
                <div class="grid-card" onclick="viewAlbumDetail('${encodeURIComponent(album.name)}')">
                    <div class="card-art-container">
                        <img class="card-art" src="/api/artwork/${album.artworkSongId}" alt="${album.name}">
                    </div>
                    <div class="card-title">${album.name}</div>
                    <div class="card-subtitle">${album.artist}</div>
                </div>
            `).join('')}
        </div>
    `;
}

function viewAlbumDetail(encodedName) {
    const albumName = decodeURIComponent(encodedName);
    currentDetailItem = albumName;
    const album = getAlbums().find(a => a.name === albumName);
    if (!album) return;

    currentView = 'album-detail';
    updateActiveNavItem('btn-show-albums');
    document.getElementById('view-title').textContent = albumName;
    
    const query = searchBar.value.toLowerCase().trim();
    const filteredTracks = album.tracks.filter(song => 
        song.title.toLowerCase().includes(query) || 
        song.artist.toLowerCase().includes(query)
    );

    currentQueue = [...filteredTracks];

    tracksContainer.innerHTML = `
        <button class="back-btn" onclick="searchBar.value=''; showAlbums()">← Back to Albums</button>
        <div class="detail-header">
            <img class="detail-art" src="/api/artwork/${album.artworkSongId}" alt="${album.name}">
            <div class="detail-info">
                <span class="detail-type">Album</span>
                <h1 class="detail-title">${album.name}</h1>
                <span class="detail-meta">${album.artist} • ${album.tracks.length} track(s)</span>
                <div class="detail-actions">
                    <button class="btn-primary" onclick="playTrack(0)">Play All</button>
                    <button class="btn-secondary" onclick="shufflePlay()">Shuffle Play</button>
                </div>
                <div id="album-desc" class="detail-summary" style="display: none;" onclick="this.classList.toggle('expanded')"></div>
            </div>
        </div>
        <div class="tracks-list">
            ${filteredTracks.map((song, index) => `
                <div class="track-row" onclick="playTrack(${index})">
                    <img class="row-art" src="/api/artwork/${song.id}" alt="Cover">
                    <div class="row-title">${song.title}</div>
                    <div class="row-artist">${song.artist}</div>
                    <div class="row-album">${song.album}</div>
                    <button class="btn-add-to-playlist" onclick="event.stopPropagation(); openAddToPlaylistModal('${song.id}')">+</button>
                </div>
            `).join('')}
        </div>
    `;

    fetch(`/api/metadata/album?artist=${encodeURIComponent(album.artist)}&album=${encodeURIComponent(album.name)}`)
        .then(res => res.json())
        .then(data => {
            if (data && data.description) {
                const descElement = document.getElementById('album-desc');
                descElement.innerHTML = `<p>${data.description.replace(/\n/g, '<br>')}</p>`;
                descElement.style.display = 'block';
            }
        }).catch(() => {});
}

function getPrimaryArtist(artist) {
    if (!artist) return 'Unknown Artist';
    let primary = artist.trim();
    const featRegex = /\s+(?:ft\.?|feat\.?|featuring|with|vs\.?|and|&)\s+/i;
    primary = primary.split(featRegex)[0];
    primary = primary.split('/')[0];
    primary = primary.split(';')[0];
    return primary.trim() || 'Unknown Artist';
}

function getArtists() {
    const artistMap = new Map();
    songs.forEach(song => {
        const rawArtist = song.artist || 'Unknown Artist';
        const key = consolidateArtists ? getPrimaryArtist(rawArtist) : rawArtist;
        if (!artistMap.has(key)) {
            artistMap.set(key, {
                name: key,
                tracks: [],
                artworkSongId: song.id
            });
        }
        artistMap.get(key).tracks.push(song);
    });
    return Array.from(artistMap.values()).sort((a, b) => a.name.localeCompare(b.name));
}

function showArtists() {
    currentView = 'artists';
    updateActiveNavItem('btn-show-artists');
    document.getElementById('view-title').textContent = 'Artists';
    
    const artists = getArtists();
    const query = searchBar.value.toLowerCase().trim();
    const filteredArtists = artists.filter(a => 
        a.name.toLowerCase().includes(query)
    );

    if (filteredArtists.length === 0) {
        tracksContainer.innerHTML = `<p class="status-msg">No artists found.</p>`;
        return;
    }

    tracksContainer.innerHTML = `
        <div class="grid-container">
            ${filteredArtists.map(artist => {
                const cachedArt = localStorage.getItem('artist_art_' + artist.name);
                const imgSrc = cachedArt ? cachedArt : `/api/artwork/${artist.artworkSongId}`;
                return `
                    <div class="grid-card" onclick="viewArtistDetail('${encodeURIComponent(artist.name)}')">
                        <div class="card-art-container" style="border-radius: 50%;">
                            <img class="card-art" id="artist-grid-art-${encodeURIComponent(artist.name)}" src="${imgSrc}" alt="${artist.name}">
                        </div>
                        <div class="card-title">${artist.name}</div>
                        <div class="card-subtitle">${artist.tracks.length} Track(s)</div>
                    </div>
                `;
            }).join('')}
        </div>
    `;

    filteredArtists.forEach(artist => {
        if (!localStorage.getItem('artist_art_' + artist.name)) {
            fetch(`/api/metadata/artist?name=${encodeURIComponent(artist.name)}`)
                .then(res => res.json())
                .then(data => {
                    if (data && data.image) {
                        localStorage.setItem('artist_art_' + artist.name, data.image);
                        const img = document.getElementById(`artist-grid-art-${encodeURIComponent(artist.name)}`);
                        if (img) img.src = data.image;
                    }
                }).catch(() => {});
        }
    });
}

function viewArtistDetail(encodedName) {
    const artistName = decodeURIComponent(encodedName);
    currentDetailItem = artistName;
    const artist = getArtists().find(a => a.name === artistName);
    if (!artist) return;

    currentView = 'artist-detail';
    updateActiveNavItem('btn-show-artists');
    document.getElementById('view-title').textContent = artistName;
    
    const query = searchBar.value.toLowerCase().trim();
    const filteredTracks = artist.tracks.filter(song => 
        song.title.toLowerCase().includes(query) || 
        song.album.toLowerCase().includes(query)
    );

    currentQueue = [...filteredTracks];

    const cachedArt = localStorage.getItem('artist_art_' + artist.name);
    const detailArtSrc = cachedArt ? cachedArt : `/api/artwork/${artist.artworkSongId}`;

    tracksContainer.innerHTML = `
        <button class="back-btn" onclick="searchBar.value=''; showArtists()">← Back to Artists</button>
        <div class="detail-header">
            <img class="detail-art" src="${detailArtSrc}" alt="${artist.name}" style="border-radius: 50%;">
            <div class="detail-info">
                <span class="detail-type">Artist</span>
                <h1 class="detail-title">${artist.name}</h1>
                <span class="detail-meta">${artist.tracks.length} track(s)</span>
                <div class="detail-actions">
                    <button class="btn-primary" onclick="playTrack(0)">Play All</button>
                    <button class="btn-secondary" onclick="shufflePlay()">Shuffle Play</button>
                </div>
                <div id="artist-bio" class="detail-summary" style="display: none;" onclick="this.classList.toggle('expanded')"></div>
            </div>
        </div>
        <div class="tracks-list">
            ${filteredTracks.map((song, index) => `
                <div class="track-row" onclick="playTrack(${index})">
                    <img class="row-art" src="/api/artwork/${song.id}" alt="Cover">
                    <div class="row-title">${song.title}</div>
                    <div class="row-artist">${song.artist}</div>
                    <div class="row-album">${song.album}</div>
                    <button class="btn-add-to-playlist" onclick="event.stopPropagation(); openAddToPlaylistModal('${song.id}')">+</button>
                </div>
            `).join('')}
        </div>
    `;

    fetch(`/api/metadata/artist?name=${encodeURIComponent(artist.name)}`)
        .then(res => res.json())
        .then(data => {
            if (data && data.biography) {
                const bioElement = document.getElementById('artist-bio');
                bioElement.innerHTML = `<p>${data.biography.replace(/\n/g, '<br>')}</p>`;
                bioElement.style.display = 'block';
            }
            if (data && data.image) {
                localStorage.setItem('artist_art_' + artist.name, data.image);
                document.querySelector('.detail-art').src = data.image;
            }
        }).catch(() => {});
}

function showPlaylists() {
    currentView = 'playlists';
    updateActiveNavItem('btn-show-playlists');
    document.getElementById('view-title').textContent = 'Playlists';

    const query = searchBar.value.toLowerCase().trim();
    const filteredPlaylists = playlists.filter(p => 
        p.name.toLowerCase().includes(query)
    );

    let html = `
        <div class="playlist-create-bar">
            <input type="text" id="playlist-name-input" class="playlist-input" placeholder="New playlist name...">
            <button class="btn-primary" onclick="createPlaylist()">Create Playlist</button>
        </div>
    `;

    if (filteredPlaylists.length === 0) {
        html += `<p class="status-msg">No playlists found. Create one above!</p>`;
    } else {
        html += `
            <div class="grid-container">
                ${filteredPlaylists.map(playlist => {
                    const resolvedTracks = resolvePlaylistSongs(playlist);
                    const hasTracks = resolvedTracks.length > 0;
                    const artworkUrl = playlist.imageUri ? playlist.imageUri : (hasTracks ? `/api/artwork/${resolvedTracks[0].id}` : 'placeholder.webp');
                    return `
                        <div class="grid-card" onclick="viewPlaylistDetail('${encodeURIComponent(playlist.name)}')">
                            <div class="card-art-container">
                                <img class="card-art" src="${artworkUrl}" alt="${playlist.name}">
                            </div>
                            <div class="card-title">${playlist.name}</div>
                            <div class="card-subtitle">${playlist.songData ? playlist.songData.length : 0} Track(s)</div>
                        </div>
                    `;
                }).join('')}
            </div>
        `;
    }

    tracksContainer.innerHTML = html;
}

async function createPlaylist(name = null) {
    const input = document.getElementById('playlist-name-input');
    const playlistName = name || (input ? input.value.trim() : '');
    if (!playlistName) return;

    if (playlists.some(p => p.name.toLowerCase() === playlistName.toLowerCase())) {
        alert('A playlist with that name already exists!');
        return;
    }

    const newPlaylist = {
        name: playlistName,
        imageUri: '',
        lastModified: Date.now(),
        songData: []
    };
    playlists.push(newPlaylist);
    
    if (input) input.value = '';

    await syncPlaylists();
    showPlaylists();
}

async function syncPlaylists() {
    try {
        const response = await fetch('/api/playlists?client=web', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify(playlists)
        });
        playlists = await response.json();
    } catch (err) {
        console.error('Failed to sync playlists with server:', err);
    }
}

function resolvePlaylistSongs(playlist) {
    if (!playlist || !playlist.songData) return [];
    return playlist.songData.map(ps => {
        let matched = songs.find(s => s.id === ps.id);
        if (!matched) {
            const playlistSongKey = (ps.title || ps.t || '').trim().toLowerCase() + "_" + (ps.artist || ps.a || '').trim().toLowerCase();
            matched = songs.find(s => {
                const librarySongKey = s.title.trim().toLowerCase() + "_" + s.artist.trim().toLowerCase();
                return librarySongKey === playlistSongKey;
            });
        }
        if (matched) {
            return {
                id: matched.id,
                title: matched.title,
                artist: matched.artist,
                album: matched.album,
                duration: matched.duration
            };
        } else {
            return {
                id: ps.id,
                title: ps.title || ps.t || 'Unknown Title',
                artist: ps.artist || ps.a || 'Unknown Artist',
                album: ps.album || 'Unknown Album',
                duration: ps.duration || 0
            };
        }
    });
}

function viewPlaylistDetail(encodedName) {
    const playlistName = decodeURIComponent(encodedName);
    currentDetailItem = playlistName;
    const playlist = playlists.find(p => p.name === playlistName);
    if (!playlist) return;

    currentView = 'playlist-detail';
    updateActiveNavItem('btn-show-playlists');
    document.getElementById('view-title').textContent = playlistName;
    
    const query = searchBar.value.toLowerCase().trim();
    const playlistSongs = resolvePlaylistSongs(playlist);
    const filteredTracks = playlistSongs.filter(song => 
        song.title.toLowerCase().includes(query) || 
        song.artist.toLowerCase().includes(query) || 
        song.album.toLowerCase().includes(query)
    );

    currentQueue = [...filteredTracks];

    const hasTracks = playlistSongs.length > 0;
    const artworkUrl = playlist.imageUri ? playlist.imageUri : (hasTracks ? `/api/artwork/${playlistSongs[0].id}` : 'placeholder.webp');

    tracksContainer.innerHTML = `
        <button class="back-btn" onclick="searchBar.value=''; showPlaylists()">← Back to Playlists</button>
        <div class="detail-header">
            <img class="detail-art" src="${artworkUrl}" alt="${playlist.name}">
            <div class="detail-info">
                <span class="detail-type">Playlist</span>
                <h1 class="detail-title">${playlist.name}</h1>
                <span class="detail-meta">${playlistSongs.length} track(s)</span>
                <div class="detail-actions">
                    <button class="btn-primary" ${hasTracks ? '' : 'disabled'} onclick="playTrack(0)">Play All</button>
                    <button class="btn-secondary" ${hasTracks ? '' : 'disabled'} onclick="shufflePlay()">Shuffle Play</button>
                    <button class="btn-secondary" onclick="renamePlaylist('${encodeURIComponent(playlist.name)}')">Rename</button>
                    <button class="btn-secondary" onclick="triggerPlaylistCoverUpload('${encodeURIComponent(playlist.name)}')">Change Cover</button>
                    <button class="btn-secondary" style="color: #ef4444; border-color: rgba(239, 68, 68, 0.2);" onclick="deletePlaylist('${encodeURIComponent(playlist.name)}')">Delete Playlist</button>
                </div>
                <input type="file" id="playlist-cover-input-${encodeURIComponent(playlist.name)}" accept="image/*" style="display: none;" onchange="uploadPlaylistCover(event, '${encodeURIComponent(playlist.name)}')">
            </div>
        </div>
        <div class="tracks-list">
            ${filteredTracks.map((song, index) => `
                <div class="track-row" onclick="playTrack(${index})">
                    <img class="row-art" src="/api/artwork/${song.id}" alt="Cover">
                    <div class="row-title">${song.title}</div>
                    <div class="row-artist">${song.artist}</div>
                    <div class="row-album">${song.album}</div>
                    <button class="btn-add-to-playlist" style="font-size: 16px; color: #ef4444;" onclick="event.stopPropagation(); removeTrackFromPlaylist('${encodeURIComponent(playlist.name)}', '${song.id}')">✕</button>
                </div>
            `).join('')}
        </div>
    `;
}

async function deletePlaylist(encodedName) {
    const playlistName = decodeURIComponent(encodedName);
    if (!confirm(`Are you sure you want to delete the playlist "${playlistName}"?`)) return;

    playlists = playlists.filter(p => p.name !== playlistName);
    await syncPlaylists();
    showPlaylists();
}

async function renamePlaylist(encodedName) {
    const oldName = decodeURIComponent(encodedName);
    const playlist = playlists.find(p => p.name === oldName);
    if (!playlist) return;

    const newName = prompt('Enter new playlist name:', oldName);
    if (!newName || newName.trim() === '' || newName.trim() === oldName) return;

    const trimmedName = newName.trim();
    if (playlists.some(p => p.name.toLowerCase() === trimmedName.toLowerCase())) {
        alert('A playlist with that name already exists!');
        return;
    }

    playlist.name = trimmedName;
    playlist.lastModified = Date.now();
    await syncPlaylists();
    viewPlaylistDetail(encodeURIComponent(trimmedName));
}

function triggerPlaylistCoverUpload(encodedName) {
    const input = document.getElementById(`playlist-cover-input-${encodedName}`);
    if (input) {
        input.click();
    }
}

async function uploadPlaylistCover(event, encodedName) {
    const file = event.target.files[0];
    if (!file) return;

    const playlistName = decodeURIComponent(encodedName);
    const playlist = playlists.find(p => p.name === playlistName);
    if (!playlist) return;

    const reader = new FileReader();
    reader.onload = async function(e) {
        playlist.imageUri = e.target.result;
        playlist.lastModified = Date.now();
        await syncPlaylists();
        viewPlaylistDetail(encodedName);
    };
    reader.readAsDataURL(file);
}

async function removeTrackFromPlaylist(encodedPlaylistName, songId) {
    const playlistName = decodeURIComponent(encodedPlaylistName);
    const playlist = playlists.find(p => p.name === playlistName);
    if (!playlist) return;

    const songToRemove = songs.find(s => s.id === songId);
    playlist.songData = playlist.songData.filter(s => {
        if (s.id === songId) return false;
        if (songToRemove) {
            const sTitle = (s.title || s.t || '').trim().toLowerCase();
            const sArtist = (s.artist || s.a || '').trim().toLowerCase();
            const rTitle = songToRemove.title.trim().toLowerCase();
            const rArtist = songToRemove.artist.trim().toLowerCase();
            if (sTitle === rTitle && sArtist === rArtist) return false;
        }
        return true;
    });
    playlist.lastModified = Date.now();
    await syncPlaylists();
    viewPlaylistDetail(encodedPlaylistName);
}

let songIdToAdd = null;

function openAddToPlaylistModal(songId) {
    songIdToAdd = songId;
    const modal = document.getElementById('playlist-modal');
    const listContainer = document.getElementById('modal-playlists-list');
    
    listContainer.innerHTML = '';
    
    if (playlists.length === 0) {
        listContainer.innerHTML = '<p style="color: var(--text-muted); font-size: 14px;">No playlists. Create one below!</p>';
    } else {
        listContainer.innerHTML = playlists.map(playlist => `
            <div class="modal-list-item" onclick="addTrackToPlaylist('${encodeURIComponent(playlist.name)}')">
                ${playlist.name}
            </div>
        `).join('');
    }
    
    modal.style.display = 'flex';
}

function closePlaylistModal() {
    const modal = document.getElementById('playlist-modal');
    modal.style.display = 'none';
    songIdToAdd = null;
    document.getElementById('modal-playlist-name').value = '';
}

async function addTrackToPlaylist(encodedPlaylistName) {
    const playlistName = decodeURIComponent(encodedPlaylistName);
    const playlist = playlists.find(p => p.name === playlistName);
    const song = songs.find(s => s.id === songIdToAdd);
    
    if (playlist && song) {
        const resolvedTracks = resolvePlaylistSongs(playlist);
        const songKey = song.title.trim().toLowerCase() + "_" + song.artist.trim().toLowerCase();
        
        const exists = resolvedTracks.some(rt => {
            const rtKey = rt.title.trim().toLowerCase() + "_" + rt.artist.trim().toLowerCase();
            return rtKey === songKey;
        });

        if (exists) {
            alert('This track is already in the playlist!');
            closePlaylistModal();
            return;
        }
        playlist.songData.push({
            id: song.id,
            title: song.title,
            artist: song.artist,
            album: song.album,
            duration: song.duration,
            t: song.title,
            a: song.artist
        });
        playlist.lastModified = Date.now();
        await syncPlaylists();
    }
    
    closePlaylistModal();
}

async function modalCreatePlaylist() {
    const input = document.getElementById('modal-playlist-name');
    const name = input.value.trim();
    if (!name) return;

    if (playlists.some(p => p.name.toLowerCase() === name.toLowerCase())) {
        alert('A playlist with that name already exists!');
        return;
    }

    const newPlaylist = {
        name: name,
        imageUri: '',
        lastModified: Date.now(),
        songData: []
    };
    playlists.push(newPlaylist);
    input.value = '';

    await syncPlaylists();
    
    if (songIdToAdd) {
        await addTrackToPlaylist(encodeURIComponent(name));
    } else {
        closePlaylistModal();
    }
}

function showSettings() {
    currentView = 'settings';
    updateActiveNavItem('btn-show-settings');
    document.getElementById('view-title').textContent = 'Settings';
    
    tracksContainer.innerHTML = `
        <div class="settings-container">
            <div class="settings-card">
                <h3>Library Tools</h3>
                <p>Maintain and optimize your music collection database.</p>
                <div class="settings-actions">
                    <button class="btn-primary" id="btn-run-deduplicate">Run Library Deduplicator</button>
                    <button class="btn-secondary" onclick="fileInput.click()">Upload More Tracks</button>
                </div>
                <div id="deduplicate-result" class="status-msg" style="display: none; margin-top: 16px; padding: 12px; background: rgba(255,255,255,0.02); border-radius: 8px;"></div>
            </div>
            
            <div class="settings-card" style="margin-top: 24px;">
                <h3>Display Customizations</h3>
                <p>Adjust how metadata and artists are presented in the library.</p>
                <div class="settings-option">
                    <label class="switch-label">
                        <input type="checkbox" id="toggle-consolidate-artists" ${consolidateArtists ? 'checked' : ''}>
                        Consolidate Featured Artists (e.g. merge "Artist ft. guest" under "Artist")
                    </label>
                </div>
            </div>
        </div>
    `;
    
    document.getElementById('btn-run-deduplicate').addEventListener('click', runDeduplication);
    document.getElementById('toggle-consolidate-artists').addEventListener('change', (e) => {
        consolidateArtists = e.target.checked;
        localStorage.setItem('consolidateArtists', consolidateArtists);
    });
}

async function runDeduplication() {
    const btn = document.getElementById('btn-run-deduplicate');
    const resultDiv = document.getElementById('deduplicate-result');
    btn.disabled = true;
    btn.textContent = 'Analyzing Database...';
    resultDiv.style.display = 'block';
    resultDiv.textContent = 'Deduplication script is running in the background...';
    
    try {
        const response = await fetch('/api/deduplicate', { method: 'POST' });
        if (!response.ok) throw new Error('Deduplicate API returned error');
        const res = await response.json();
        resultDiv.innerHTML = `<span style="color: var(--vibe-color); font-weight: 600;">${res.message}</span><br>Scanned: ${res.scanned} files | Duplicates Removed: ${res.duplicatesFound}`;
        await loadLibrary();
    } catch (err) {
        resultDiv.textContent = 'Error: Failed to execute library deduplication.';
    } finally {
        btn.disabled = false;
        btn.textContent = 'Run Library Deduplicator';
    }
}
