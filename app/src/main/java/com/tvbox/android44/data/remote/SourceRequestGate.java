package com.tvbox.android44.data.remote;

import com.tvbox.android44.common.AppConstants;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import okhttp3.Request;

/** One budget for VOD requests across home, search and detail supplements. */
final class SourceRequestGate {
    private static final Semaphore SLOTS = new Semaphore(AppConstants.SEARCH_MAX_CONCURRENT, true);
    private SourceRequestGate() {}

    static String execute(Request request, long timeoutMs, CancelScope scope) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        boolean acquired = false;
        try {
            while (!acquired) {
                if (scope != null && scope.isCancelled()) throw new InterruptedIOException("Cancelled");
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new SocketTimeoutException("source queue timeout");
                acquired = SLOTS.tryAcquire(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)),
                        TimeUnit.NANOSECONDS);
            }
            if (scope != null && scope.isCancelled()) throw new InterruptedIOException("Cancelled");
            long remainingMs = Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            return HttpExecutor.executeForString(HttpClients.withTimeout(remainingMs), request, scope);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new InterruptedIOException("source request interrupted");
        } finally {
            if (acquired) SLOTS.release();
        }
    }
}
