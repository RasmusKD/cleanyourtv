package io.github.rasmuskd.huekeys;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;

import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

/**
 * HTTPS to the bridge, trusting exactly one certificate: the one whose SHA-256 install.sh recorded. The bridge's
 * certificate is self-signed for its bridge id, not for its address, so the pin stands in for both the CA check
 * and the host name check; any other certificate is refused.
 */
final class Pin implements X509TrustManager {
    private final String sha256;

    Pin(String sha256) {
        this.sha256 = sha256;
    }

    static boolean valid(String pin) {
        return pin.matches("[0-9a-f]{64}");
    }

    static String sha256(byte[] der) throws GeneralSecurityException {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(der)) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            if (chain == null || chain.length == 0 || !sha256(chain[0].getEncoded()).equals(sha256)) {
                throw new CertificateException("not the bridge's certificate");
            }
        } catch (GeneralSecurityException e) {
            throw new CertificateException(e);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        throw new CertificateException("client certificates are not used");
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return new X509Certificate[0];
    }

    SSLSocketFactory socketFactory() throws GeneralSecurityException {
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(null, new TrustManager[] {this}, null);
        return tls.getSocketFactory();
    }

    /** The pinned certificate already proved the peer; its name is the bridge id, never the address dialled. */
    static final HostnameVerifier PINNED = (host, session) -> true;
}
