const express = require('express');
const path = require('path');
const fs = require('fs');
const mm = require('music-metadata');
const multer = require('multer');

const app = express();
const PORT = process.env.PORT || 1337;

// Scan directory: falls back to a subfolder inside this project
const musicDir = process.env.MUSIC_DIR || path.join(__dirname, 'music');
const playlistsFile = path.join(musicDir, 'playlists.json');
const deletedPlaylistsFile = path.join(musicDir, 'deleted_playlists.json');

if (!fs.existsSync(musicDir)) {
    fs.mkdirSync(musicDir, { recursive: true });
}

app.use(express.static(path.join(__dirname, 'public')));

// Configure Multer for track uploads
const storage = multer.diskStorage({
    destination: (req, file, cb) => {
        cb(null, musicDir);
    },
    filename: (req, file, cb) => {
        // Retain original file name safely
        cb(null, file.originalname);
    }
});

const upload = multer({ storage });

// Recursive helper to find all MP3 files
function getMp3Files(dir, fileList = []) {
    try {
        const files = fs.readdirSync(dir);
        files.forEach(file => {
            const filePath = path.join(dir, file);
            
            const filenameByteLength = Buffer.byteLength(file, 'utf8');
            if (filenameByteLength >= 250) {
                console.warn(`Skipping scan of extremely long filename (${filenameByteLength} bytes): ${file}`);
                return;
            }

            try {
                const stat = fs.statSync(filePath);
                if (stat.isDirectory()) {
                    getMp3Files(filePath, fileList);
                } else if (file.toLowerCase().endsWith('.mp3')) {
                    fileList.push(filePath);
                }
            } catch (err) {
                console.error(`Skipping file due to scan error: ${filePath}`, err);
            }
        });
    } catch (err) {
        console.error(`Skipping directory due to scan error: ${dir}`, err);
    }
    return fileList;
}

// Fetch all songs on the Pi server
app.get('/api/songs', async (req, res) => {
    console.log('GET /api/songs - Library scan requested');
    try {
        const mp3Paths = getMp3Files(musicDir);
        const songs = [];

        for (let i = 0; i < mp3Paths.length; i++) {
            const filePath = mp3Paths[i];
            
            // Delete and skip zero-byte or corrupt files from failed transfers
            try {
                const stats = fs.statSync(filePath);
                if (stats.size === 0) {
                    console.log(`Removing empty/corrupt file from failed transfer: ${filePath}`);
                    fs.unlinkSync(filePath);
                    continue;
                }
            } catch (err) {
                continue;
            }

            const relativePath = path.relative(musicDir, filePath);
            const id = Buffer.from(relativePath).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, ''); // Safe ID base64
            
            let title = path.basename(filePath, '.mp3');
            let artist = 'Unknown Artist';
            let album = 'Unknown Album';
            let duration = 0;

            try {
                const metadata = await mm.parseFile(filePath);
                title = metadata.common.title || title;
                artist = metadata.common.artist || artist;
                album = metadata.common.album || album;
                duration = metadata.format.duration || 0;
            } catch (e) {
                // Return default naming if metadata parsing fails
            }

            songs.push({ id, title, artist, album, duration });
        }
        res.json(songs);
    } catch (err) {
        console.error('Error scanning music library:', err);
        res.status(500).json({ error: 'Failed to scan music library' });
    }
});

// Stream audio file with HTTP Range support for seek operations
app.get('/api/stream/:id', (req, res) => {
    try {
        const idStr = req.params.id.replace(/-/g, '+').replace(/_/g, '/');
        const relativePath = Buffer.from(idStr, 'base64').toString('utf8');
        const filePath = path.join(musicDir, relativePath);
        console.log(`GET /api/stream - Streaming requested for: ${relativePath}`);

        if (!fs.existsSync(filePath)) {
            return res.status(404).send('File not found');
        }

        const stat = fs.statSync(filePath);
        const fileSize = stat.size;
        const range = req.headers.range;

        if (range) {
            const parts = range.replace(/bytes=/, "").split("-");
            const start = parseInt(parts[0], 10);
            const end = parts[1] ? parseInt(parts[1], 10) : fileSize - 1;
            const chunksize = (end - start) + 1;
            const file = fs.createReadStream(filePath, { start, end });
            const head = {
                'Content-Range': `bytes ${start}-${end}/${fileSize}`,
                'Accept-Ranges': 'bytes',
                'Content-Length': chunksize,
                'Content-Type': 'audio/mpeg',
            };
            res.writeHead(206, head);
            file.pipe(res);
        } else {
            const head = {
                'Content-Length': fileSize,
                'Content-Type': 'audio/mpeg',
            };
            res.writeHead(200, head);
            fs.createReadStream(filePath).pipe(res);
        }
    } catch (err) {
        console.error('Error streaming file:', err);
        res.status(500).send('Streaming error');
    }
});

// Extract album artwork buffer from ID3 tags
app.get('/api/artwork/:id', async (req, res) => {
    try {
        const idStr = req.params.id.replace(/-/g, '+').replace(/_/g, '/');
        const relativePath = Buffer.from(idStr, 'base64').toString('utf8');
        const filePath = path.join(musicDir, relativePath);
        console.log(`GET /api/artwork - Art extraction requested for: ${relativePath}`);

        if (fs.existsSync(filePath)) {
            const metadata = await mm.parseFile(filePath);
            const picture = metadata.common.picture && metadata.common.picture[0];
            if (picture) {
                res.contentType(picture.format);
                return res.send(picture.data);
            }
        }
    } catch (e) {
        console.error('Error extracting artwork:', e);
    }
    // Fallback to placeholder image
    res.sendFile(path.join(__dirname, 'public', 'placeholder.webp'));
});

// Upload files endpoint
app.post('/api/upload', upload.array('files'), (req, res) => {
    try {
        const filesCount = req.files ? req.files.length : 0;
        console.log(`POST /api/upload - Received upload request for ${filesCount} files`);
        res.json({ success: true, count: req.files.length });
    } catch (err) {
        console.error('Error handling upload:', err);
        res.status(500).json({ error: 'Upload failed' });
    }
});

// Fetch synced playlists configuration
app.get('/api/playlists', (req, res) => {
    console.log('GET /api/playlists - Playlists fetch requested');
    try {
        if (fs.existsSync(playlistsFile)) {
            const data = fs.readFileSync(playlistsFile, 'utf8');
            return res.json(JSON.parse(data));
        }
        res.json([]);
    } catch (e) {
        console.error('Error fetching playlists:', e);
        res.status(500).json({ error: 'Failed to read playlists' });
    }
});

function getDeletedState() {
    try {
        if (fs.existsSync(deletedPlaylistsFile)) {
            const state = JSON.parse(fs.readFileSync(deletedPlaylistsFile, 'utf8'));
            const cutoff = Date.now() - 30 * 24 * 60 * 60 * 1000;
            let updated = false;
            
            if (state.playlists) {
                for (const name in state.playlists) {
                    if (state.playlists[name] < cutoff) {
                        delete state.playlists[name];
                        updated = true;
                    }
                }
            } else {
                state.playlists = {};
                updated = true;
            }
            
            if (state.tracks) {
                for (const plName in state.tracks) {
                    let plUpdated = false;
                    for (const songId in state.tracks[plName]) {
                        if (state.tracks[plName][songId] < cutoff) {
                            delete state.tracks[plName][songId];
                            plUpdated = true;
                            updated = true;
                        }
                    }
                    if (plUpdated && Object.keys(state.tracks[plName]).length === 0) {
                        delete state.tracks[plName];
                    }
                }
            } else {
                state.tracks = {};
                updated = true;
            }
            
            if (updated) {
                fs.writeFileSync(deletedPlaylistsFile, JSON.stringify(state, null, 2), 'utf8');
            }
            return state;
        }
    } catch (e) {
        console.error('Error reading deleted state:', e);
    }
    return { playlists: {}, tracks: {} };
}

function saveDeletedState(state) {
    try {
        fs.writeFileSync(deletedPlaylistsFile, JSON.stringify(state, null, 2), 'utf8');
    } catch (e) {
        console.error('Error saving deleted state:', e);
    }
}

// Sync and merge playlists
app.post('/api/playlists', express.json({ limit: '50mb' }), (req, res) => {
    try {
        const clientPlaylists = req.body || [];
        const isWebClient = req.query.client === 'web';
        console.log(`POST /api/playlists - Sync request (isWebClient: ${isWebClient})`);

        let serverPlaylists = [];
        if (fs.existsSync(playlistsFile)) {
            serverPlaylists = JSON.parse(fs.readFileSync(playlistsFile, 'utf8'));
        }

        const deletedState = getDeletedState();

        if (isWebClient) {
            // Web Client Update - Autoritative Overwrite
            const clientMap = new Map(clientPlaylists.map(p => [p.name, p]));
            const serverMap = new Map(serverPlaylists.map(p => [p.name, p]));

            // Detect deleted playlists
            serverPlaylists.forEach(serverP => {
                if (!clientMap.has(serverP.name)) {
                    deletedState.playlists[serverP.name] = Date.now();
                    console.log(`Web client deleted playlist: "${serverP.name}"`);
                }
            });

            // Detect track removals in existing playlists
            clientPlaylists.forEach(clientP => {
                const serverP = serverMap.get(clientP.name);
                if (serverP) {
                    const clientSongIds = new Set(clientP.songData.map(s => s.id));
                    serverP.songData.forEach(song => {
                        if (!clientSongIds.has(song.id)) {
                            if (!deletedState.tracks[clientP.name]) {
                                deletedState.tracks[clientP.name] = {};
                            }
                            deletedState.tracks[clientP.name][song.id] = Date.now();
                            console.log(`Web client removed song "${song.id}" from playlist "${clientP.name}"`);
                        }
                    });
                }
            });

            saveDeletedState(deletedState);
            fs.writeFileSync(playlistsFile, JSON.stringify(clientPlaylists, null, 2), 'utf8');
            return res.json(clientPlaylists);
        }

        // Android Sync - Merge filtering out tombstones
        const playlistMap = new Map();
        serverPlaylists.forEach(p => playlistMap.set(p.name, p));

        clientPlaylists.forEach(clientP => {
            if (deletedState.playlists[clientP.name]) {
                console.log(`Ignoring deleted playlist from Android sync: "${clientP.name}"`);
                return;
            }

            const serverP = playlistMap.get(clientP.name);
            if (!serverP) {
                const deletedSongs = deletedState.tracks[clientP.name] || {};
                clientP.songData = clientP.songData.filter(s => !deletedSongs[s.id]);
                playlistMap.set(clientP.name, clientP);
            } else {
                const deletedSongs = deletedState.tracks[clientP.name] || {};
                const songIds = new Set(serverP.songData.map(s => s.id));
                clientP.songData.forEach(s => {
                    if (!songIds.has(s.id) && !deletedSongs[s.id]) {
                        serverP.songData.push(s);
                    }
                });
            }
        });

        const mergedPlaylists = Array.from(playlistMap.values());
        mergedPlaylists.forEach(p => {
            const deletedSongs = deletedState.tracks[p.name] || {};
            p.songData = p.songData.filter(s => !deletedSongs[s.id]);
        });

        fs.writeFileSync(playlistsFile, JSON.stringify(mergedPlaylists, null, 2), 'utf8');
        res.json(mergedPlaylists);
    } catch (e) {
        console.error('Error syncing playlists:', e);
        res.status(500).json({ error: 'Failed to sync playlists' });
    }
});

app.post('/api/deduplicate', async (req, res) => {
    console.log('POST /api/deduplicate - Running library deduplication');
    try {
        const { deduplicate } = require('./deduplicate');
        const result = await deduplicate();
        res.json(result);
    } catch (err) {
        console.error('Error running deduplication:', err);
        res.status(500).json({ error: 'Deduplication failed', details: err.message });
    }
});

app.listen(PORT, () => {
    console.log(`VibeStation Server listening on http://localhost:${PORT}`);
    console.log(`Scan Folder: ${musicDir}`);
});
