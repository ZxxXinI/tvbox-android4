package com.tvbox.android44.domain.update;

/** Process-local startup checks; manual update checks remain independent. */
public final class StartupUpdatePolicy {
    public static final long INTERVAL_MS = 15 * 60 * 1000L;
    private long sequence;
    private long active;
    private long nextCheckAt;
    private int promptedVersion;

    public synchronized long begin(boolean enabled, String manifestUrl, long now) {
        if (!enabled || manifestUrl == null || manifestUrl.trim().isEmpty()
                || active != 0 || now < nextCheckAt) return 0;
        active = ++sequence;
        return active;
    }

    public synchronized void complete(long token, long now) {
        if (active != token || token == 0) return;
        active = 0;
        nextCheckAt = now + INTERVAL_MS;
    }

    public synchronized void cancel(long token) {
        // Cancellation of an old Activity cannot release a newer request.
        if (active == token) active = 0;
    }

    public synchronized boolean claimPrompt(int versionCode) {
        if (versionCode <= promptedVersion) return false;
        promptedVersion = versionCode;
        return true;
    }
}
