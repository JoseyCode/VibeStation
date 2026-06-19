const express = require('express');
const path = require('path');
const fs = require('fs');
const mm = require('music-metadata');
const multer = require('multer');

const app = express();
const PORT = process.env.PORT || 1337;

// Scan directory: falls back to a subfolder inside this project
const musicDir = process.env.MUSIC_DIR || path.join(__dirname, 'music');

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
    try {
        const mp3Paths = getMp3Files(musicDir);
        const songs = [];

        for (let i = 0; i < mp3Paths.length; i++) {
            const filePath = mp3Paths[i];
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
        res.status(500).json({ error: 'Failed to scan music library' });
    }
});

// Stream audio file with HTTP Range support for seek operations
app.get('/api/stream/:id', (req, res) => {
    try {
        const relativePath = Buffer.from(req.params.id, 'base64url').toString('utf8');
        const filePath = path.join(musicDir, relativePath);

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
        res.status(500).send('Streaming error');
    }
});

// Extract album artwork buffer from ID3 tags
app.get('/api/artwork/:id', async (req, res) => {
    try {
        const relativePath = Buffer.from(req.params.id, 'base64url').toString('utf8');
        const filePath = path.join(musicDir, relativePath);

        if (fs.existsSync(filePath)) {
            const metadata = await mm.parseFile(filePath);
            const picture = metadata.common.picture && metadata.common.picture[0];
            if (picture) {
                res.contentType(picture.format);
                return res.send(picture.data);
            }
        }
    } catch (e) {}
    // Fallback to placeholder image
    res.sendFile(path.join(__dirname, 'public', 'placeholder.png'));
});

// Upload files endpoint
app.post('/api/upload', upload.array('files'), (req, res) => {
    try {
        res.json({ success: true, count: req.files.length });
    } catch (err) {
        res.status(500).json({ error: 'Upload failed' });
    }
});

app.listen(PORT, () => {
    console.log(`VibeStation Server listening on http://localhost:${PORT}`);
    console.log(`Scan Folder: ${musicDir}`);
});
