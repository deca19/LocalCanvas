package com.localcanvas.app;

import org.junit.Test;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class ModelManagerAtomicReplaceTest {
    @Test
    public void replacementKeepsNewFileAndRemovesBackup() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "lc-atomic-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File oldFile = new File(dir, "model.model");
        File part = new File(dir, "model.model.part");
        try (FileOutputStream out = new FileOutputStream(oldFile)) { out.write("old".getBytes(StandardCharsets.US_ASCII)); }
        try (FileOutputStream out = new FileOutputStream(part)) { out.write("GGUFnew".getBytes(StandardCharsets.US_ASCII)); }
        ModelManager.atomicReplace(part, oldFile);
        assertEquals("GGUFnew", new String(java.nio.file.Files.readAllBytes(oldFile.toPath()), StandardCharsets.US_ASCII));
        assertFalse(part.exists());
        assertFalse(new File(oldFile.getPath() + ".bak").exists());
        oldFile.delete();
        dir.delete();
    }

    @Test
    public void missingReplacementDoesNotRemoveExistingFile() throws Exception {
        File dir = new File(System.getProperty("java.io.tmpdir"), "lc-atomic-missing-" + System.nanoTime());
        assertTrue(dir.mkdirs());
        File oldFile = new File(dir, "model.model");
        try (FileOutputStream out = new FileOutputStream(oldFile)) { out.write("old".getBytes(StandardCharsets.US_ASCII)); }
        try {
            ModelManager.atomicReplace(new File(dir, "missing.part"), oldFile);
            throw new AssertionError("expected failure");
        } catch (java.io.IOException expected) {
        }
        assertEquals("old", new String(java.nio.file.Files.readAllBytes(oldFile.toPath()), StandardCharsets.US_ASCII));
        oldFile.delete();
        dir.delete();
    }
}
