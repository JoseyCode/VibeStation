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
    const files = fs.readdirSync(dir);
    files.forEach(file => {
        const filePath = path.join(dir, file);
        if (fs.statSync(filePath).isDirectory()) {
            getMp3Files(filePath, fileList);
        } else if (file.toLowerCase().endsWith('.mp3')) {
            fileList.push(filePath);
        }
    });
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
            const id = Buffer.from(relativePath).toString('base64url'); // Safe ID base64
            
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
        const relativePath = Buffer.from(req.params.id, 'base64url').toString('utf8');
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
        const relativePath = Buffer.from(req.params.id, 'base64url').toString('utf8');
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
    res.sendFile(path.join(__dirname, 'public', 'placeholder.png'));
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

// Merge and backup local playlists on the server
app.post('/api/playlists', express.json({ limit: '50mb' }), (req, res) => {
    try {
        const clientPlaylists = req.body;
        const playlistsCount = clientPlaylists ? clientPlaylists.length : 0;
        console.log(`POST /api/playlists - Sync request for ${playlistsCount} playlists`);

        let serverPlaylists = [];
        if (fs.existsSync(playlistsFile)) {
            serverPlaylists = JSON.parse(fs.readFileSync(playlistsFile, 'utf8'));
        }

        const playlistMap = new Map();
        serverPlaylists.forEach(p => playlistMap.set(p.name, p));

        clientPlaylists.forEach(clientP => {
            const serverP = playlistMap.get(clientP.name);
            if (!serverP) {
                playlistMap.set(clientP.name, clientP);
            } else {
                // Merge songs list unions
                const songIds = new Set(serverP.songData.map(s => s.id));
                clientP.songData.forEach(s => {
                    if (!songIds.has(s.id)) {
                        serverP.songData.push(s);
                    }
                });
            }
        });

        const mergedPlaylists = Array.from(playlistMap.values());
        fs.writeFileSync(playlistsFile, JSON.stringify(mergedPlaylists, null, 2), 'utf8');
        res.json(mergedPlaylists);
    } catch (e) {
        console.error('Error syncing playlists:', e);
        res.status(500).json({ error: 'Failed to sync playlists' });
    }
});

app.listen(PORT, () => {
    console.log(`VibeStation Server listening on http://localhost:${PORT}`);
    console.log(`Scan Folder: ${musicDir}`);
});
