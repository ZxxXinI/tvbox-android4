package com.tvbox.android44.data.remote;

import com.tvbox.android44.R;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.X509TrustManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = {19, 23, 28})
public class LegacyCertificateTrustTest {
    private InputStream pem() {
        return RuntimeEnvironment.getApplication().getResources().openRawResource(R.raw.isrg_root_x1);
    }

    private X509Certificate root() throws Exception {
        try (InputStream in = pem()) {
            return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(in);
        }
    }

    private X509TrustManager missingRootSystem() {
        return new X509TrustManager() {
            public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("Simulated Android 6 missing root");
            }
            public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                throw new CertificateException("No client certificate trust");
            }
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        };
    }

    @Test public void bundledRootHasVerifiedFingerprintAndLoadsFromAndroidResources() throws Exception {
        try (InputStream in = pem()) {
            X509TrustManager trust = LegacyCertificateTrust.fromRoot(in);
            assertTrue(trust.getAcceptedIssuers().length > 0);
        }
        assertTrue(root().getSubjectX500Principal().getName().contains("ISRG Root X1"));
    }

    @Test public void missingSystemRootCanUseAdditionalRootButUnrelatedCertificatesAreRejected() throws Exception {
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        store.setCertificateEntry("root", root());
        X509TrustManager trust = new LegacyCertificateTrust(missingRootSystem(),
                LegacyCertificateTrust.trustManager(store));
        trust.checkServerTrusted(new X509Certificate[]{root()}, "RSA");
        X509Certificate unrelated = null;
        for (X509Certificate certificate : LegacyCertificateTrust.trustManager(null).getAcceptedIssuers()) {
            if (!certificate.equals(root())) { unrelated = certificate; break; }
        }
        assertNotNull("JVM has an unrelated public root", unrelated);
        try {
            trust.checkServerTrusted(new X509Certificate[]{unrelated}, "RSA");
            fail("An unrelated root must not be trusted by the ISRG-only fallback");
        } catch (CertificateException expected) { }
        try {
            trust.checkClientTrusted(new X509Certificate[]{root()}, "RSA");
            fail("Extra server root must not grant client trust");
        } catch (CertificateException expected) { }
    }

    @Test public void emptyChainAndSubstitutedBundledRootAreRejected() throws Exception {
        try (InputStream in = pem()) {
            try {
                LegacyCertificateTrust.fromRoot(in).checkServerTrusted(new X509Certificate[0], "RSA");
                fail("Empty chain must fail");
            } catch (CertificateException expected) { }
        }
        for (X509Certificate certificate : LegacyCertificateTrust.trustManager(null).getAcceptedIssuers()) {
            if (!certificate.equals(root())) {
                try {
                    LegacyCertificateTrust.fromRoot(new ByteArrayInputStream(certificate.getEncoded()));
                    fail("Only the fingerprint-verified root may be bundled");
                } catch (CertificateException expected) { }
                return;
            }
        }
        fail("Missing test root");
    }
}
