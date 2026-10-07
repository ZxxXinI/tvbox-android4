package com.tvbox.android44.data.remote;

import java.io.InputStream;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

/** Adds the verified ISRG root to legacy devices without bypassing PKIX validation. */
final class LegacyCertificateTrust implements X509TrustManager {
    static final String ROOT_SHA256 =
            "96bcec06264976f37460779acf28c5a7cfe8a3c0aae11a8ffcee05c0bddf08c6";
    private final X509TrustManager system;
    private final X509TrustManager additional;

    LegacyCertificateTrust(X509TrustManager system, X509TrustManager additional) {
        this.system = system;
        this.additional = additional;
    }

    static X509TrustManager fromRoot(InputStream pem) throws GeneralSecurityException, java.io.IOException {
        X509Certificate root = (X509Certificate) CertificateFactory.getInstance("X.509")
                .generateCertificate(pem);
        StringBuilder fingerprint = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(root.getEncoded())) {
            fingerprint.append(String.format(java.util.Locale.US, "%02x", b & 0xff));
        }
        if (!ROOT_SHA256.equals(fingerprint.toString())) {
            throw new CertificateException("Unexpected bundled ISRG root fingerprint");
        }
        root.checkValidity();
        root.verify(root.getPublicKey());
        KeyStore roots = KeyStore.getInstance(KeyStore.getDefaultType());
        roots.load(null, null);
        roots.setCertificateEntry("isrg-root-x1", root);
        return new LegacyCertificateTrust(trustManager(null), trustManager(roots));
    }

    static X509TrustManager trustManager(KeyStore store) throws GeneralSecurityException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(store);
        for (TrustManager manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager) return (X509TrustManager) manager;
        }
        throw new GeneralSecurityException("No X509 trust manager");
    }

    @Override public void checkServerTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        if (chain == null || chain.length == 0) throw new CertificateException("Empty server chain");
        try {
            system.checkServerTrusted(chain, authType);
        } catch (CertificateException systemFailure) {
            // The fallback performs full chain validation against the single public root.
            additional.checkServerTrusted(chain, authType);
        }
    }

    @Override public void checkClientTrusted(X509Certificate[] chain, String authType)
            throws CertificateException {
        system.checkClientTrusted(chain, authType);
    }

    @Override public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] first = system.getAcceptedIssuers();
        X509Certificate[] second = additional.getAcceptedIssuers();
        X509Certificate[] all = new X509Certificate[first.length + second.length];
        System.arraycopy(first, 0, all, 0, first.length);
        System.arraycopy(second, 0, all, first.length, second.length);
        return all;
    }
}
