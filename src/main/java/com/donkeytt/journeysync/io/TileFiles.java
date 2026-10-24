package com.donkeytt.journeysync.io;

import java.io.File;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import com.donkeytt.journeysync.data.TileKey;

/**
 * Tile file access under a fixed base directory, with path-traversal protection and atomic writes
 * (temp file + move) so JourneyMap never observes a partially written PNG.
 */
public final class TileFiles {

    private final File baseDir;
    private final String baseCanonical;

    public TileFiles(File baseDir) {
        this.baseDir = baseDir;
        try {
            this.baseCanonical = baseDir.getCanonicalPath();
        } catch (IOException e) {
            throw new IllegalStateException("Bad tile base dir: " + baseDir, e);
        }
    }

    public File baseDir() {
        return baseDir;
    }

    /**
     * Resolve a tile key to a file under the base dir. The result is guaranteed to stay inside the
     * base directory.
     */
    public File resolve(TileKey key) {
        File f = new File(baseDir, key.relPath());
        try {
            String canonical = f.getCanonicalPath();
            if (!canonical.startsWith(baseCanonical + File.separator)) {
                throw new SecurityException("Tile path escapes base dir: " + key.relPath());
            }
        } catch (IOException e) {
            throw new IllegalStateException("Cannot resolve tile path: " + key.relPath(), e);
        }
        return f;
    }

    public boolean exists(TileKey key) {
        return resolve(key).isFile();
    }

    public byte[] read(TileKey key) throws IOException {
        return Files.readAllBytes(resolve(key).toPath());
    }

    /**
     * Write tile bytes atomically: write to a temp sibling then move into place.
     */
    public void writeAtomic(TileKey key, byte[] data) throws IOException {
        File target = resolve(key);
        File parent = target.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("Cannot create directory: " + parent);
        }
        Path targetPath = target.toPath();
        Path tmp = Files.createTempFile(parent.toPath(), target.getName(), ".part");
        try {
            Files.write(tmp, data);
            moveIntoPlace(tmp, targetPath);
        } finally {
            Files.deleteIfExists(tmp);
        }
    }

    private static void moveIntoPlace(Path tmp, Path target) throws IOException {
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
