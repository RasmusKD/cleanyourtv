package io.github.rasmuskd.huekeys;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** A real TLS server with a throwaway self-signed certificate (bridge-test.p12, made with keytool for this test). */
public class PinTest {
    private ServerSocket server;
    private String pin;

    @Before public void serve() throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = getClass().getResourceAsStream("/bridge-test.p12")) {
            store.load(in, "testtest".toCharArray());
        }
        Certificate cert = store.getCertificate("bridge");
        pin = Pin.sha256(cert.getEncoded());
        KeyManagerFactory keys = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keys.init(store, "testtest".toCharArray());
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(keys.getKeyManagers(), null, null);
        server = tls.getServerSocketFactory().createServerSocket(0, 4, InetAddress.getLoopbackAddress());
        Thread serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    for (String line; (line = in.readLine()) != null && !line.isEmpty(); ) {
                        // the request head; the body-less GET ends at the blank line
                    }
                    OutputStream out = s.getOutputStream();
                    out.write(("HTTP/1.1 200 OK\r\nContent-Length: 11\r\nConnection: close\r\n\r\n{\"ok\":true}")
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                } catch (IOException handshakeOrClose) {
                    // a refused handshake, or the test ended
                }
            }
        });
        serving.setDaemon(true);
        serving.start();
    }

    @After public void stop() throws IOException {
        server.close();
    }

    private String url() {
        return "https://127.0.0.1:" + server.getLocalPort() + "/api/k/sensors";
    }

    @Test public void thePinnedCertificateIsTrusted() throws Exception {
        assertEquals("{\"ok\":true}", HueKeyActivity.request(new Pin(pin), "GET", url(), null));
    }

    @Test public void anyOtherCertificateIsRefused() {
        String other = (pin.charAt(0) == '0' ? "1" : "0") + pin.substring(1);
        try {
            HueKeyActivity.request(new Pin(other), "GET", url(), null);
            fail("sent the request to a server with another certificate");
        } catch (IOException expected) {
            // the handshake failed before the key was sent
        }
    }

    @Test public void aConfigIsWholeOrNothing() {
        String whole = "{\"bridge\":\"10.0.0.2\",\"pin\":\"" + pin.toUpperCase() + "\",\"key\":\"k\",\"room\":\"1\"}";
        Config c = Config.parse(whole);
        assertNotNull(c);
        assertTrue(c.complete());
        assertEquals(pin, c.pin);
        assertTrue(c.url("/x").startsWith("https://10.0.0.2/"));
        // a new address without its key and certificate would send the stored key elsewhere
        assertNull(Config.parse("{\"bridge\":\"attacker.example\"}"));
        assertNull(Config.parse("{\"bridge\":\"10.0.0.2\",\"key\":\"k\",\"room\":\"1\"}"));
        assertNull(Config.parse("{\"bridge\":\"10.0.0.2\",\"pin\":\"abc\",\"key\":\"k\",\"room\":\"1\"}"));
        assertNull(Config.parse("not json"));
        assertFalse(Pin.valid(""));
    }
}
