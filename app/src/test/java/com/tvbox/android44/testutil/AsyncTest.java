package com.tvbox.android44.testutil;

import org.robolectric.Shadows;
import android.os.Looper;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.assertTrue;

public final class AsyncTest {
    private AsyncTest() {}

    public static void await(CountDownLatch latch) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (latch.getCount() != 0 && System.nanoTime() < deadline) {
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            latch.await(10, TimeUnit.MILLISECONDS);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("background callback did not complete", latch.getCount() == 0);
    }
}
