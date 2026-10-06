package com.tvbox.android44.domain.update;

import org.junit.Test;
import static org.junit.Assert.*;

public class StartupUpdatePolicyTest {
    @Test public void disabledAndEmptyManifestDoNotReserveCheck() {
        StartupUpdatePolicy policy = new StartupUpdatePolicy();
        assertEquals(0, policy.begin(false, "https://example.com/update.json", 100));
        assertEquals(0, policy.begin(true, " ", 100)); assertEquals(0, policy.begin(true, null, 100));
        assertTrue(policy.begin(true, "https://example.com/update.json", 100) > 0);
    }
    @Test public void inFlightAndRecentChecksAreDeduplicated() {
        StartupUpdatePolicy policy = new StartupUpdatePolicy(); long token = policy.begin(true, "url", 100);
        assertEquals(0, policy.begin(true, "url", 101)); policy.complete(token, 200);
        assertEquals(0, policy.begin(true, "url", 201)); assertTrue(policy.begin(true, "url", 200 + StartupUpdatePolicy.INTERVAL_MS) > 0);
    }
    @Test public void oldActivityCancellationCannotReleaseNewRequest() {
        StartupUpdatePolicy policy = new StartupUpdatePolicy(); long first = policy.begin(true, "url", 100); policy.cancel(first);
        long second = policy.begin(true, "url", 101); assertNotEquals(first, second);
        policy.cancel(first); policy.complete(first, 10000); assertEquals(0, policy.begin(true, "url", 102));
        policy.complete(second, 200); assertEquals(0, policy.begin(true, "url", 201));
    }
    @Test public void eachNewVersionCanPromptOnceAcrossRecreation() {
        StartupUpdatePolicy policy = new StartupUpdatePolicy(); assertTrue(policy.claimPrompt(2)); assertFalse(policy.claimPrompt(2));
        assertFalse(policy.claimPrompt(1)); assertTrue(policy.claimPrompt(3));
    }
}
