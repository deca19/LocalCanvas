package com.localcanvas.app;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Same-filesystem atomic replacement; fail closed rather than delete or copy over the old model. */
public final class AtomicModelFile {
    private AtomicModelFile() {}

    public static void replace(File part, File dest) throws IOException {
        if (part == null || dest == null || !part.isFile() || part.length() == 0) {
            throw new IOException("Invalid replacement source");
        }
        File canonicalPart = part.getCanonicalFile();
        File canonicalDest = dest.getCanonicalFile();
        if (canonicalPart.equals(canonicalDest)) {
            throw new IOException("Replacement source and destination are identical");
        }
        if (!canonicalPart.getParentFile().equals(canonicalDest.getParentFile())) {
            throw new IOException("Replacement must be staged in destination directory");
        }
        try (RandomAccessFile f = new RandomAccessFile(part, "rw")) {
            f.getFD().sync();
        }
        Files.move(part.toPath(), dest.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
    }

    /** Cleans up stale import/backup files without touching valid installed models or active .part files. */
    public static int cleanupStaleArtifacts(File dir) {
        if (dir == null || !dir.isDirectory()) return 0;
        File[] files = dir.listFiles();
        if (files == null) return 0;
        int removed = 0;
        for (File f : files) {
            String name = f.getName();
            if (name.endsWith(".import.part") || name.endsWith(".bak")) {
                if (f.delete()) removed++;
            }
        }
        return removed;
    }
}
