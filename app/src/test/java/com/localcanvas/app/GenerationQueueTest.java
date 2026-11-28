package com.localcanvas.app;

import android.graphics.Bitmap;
import org.junit.Test;
import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class GenerationQueueTest {
    @Test
    public void queuedJobsAreConsumedOneAtATimeAndBothFinish() throws Exception {
        GenerationQueue q = new GenerationQueue();
        CountDownLatch done = new CountDownLatch(2);
        AtomicInteger callbacks = new AtomicInteger();
        GenerationQueue.Listener listener = new GenerationQueue.Listener() {
            public void started(int i, int t) {}
            public void completed(Bitmap b, int i) { callbacks.incrementAndGet(); done.countDown(); }
            public void failed(String m, int i) { callbacks.incrementAndGet(); done.countDown(); }
        };
        q.submit(new GenerationQueue.Job(new File("/missing/a.model"), "a", "", 64, 64, 1, 1, 1, 1, "euler", "auto"), listener);
        q.submit(new GenerationQueue.Job(new File("/missing/b.model"), "b", "", 64, 64, 1, 1, 2, 1, "euler", "auto"), listener);
        assertTrue(done.await(3, TimeUnit.SECONDS));
        assertEquals(2, callbacks.get());
        q.shutdown();
    }

    @Test
    public void modelLeasePreventsWriteWhileReadHeld() {
        String path = "/tmp/lease-test-" + System.nanoTime() + ".model";
        assertTrue(ModelLeaseManager.shared().tryAcquireRead(path));
        assertFalse(ModelLeaseManager.shared().tryAcquireWrite(path));
        ModelLeaseManager.shared().releaseRead(path);
        assertTrue(ModelLeaseManager.shared().tryAcquireWrite(path));
        ModelLeaseManager.shared().releaseWrite(path);
    }
}
