package com.botmaker.shared.ipc;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;

/**
 * Length-prefixed binary framing for {@link TelemetryEvent}s — dependency-free (no JSON/Jackson), keeping
 * {@code botmaker-shared} JNA-only. Wire layout of one frame:
 *
 * <pre>
 *   int32  payloadLength (big-endian, via DataOutputStream)
 *   byte   protocolVersion
 *   byte   typeTag  (1=Match, 2=Click, 3=Region)
 *   ...    type-specific fields, encoded field-by-field
 * </pre>
 *
 * The 1-byte version lets shared/sdk/studio evolve independently: a reader rejects a frame whose version it
 * does not understand rather than misreading it.
 */
public final class TelemetryFrame {

    /** Bumped when the on-wire encoding changes incompatibly. v2 added a trailing {@code line} per event. */
    public static final int PROTOCOL_VERSION = 2;

    /** Guards a decoder against absurd length prefixes (a stray/misaligned stream). */
    static final int MAX_FRAME_BYTES = 1 << 20;

    private static final int TYPE_MATCH = 1;
    private static final int TYPE_CLICK = 2;
    private static final int TYPE_REGION = 3;

    private TelemetryFrame() {}

    /** Encodes and writes one framed event, then flushes. */
    public static void write(DataOutputStream out, TelemetryEvent event) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream p = new DataOutputStream(buffer);
        p.writeByte(PROTOCOL_VERSION);
        switch (event) {
            case TelemetryEvent.Match m -> {
                p.writeByte(TYPE_MATCH);
                writeTarget(p, m.target());
                writeNullableRect(p, m.region());
                writeNullableRect(p, m.rect());
                p.writeDouble(m.confidence());
                p.writeBoolean(m.found());
                p.writeInt(m.line());
            }
            case TelemetryEvent.Click c -> {
                p.writeByte(TYPE_CLICK);
                writeTarget(p, c.target());
                p.writeInt(c.x());
                p.writeInt(c.y());
                p.writeInt(c.button());
                p.writeInt(c.line());
            }
            case TelemetryEvent.Region r -> {
                p.writeByte(TYPE_REGION);
                writeTarget(p, r.target());
                writeNullableRect(p, r.rect());
                p.writeInt(r.line());
            }
        }
        byte[] payload = buffer.toByteArray();
        out.writeInt(payload.length);
        out.write(payload);
        out.flush();
    }

    /** Reads and decodes one framed event. Throws {@link EOFException} at a clean stream end. */
    public static TelemetryEvent read(DataInputStream in) throws IOException {
        int length = in.readInt();
        if (length < 0 || length > MAX_FRAME_BYTES) {
            throw new IOException("Bad telemetry frame length: " + length);
        }
        byte[] payload = in.readNBytes(length);
        if (payload.length < length) throw new EOFException("Truncated telemetry frame");

        DataInputStream p = new DataInputStream(new ByteArrayInputStream(payload));
        int version = p.readUnsignedByte();
        if (version != PROTOCOL_VERSION) {
            throw new IOException("Unsupported telemetry protocol version: " + version);
        }
        int type = p.readUnsignedByte();
        return switch (type) {
            case TYPE_MATCH -> new TelemetryEvent.Match(
                    readTarget(p), readNullableRect(p), readNullableRect(p), p.readDouble(), p.readBoolean(), p.readInt());
            case TYPE_CLICK -> new TelemetryEvent.Click(
                    readTarget(p), p.readInt(), p.readInt(), p.readInt(), p.readInt());
            case TYPE_REGION -> new TelemetryEvent.Region(
                    readTarget(p), readNullableRect(p), p.readInt());
            default -> throw new IOException("Unknown telemetry type tag: " + type);
        };
    }

    private static void writeTarget(DataOutputStream p, TelemetryEvent.Target t) throws IOException {
        writeNullableString(p, t.title());
        p.writeInt(t.x());
        p.writeInt(t.y());
        p.writeInt(t.width());
        p.writeInt(t.height());
    }

    private static TelemetryEvent.Target readTarget(DataInputStream p) throws IOException {
        return new TelemetryEvent.Target(
                readNullableString(p), p.readInt(), p.readInt(), p.readInt(), p.readInt());
    }

    private static void writeNullableRect(DataOutputStream p, TelemetryEvent.Rect r) throws IOException {
        if (r == null) {
            p.writeBoolean(false);
            return;
        }
        p.writeBoolean(true);
        p.writeInt(r.x());
        p.writeInt(r.y());
        p.writeInt(r.width());
        p.writeInt(r.height());
    }

    private static TelemetryEvent.Rect readNullableRect(DataInputStream p) throws IOException {
        if (!p.readBoolean()) return null;
        return new TelemetryEvent.Rect(p.readInt(), p.readInt(), p.readInt(), p.readInt());
    }

    private static void writeNullableString(DataOutputStream p, String s) throws IOException {
        if (s == null) {
            p.writeBoolean(false);
            return;
        }
        p.writeBoolean(true);
        p.writeUTF(s);
    }

    private static String readNullableString(DataInputStream p) throws IOException {
        if (!p.readBoolean()) return null;
        return p.readUTF();
    }
}
