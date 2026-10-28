package io.github.rasmuskd.remotedaemon;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;
import java.util.Base64;

import javax.crypto.Cipher;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class AdbTest {
    @Rule public TemporaryFolder dir = new TemporaryFolder();

    @Test public void headerCarriesChecksumAndMagic() {
        ByteBuffer h = ByteBuffer.wrap(Adb.header(Adb.CNXN, 1, 2, new byte[]{1, 2, (byte) 0xff}))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(Adb.CNXN, h.getInt());
        assertEquals(1, h.getInt());
        assertEquals(2, h.getInt());
        assertEquals(3, h.getInt());
        assertEquals(258, h.getInt());
        assertEquals(~Adb.CNXN, h.getInt());
    }

    @Test public void theKeyIsKeptAcrossLoads() throws Exception {
        AdbKey a = AdbKey.loadOrCreate(dir.getRoot()), b = AdbKey.loadOrCreate(dir.getRoot());
        assertArrayEquals(a.pair.getPublic().getEncoded(), b.pair.getPublic().getEncoded());
    }

    @Test public void signatureIsPkcs1OverSha1DigestInfo() throws Exception {
        AdbKey key = AdbKey.loadOrCreate(dir.getRoot());
        byte[] token = new byte[20];
        Arrays.fill(token, (byte) 7);
        Cipher rsa = Cipher.getInstance("RSA/ECB/PKCS1Padding");
        rsa.init(Cipher.DECRYPT_MODE, key.pair.getPublic());
        byte[] signed = rsa.doFinal(key.sign(token));
        assertEquals(35, signed.length);
        assertArrayEquals(token, Arrays.copyOfRange(signed, 15, 35));
    }

    /** A peer that answers every reply with a new token gets one signature and one key offer, then a closed door. */
    @Test public void aPeerSendingTokensForeverGetsOneSignature() throws Exception {
        AdbKey key = AdbKey.loadOrCreate(dir.getRoot());
        int[] signatures = {0}, offers = {0};
        try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread peer = new Thread(() -> {
                try (Socket s = server.accept()) {
                    DataInputStream in = new DataInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    for (int i = 0; i < 10; i++) {
                        byte[] head = new byte[24];
                        in.readFully(head);
                        ByteBuffer h = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
                        int command = h.getInt(), arg0 = h.getInt();
                        h.getInt();
                        in.readFully(new byte[h.getInt()]);
                        if (command == Adb.AUTH && arg0 == 2) signatures[0]++;
                        if (command == Adb.AUTH && arg0 == 3) offers[0]++;
                        byte[] token = new byte[20];
                        out.write(Adb.header(Adb.AUTH, 1, 0, token));
                        out.write(token);
                        out.flush();
                    }
                } catch (IOException closed) {
                    // the client gave up, which is the point
                }
            });
            peer.start();
            try {
                Adb.shell("127.0.0.1", server.getLocalPort(), key, "true", 2000);
                fail("authenticated against a peer that never accepts");
            } catch (IOException expected) {
                // refused the third token
            }
            peer.join(5000);
        }
        assertEquals(1, signatures[0]);
        assertEquals(1, offers[0]);
    }

    @Test public void publicKeyStructMatchesAdbd() throws Exception {
        AdbKey key = AdbKey.loadOrCreate(dir.getRoot());
        RSAPublicKey pub = (RSAPublicKey) key.pair.getPublic();
        String line = new String(key.publicKeyLine("x@tv"), StandardCharsets.UTF_8);
        assertTrue(line.endsWith(" x@tv\0"));
        ByteBuffer s = ByteBuffer.wrap(Base64.getDecoder().decode(line.substring(0, line.indexOf(' '))))
                .order(ByteOrder.LITTLE_ENDIAN);
        assertEquals(524, s.capacity());
        assertEquals(64, s.getInt());
        long n0inv = s.getInt() & 0xffffffffL;
        byte[] n = new byte[256];
        s.get(n);
        byte[] be = new byte[257];
        for (int i = 0; i < 256; i++) be[256 - i] = n[i];
        BigInteger modulus = new BigInteger(be);
        assertEquals(pub.getModulus(), modulus);
        // n0inv * n == -1 (mod 2^32)
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        assertEquals(r32.subtract(BigInteger.ONE), BigInteger.valueOf(n0inv).multiply(modulus).mod(r32));
        s.position(4 + 4 + 256 + 256);
        assertEquals(65537, s.getInt());
    }
}
