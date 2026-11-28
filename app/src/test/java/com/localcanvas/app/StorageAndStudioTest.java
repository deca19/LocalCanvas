package com.localcanvas.app;

import android.graphics.Bitmap;
import org.junit.Test;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/** Pure-JVM unit tests for AtomicModelFile cleanup, GenerationQueue controls, and ModelLeaseManager. */
public class StorageAndStudioTest {
    @Test
    public void cleanupRemovesOnlyStaleTempArtifacts() throws Exception {
        File dir = Files.createTempDirectory("lc-clean").toFile();
        File valid = new File(dir, "sd15.model");
        File staleImport = new File(dir, "sd15.model.import.part");
        File staleBak = new File(dir, "sd15.model.bak");
        Files.write(valid.toPath(), new byte[]{1, 2, 3});
        Files.write(staleImport.toPath(), new byte[]{4});
        Files.write(staleBak.toPath(), new byte[]{5});

        int removed = AtomicModelFile.cleanupStaleArtifacts(dir);
        assertEquals(2, removed);
        assertTrue(valid.exists());
        assertFalse(staleImport.exists());
        assertFalse(staleBak.exists());
    }

    @Test
    public void queuePauseReorderAndCancelWork() throws Exception {
        GenerationQueue q = new GenerationQueue();
        q.setPaused(true);
        assertTrue(q.isPaused());

        GenerationQueue.Listener noop = new GenerationQueue.Listener() {
            public void started(int i, int t) {}
            public void completed(Bitmap b, int i) {}
            public void failed(String m, int i) {}
        };
        GenerationQueue.Job j1 = new GenerationQueue.Job(new File("/missing/1.model"), "p1", "", 64, 64, 1, 1, 1, 1, "euler", "auto");
        GenerationQueue.Job j2 = new GenerationQueue.Job(new File("/missing/2.model"), "p2", "", 64, 64, 1, 1, 2, 1, "euler", "auto");
        q.submit(j1, noop);
        q.submit(j2, noop);
        assertEquals(2, q.pendingCount());

        assertTrue(q.moveUp(j2.id));
        assertEquals(j2.id, q.queuedIds().get(0));
        assertTrue(q.cancelJob(j1.id));
        assertEquals(1, q.pendingCount());
        assertEquals(GenerationQueue.State.CANCELLED, q.state(j1.id));

        CountDownLatch done = new CountDownLatch(1);
        q.submit(new GenerationQueue.Job(new File("/missing/3.model"), "p3", "", 64, 64, 1, 1, 3, 1, "euler", "auto"),
                new GenerationQueue.Listener() {
                    public void started(int i, int t) {}
                    public void completed(Bitmap b, int i) { done.countDown(); }
                    public void failed(String m, int i) { done.countDown(); }
                });
        q.setPaused(false);
        assertTrue(done.await(3, TimeUnit.SECONDS));
        q.shutdown();
    }
}
