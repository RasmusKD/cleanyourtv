package io.github.rasmuskd.remotedaemon;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;

/**
 * Just enough of the ADB wire protocol to run one shell command on the TV's own adbd: connect, authenticate with
 * the app's key, open "shell:", read until the command closes.
 */
final class Adb {
    static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
    private static final int VERSION = 0x01000000, MAX_DATA = 4096;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;

    static final class Message {
        final int command, arg0, arg1;
        final byte[] data;

        Message(int command, int arg0, int arg1, byte[] data) {
            this.command = command;
            this.arg0 = arg0;
            this.arg1 = arg1;
            this.data = data;
        }
    }

    static byte[] header(int command, int arg0, int arg1, byte[] data) {
        int sum = 0;
        for (byte b : data) sum += b & 0xff;
        return ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).array();
    }

    private final Socket socket;
    private final DataInputStream in;
    private final OutputStream out;

    private Adb(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new DataInputStream(socket.getInputStream());
        this.out = socket.getOutputStream();
    }

    private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
        out.write(header(command, arg0, arg1, data));
        out.write(data);
        out.flush();
    }

    private Message read() throws IOException {
        byte[] head = new byte[24];
        in.readFully(head);
        ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
        int command = b.getInt(), arg0 = b.getInt(), arg1 = b.getInt(), length = b.getInt();
        if (length < 0 || length > 1 << 20) throw new IOException("bad ADB message length " + length);
        byte[] data = new byte[length];
        in.readFully(data);
        return new Message(command, arg0, arg1, data);
    }

    /**
     * Runs command through "shell:" on host:port and returns what it printed. A key adbd does not know yet is
     * offered for the TV's "Allow debugging?" prompt, which is waited on for up to authWaitMs.
     */
    static String shell(String host, int port, AdbKey key, String command, int authWaitMs)
            throws IOException, GeneralSecurityException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 3000);
            socket.setSoTimeout(10000);
            Adb adb = new Adb(socket);
            adb.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false;
            while (true) {
                Message m = adb.read();
                if (m.command == CNXN) break;
                if (m.command != AUTH || m.arg0 != AUTH_TOKEN) throw new IOException("unexpected ADB reply");
                if (!signed) {
                    adb.send(AUTH, AUTH_SIGNATURE, 0, key.sign(m.data));
                    signed = true;
                } else {
                    // the signature was refused: this key is new to the TV, so ask
                    adb.send(AUTH, AUTH_RSAPUBLICKEY, 0, key.publicKeyLine("remote-daemon@tv"));
                    socket.setSoTimeout(authWaitMs);
                }
            }
            socket.setSoTimeout(30000);
            int local = 1;
            adb.send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            while (true) {
                Message m = adb.read();
                if (m.command == WRTE) {
                    output.write(m.data);
                    adb.send(OKAY, local, m.arg0, new byte[0]);
                } else if (m.command == CLSE) {
                    return output.toString("UTF-8");
                } else if (m.command != OKAY) {
                    throw new IOException("unexpected ADB message " + Integer.toHexString(m.command));
                }
            }
        }
    }
}
