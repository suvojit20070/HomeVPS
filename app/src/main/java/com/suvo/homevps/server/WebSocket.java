package com.suvo.homevps.server;

import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Minimal RFC 6455 WebSocket implementation (text frames, ping/pong, close).
 * Enough for the live terminal stream; no extensions or compression.
 */
public class WebSocket {

    private static final String MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    public static final int OP_TEXT = 0x1;
    public static final int OP_BINARY = 0x2;
    public static final int OP_CLOSE = 0x8;
    public static final int OP_PING = 0x9;
    public static final int OP_PONG = 0xA;

    public static class Frame {
        public final int opcode;
        public final byte[] payload;

        Frame(int opcode, byte[] payload) {
            this.opcode = opcode;
            this.payload = payload;
        }

        public String text() {
            return new String(payload, StandardCharsets.UTF_8);
        }
    }

    private final InputStream in;
    private final OutputStream out;
    private volatile boolean closed = false;

    public WebSocket(InputStream in, OutputStream out) {
        this.in = in;
        this.out = out;
    }

    public static String acceptKey(String clientKey) throws Exception {
        MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
        byte[] hash = sha1.digest((clientKey.trim() + MAGIC).getBytes(StandardCharsets.UTF_8));
        return Base64.encodeToString(hash, Base64.NO_WRAP);
    }

    public static void writeHandshake(OutputStream out, String clientKey) throws Exception {
        String resp = "HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + acceptKey(clientKey) + "\r\n\r\n";
        out.write(resp.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    public synchronized void sendText(String text) throws IOException {
        sendFrame(OP_TEXT, text.getBytes(StandardCharsets.UTF_8));
    }

    public synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        if (closed) throw new IOException("closed");
        int len = payload.length;
        out.write(0x80 | opcode);
        if (len < 126) {
            out.write(len);
        } else if (len <= 0xFFFF) {
            out.write(126);
            out.write((len >> 8) & 0xFF);
            out.write(len & 0xFF);
        } else {
            out.write(127);
            for (int i = 7; i >= 0; i--) {
                out.write((int) (((long) len >> (8 * i)) & 0xFF));
            }
        }
        out.write(payload);
        out.flush();
    }

    /** Blocks until a full frame arrives. Returns null on EOF. */
    public Frame readFrame() throws IOException {
        int b0 = in.read();
        if (b0 < 0) return null;
        int b1 = in.read();
        if (b1 < 0) return null;

        int opcode = b0 & 0x0F;
        boolean masked = (b1 & 0x80) != 0;
        long len = b1 & 0x7F;
        if (len == 126) {
            len = ((long) readByte() << 8) | readByte();
        } else if (len == 127) {
            len = 0;
            for (int i = 0; i < 8; i++) {
                len = (len << 8) | readByte();
            }
        }
        if (len > 4 * 1024 * 1024) throw new IOException("frame too large");

        byte[] mask = new byte[4];
        if (masked) readFully(mask);

        byte[] payload = new byte[(int) len];
        readFully(payload);
        if (masked) {
            for (int i = 0; i < payload.length; i++) {
                payload[i] ^= mask[i % 4];
            }
        }
        return new Frame(opcode, payload);
    }

    private int readByte() throws IOException {
        int b = in.read();
        if (b < 0) throw new IOException("eof");
        return b;
    }

    private void readFully(byte[] buf) throws IOException {
        int off = 0;
        while (off < buf.length) {
            int n = in.read(buf, off, buf.length - off);
            if (n < 0) throw new IOException("eof");
            off += n;
        }
    }

    public void close() {
        if (closed) return;
        try {
            sendFrame(OP_CLOSE, new byte[0]);
        } catch (IOException ignored) {
        }
        closed = true;
    }

    public boolean isClosed() {
        return closed;
    }
}
