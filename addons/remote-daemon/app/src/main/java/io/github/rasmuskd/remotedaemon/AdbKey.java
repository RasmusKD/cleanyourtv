package io.github.rasmuskd.remotedaemon;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The app's own ADB key: a 2048-bit RSA pair kept in its private files, the public half in the form adbd stores
 * in adb_keys, and the token signature the ADB handshake asks for.
 */
final class AdbKey {
    private static final int MODULUS_WORDS = 2048 / 32;
    /** DER prefix of a SHA-1 DigestInfo: adbd verifies the token as if it were a SHA-1 digest. */
    private static final byte[] SHA1_DIGEST_INFO = {
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14};

    final KeyPair pair;

    private AdbKey(KeyPair pair) {
        this.pair = pair;
    }

    static AdbKey loadOrCreate(File dir) throws IOException, GeneralSecurityException {
        File priv = new File(dir, "adbkey"), pub = new File(dir, "adbkey.pub");
        KeyFactory rsa = KeyFactory.getInstance("RSA");
        if (priv.isFile() && pub.isFile()) {
            return new AdbKey(new KeyPair(
                    rsa.generatePublic(new X509EncodedKeySpec(Files.readAllBytes(pub.toPath()))),
                    rsa.generatePrivate(new PKCS8EncodedKeySpec(Files.readAllBytes(priv.toPath())))));
        }
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        Files.write(priv.toPath(), pair.getPrivate().getEncoded());
        Files.write(pub.toPath(), pair.getPublic().getEncoded());
        return new AdbKey(pair);
    }

    /** PKCS#1 v1.5 signature of the 20-byte token, as adbd checks it. */
    byte[] sign(byte[] token) throws GeneralSecurityException {
        Signature sig = Signature.getInstance("NONEwithRSA");
        sig.initSign(pair.getPrivate());
        sig.update(SHA1_DIGEST_INFO);
        sig.update(token);
        return sig.sign();
    }

    /** The adb_keys line: base64 of adbd's RSAPublicKey struct, a space and a name, NUL-terminated. */
    byte[] publicKeyLine(String name) {
        String line = Base64.getEncoder().encodeToString(encode((RSAPublicKey) pair.getPublic())) + " " + name + "\0";
        return line.getBytes(StandardCharsets.UTF_8);
    }

    /** struct { u32 words; u32 n0inv; u8 n[256]; u8 rr[256]; u32 e; }, every number little-endian. */
    static byte[] encode(RSAPublicKey key) {
        BigInteger n = key.getModulus();
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = n.mod(r32).modInverse(r32).negate().mod(r32);
        BigInteger rr = BigInteger.ONE.shiftLeft(2048 * 2).mod(n);
        ByteBuffer out = ByteBuffer.allocate(4 + 4 + 256 + 256 + 4).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(MODULUS_WORDS);
        out.putInt(n0inv.intValue());
        out.put(littleEndian(n, 256));
        out.put(littleEndian(rr, 256));
        out.putInt(key.getPublicExponent().intValue());
        return out.array();
    }

    static byte[] littleEndian(BigInteger value, int size) {
        byte[] big = value.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream(size);
        for (int i = big.length - 1; i >= 0 && out.size() < size; i--) out.write(big[i]);
        while (out.size() < size) out.write(0);
        return out.toByteArray();
    }
}
