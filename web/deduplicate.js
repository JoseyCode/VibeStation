const fs = require('fs');
const path = require('path');
const mm = require('music-metadata');

const musicDir = process.env.MUSIC_DIR || path.join(__dirname, 'music');
const playlistsFile = path.join(musicDir, 'playlists.json');

// Helper to calculate the ID the exact same way server.js does
function getFileId(filePath) {
    const relativePath = path.relative(musicDir, filePath);
    return Buffer.from(relativePath).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

// Same logic as Android app's SyncManager
function makeMatchKey(title, artist) {
    return (title + "_" + artist).toLowerCase().replace(/[^a-z0-9_]/g, '');
}

function getMp3Files(dir, fileList = []) {
    try {
        if (!fs.existsSync(dir)) return fileList;
        const files = fs.readdirSync(dir);
        files.forEach(file => {
            const filePath = path.join(dir, file);
            
            const filenameByteLength = Buffer.byteLength(file, 'utf8');
            if (filenameByteLength >= 250) return;

            try {
                const stat = fs.statSync(filePath);
                if (stat.isDirectory()) {
                    getMp3Files(filePath, fileList);
                } else if (file.toLowerCase().endsWith('.mp3')) {
                    if (stat.size > 0) fileList.push(filePath);
                }
            } catch (err) {}
        });
    } catch (err) {}
    return fileList;
}

async function deduplicate() {
    console.log(`Scanning for duplicates in ${musicDir}...`);
    const files = getMp3Files(musicDir);
    const seenSongs = new Map();
    let duplicateCount = 0;

    let playlists = [];
    if (fs.existsSync(playlistsFile)) {
        playlists = JSON.parse(fs.readFileSync(playlistsFile, 'utf8'));
    }

    for (const filePath of files) {
        let title = path.basename(filePath, '.mp3');
        let artist = 'Unknown Artist';

        try {
            const metadata = await mm.parseFile(filePath);
            title = metadata.common.title || title;
            artist = metadata.common.artist || artist;
        } catch (e) {}

        const matchKey = makeMatchKey(title, artist);
        
        if (seenSongs.has(matchKey)) {
            duplicateCount++;
            const primaryPath = seenSongs.get(matchKey);
            const primaryId = getFileId(primaryPath);
            const duplicateId = getFileId(filePath);

            console.log(`Found duplicate: ${title} by ${artist}`);
            console.log(`  Primary: ${primaryPath}`);
            console.log(`  Deleting: ${filePath}`);

            // Replace occurrences in playlists
            let changedPlaylists = 0;
            playlists.forEach(p => {
                let modified = false;
                p.songData.forEach(song => {
                    if (song.id === duplicateId) {
                        song.id = primaryId;
                        modified = true;
                    }
                });
                if (modified) changedPlaylists++;
            });

            if (changedPlaylists > 0) {
                console.log(`  Updated ${changedPlaylists} playlists referencing this duplicate.`);
            }

            try {
                fs.unlinkSync(filePath);
            } catch (e) {
                console.error(`  Failed to delete ${filePath}`, e);
            }
        } else {
            seenSongs.set(matchKey, filePath);
        }
    }

    if (duplicateCount > 0) {
        fs.writeFileSync(playlistsFile, JSON.stringify(playlists, null, 2), 'utf8');
        console.log(`\nDeduplication complete. Removed ${duplicateCount} duplicates.`);
    } else {
        console.log('\nNo duplicates found. Database is clean!');
    }
}

deduplicate().catch(err => console.error(err));
