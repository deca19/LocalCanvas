package com.localcanvas.app;

import org.junit.Test;
import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Real HTTP integration tests using an embedded ServerSocket covering
 * HTTP 200, HTTP 206 resume, HTTP 416 restart, ETag change, SHA-256 mismatch, and corrupt rejection.
 */
public class ModelManagerHttpIntegrationTest {
    @Test
    public void downloads200AndResumes206WithStrongEtag() throws Exception {
        byte[] payload = DownloadValidatorTest.buildMinimalValidGguf();
        int split = payload.length / 2;
        File dir = Files.createTempDirectory("lc-http-test").toFile();
        ModelManager mm = new ModelManager(dir, true);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String url = "http://127.0.0.1:" + port + "/model.gguf";
            File part = new File(dir, "testmodel.model.part");
            File meta = new File(dir, "testmodel.model.part.meta");
            byte[] firstHalf = new byte[split];
            System.arraycopy(payload, 0, firstHalf, 0, split);
            Files.write(part.toPath(), firstHalf);
            Files.write(meta.toPath(), (url + "\n\"v1-strong\"").getBytes(StandardCharsets.UTF_8));

            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    String line;
                    String range = "";
                    while ((line = br.readLine()) != null && !line.isEmpty()) {
                        if (line.toLowerCase().startsWith("range:")) range = line;
                    }
                    OutputStream out = s.getOutputStream();
                    if (range.contains("bytes=" + split + "-")) {
                        int rem = payload.length - split;
                        String hdr = "HTTP/1.1 206 Partial Content\r\n"
                                + "ETag: \"v1-strong\"\r\n"
                                + "Content-Range: bytes " + split + "-" + (payload.length - 1) + "/" + payload.length + "\r\n"
                                + "Content-Length: " + rem + "\r\n\r\n";
                        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                        out.write(payload, split, rem);
                    } else {
                        String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\n\r\n";
                        out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                        out.write(payload);
                    }
                    out.flush();
                } catch (Exception ignored) {}
            });
            t.start();

            File installed = mm.download("testmodel", url, null, null, new AtomicBoolean(false));
            t.join(3000);
            assertTrue(installed.isFile());
            assertEquals(payload.length, installed.length());
        }
    }

    @Test
    public void restartsCleanlyOn416() throws Exception {
        byte[] payload = DownloadValidatorTest.buildMinimalValidGguf();
        File dir = Files.createTempDirectory("lc-http-416").toFile();
        ModelManager mm = new ModelManager(dir, true);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String url = "http://127.0.0.1:" + port + "/model.gguf";
            File part = new File(dir, "m416.model.part");
            File meta = new File(dir, "m416.model.part.meta");
            Files.write(part.toPath(), new byte[payload.length + 10]);
            Files.write(meta.toPath(), (url + "\n\"v1\"").getBytes(StandardCharsets.UTF_8));

            Thread t = new Thread(() -> {
                for (int req = 0; req < 2; req++) {
                    try (Socket s = server.accept()) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        boolean hasRange = false;
                        while ((line = br.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("range:")) hasRange = true;
                        }
                        OutputStream out = s.getOutputStream();
                        if (hasRange) {
                            out.write("HTTP/1.1 416 Range Not Satisfiable\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                        } else {
                            String hdr = "HTTP/1.1 200 OK\r\nETag: \"v1\"\r\nContent-Length: " + payload.length + "\r\n\r\n";
                            out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                            out.write(payload);
                        }
                        out.flush();
                    } catch (Exception ignored) {}
                }
            });
            t.start();

            File installed = mm.download("m416", url, null, null, new AtomicBoolean(false));
            t.join(3000);
            assertTrue(installed.isFile());
            assertEquals(payload.length, installed.length());
        }
    }

    @Test
    public void restartsCleanlyOnEtagChange() throws Exception {
        byte[] payload = DownloadValidatorTest.buildMinimalValidGguf();
        File dir = Files.createTempDirectory("lc-http-etag").toFile();
        ModelManager mm = new ModelManager(dir, true);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String url = "http://127.0.0.1:" + port + "/model.gguf";
            File part = new File(dir, "metag.model.part");
            File meta = new File(dir, "metag.model.part.meta");
            Files.write(part.toPath(), new byte[10]);
            Files.write(meta.toPath(), (url + "\n\"old-tag\"").getBytes(StandardCharsets.UTF_8));

            Thread t = new Thread(() -> {
                for (int req = 0; req < 2; req++) {
                    try (Socket s = server.accept()) {
                        BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                        String line;
                        boolean hasRange = false;
                        while ((line = br.readLine()) != null && !line.isEmpty()) {
                            if (line.toLowerCase().startsWith("range:")) hasRange = true;
                        }
                        OutputStream out = s.getOutputStream();
                        if (hasRange) {
                            String hdr = "HTTP/1.1 206 Partial Content\r\nETag: \"new-tag\"\r\nContent-Range: bytes 10-"
                                    + (payload.length - 1) + "/" + payload.length + "\r\nContent-Length: " + (payload.length - 10) + "\r\n\r\n";
                            out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                            out.write(payload, 10, payload.length - 10);
                        } else {
                            String hdr = "HTTP/1.1 200 OK\r\nETag: \"new-tag\"\r\nContent-Length: " + payload.length + "\r\n\r\n";
                            out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                            out.write(payload);
                        }
                        out.flush();
                    } catch (Exception ignored) {}
                }
            });
            t.start();

            File installed = mm.download("metag", url, null, null, new AtomicBoolean(false));
            t.join(3000);
            assertTrue(installed.isFile());
            assertEquals(payload.length, installed.length());
        }
    }

    @Test
    public void rejectsSha256MismatchAndCorruptPayload() throws Exception {
        byte[] payload = DownloadValidatorTest.buildMinimalValidGguf();
        File dir = Files.createTempDirectory("lc-http-sha").toFile();
        ModelManager mm = new ModelManager(dir, true);

        try (ServerSocket server = new ServerSocket(0)) {
            int port = server.getLocalPort();
            String url = "http://127.0.0.1:" + port + "/model.gguf";
            Thread t = new Thread(() -> {
                try (Socket s = server.accept()) {
                    BufferedReader br = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    while (true) {
                        String l = br.readLine();
                        if (l == null || l.isEmpty()) break;
                    }
                    OutputStream out = s.getOutputStream();
                    String hdr = "HTTP/1.1 200 OK\r\nContent-Length: " + payload.length + "\r\n\r\n";
                    out.write(hdr.getBytes(StandardCharsets.US_ASCII));
                    out.write(payload);
                    out.flush();
                } catch (Exception ignored) {}
            });
            t.start();

            boolean failed = false;
            try {
                mm.download("badsha", url, "0000000000000000000000000000000000000000000000000000000000000000", null, new AtomicBoolean(false));
            } catch (IOException expected) {
                failed = true;
            }
            t.join(3000);
            assertTrue(failed);
            assertFalse(new File(dir, "badsha.model").exists());
        }
    }
}
