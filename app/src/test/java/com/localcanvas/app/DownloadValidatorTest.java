package com.localcanvas.app;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DownloadValidatorTest {
    @Test
    public void parsesValidRange() throws Exception {
        DownloadValidator.Range r = DownloadValidator.parseContentRange("bytes 10-19/100");
        assertEquals(10, r.start);
        assertEquals(19, r.end);
        assertEquals(100, r.total);
    }

    @Test(expected = IOException.class)
    public void rejectsBadRange() throws IOException {
        DownloadValidator.parseContentRange("bytes 20-10/100");
    }

    @Test
    public void acceptsMatchingResume() throws Exception {
        assertTrue(DownloadValidator.acceptsResume("bytes 10-99/100", 10, 10));
    }

    @Test
    public void rejectsMismatchedResume() throws Exception {
        assertFalse(DownloadValidator.acceptsResume("bytes 11-99/100", 10, 10));
    }

    @Test
    public void rejectsTruncatedGgufHeader() throws Exception {
        File f = File.createTempFile("model", ".gguf");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write("GGUF".getBytes(StandardCharsets.US_ASCII));
            writeLe32(o, 3);
            writeLe64(o, 1);
            writeLe64(o, 1);
            o.write(0);
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void acceptsValidMinimalGguf() throws Exception {
        File f = File.createTempFile("valid", ".gguf");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write(buildMinimalValidGguf());
        }
        assertTrue(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsCorruptModel() throws Exception {
        File f = File.createTempFile("model", ".bin");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write("bad-file".getBytes(StandardCharsets.US_ASCII));
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsEightByteFakeGguf() throws Exception {
        File f = File.createTempFile("model", ".bin");
        try (FileOutputStream o = new FileOutputStream(f)) {
            o.write("GGUF1234".getBytes(StandardCharsets.US_ASCII));
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void acceptsSafeTensorsWithBoundedOffsets() throws Exception {
        File f = File.createTempFile("model", ".safetensors");
        String json = "{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]}}";
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream o = new FileOutputStream(f)) {
            writeLe64(o, h.length);
            o.write(h);
            o.write(new byte[]{0, 0, 0, 0});
        }
        assertTrue(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsSafeTensorsOutOfBounds() throws Exception {
        File f = File.createTempFile("model", ".safetensors");
        String json = "{\"x\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,400]}}";
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream o = new FileOutputStream(f)) {
            writeLe64(o, h.length);
            o.write(h);
            o.write(new byte[]{0, 0, 0, 0});
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsSafeTensorsShapeByteMismatch() throws Exception {
        File f = File.createTempFile("model", ".safetensors");
        String json = "{\"x\":{\"dtype\":\"F32\",\"shape\":[2,2],\"data_offsets\":[0,4]}}";
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream o = new FileOutputStream(f)) {
            writeLe64(o, h.length);
            o.write(h);
            o.write(new byte[16]);
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsOverlappingSafeTensorsOffsets() throws Exception {
        File f = File.createTempFile("overlap", ".safetensors");
        String json = "{\"a\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[0,8]},"
                + "\"b\":{\"dtype\":\"F32\",\"shape\":[2],\"data_offsets\":[4,12]}}";
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream o = new FileOutputStream(f)) {
            writeLe64(o, h.length);
            o.write(h);
            o.write(new byte[12]);
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    @Test
    public void rejectsDuplicateKeysInSafeTensorsHeader() throws Exception {
        File f = File.createTempFile("dupkey", ".safetensors");
        String json = "{\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[0,4]},"
                + "\"a\":{\"dtype\":\"F32\",\"shape\":[1],\"data_offsets\":[4,8]}}";
        byte[] h = json.getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream o = new FileOutputStream(f)) {
            writeLe64(o, h.length);
            o.write(h);
            o.write(new byte[8]);
        }
        assertFalse(DownloadValidator.isSupportedModel(f));
        assertTrue(f.delete());
    }

    public static byte[] buildMinimalValidGguf() throws IOException {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write("GGUF".getBytes(StandardCharsets.US_ASCII));
        writeLe32(o, 3);
        writeLe64(o, 1);
        writeLe64(o, 1);
        writeGgufStr(o, "general.architecture");
        writeLe32(o, 8);
        writeGgufStr(o, "sd15");
        writeGgufStr(o, "w");
        writeLe32(o, 1);
        writeLe64(o, 4);
        writeLe32(o, 0);
        writeLe64(o, 0);
        while (o.size() % 32 != 0) o.write(0);
        o.write(new byte[32]);
        return o.toByteArray();
    }

    private static void writeGgufStr(OutputStream o, String s) throws IOException {
        byte[] b = s.getBytes(StandardCharsets.UTF_8);
        writeLe64(o, b.length);
        o.write(b);
    }

    private static void writeLe32(OutputStream o, int v) throws IOException {
        for (int i = 0; i < 4; i++) o.write((v >> (8 * i)) & 255);
    }

    private static void writeLe64(OutputStream o, long v) throws IOException {
        for (int i = 0; i < 8; i++) o.write((int) (v >> (8 * i)) & 255);
    }
}
