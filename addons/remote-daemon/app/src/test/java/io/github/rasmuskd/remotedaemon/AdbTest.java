package io.github.rasmuskd.remotedaemon;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.math.BigInteger;
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
