package net.toshimichi.recorder;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * On-disk layout, all big-endian:
 * <pre>
 * int   magic "MCRC"
 * int   version (2)
 * long  recording start, epoch millis
 * long  recording player's UUID, most significant bits   (version 2 and up)
 * long  recording player's UUID, least significant bits
 * UTF   recording player's name
 * int   property count, then per property: UTF name, UTF value, bool signed, [UTF signature]   (version 2 and up)
 * then until EOF:
 *   int   time since start, millis
 *   byte  direction: 0 clientbound, 1 serverbound, 2 synthetic clientbound (world snapshot taken at start),
 *                    3 input event
 *   int   length
 *   bytes packet exactly as it crossed the wire after decryption and decompression: varint id + payload;
 *         for an input event: byte device (0 keyboard, 1 mouse), int LWJGL key or button code, byte pressed
 * </pre>
 */
public final class RecordingFile {

    public static final int MAGIC = 0x4D435243;
    public static final int VERSION = 2;
    public static final byte CLIENTBOUND = 0;
    public static final byte SERVERBOUND = 1;
    public static final byte SNAPSHOT = 2;
    public static final byte INPUT = 3;
    public static final byte KEYBOARD = 0;
    public static final byte MOUSE = 1;

    public static final class Entry {

        public final int time;
        public final byte direction;
        public final byte[] data;

        public Entry(int time, byte direction, byte[] data) {
            this.time = time;
            this.direction = direction;
            this.data = data;
        }
    }

    public static final class Contents {

        public final GameProfile profile; // null for a version 1 file
        public final List<Entry> entries;

        public Contents(GameProfile profile, List<Entry> entries) {
            this.profile = profile;
            this.entries = entries;
        }
    }

    private RecordingFile() {
    }

    public static void writeHeader(DataOutput out, long startMillis, GameProfile profile) throws IOException {
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeLong(startMillis);
        out.writeLong(profile.getId().getMostSignificantBits());
        out.writeLong(profile.getId().getLeastSignificantBits());
        out.writeUTF(profile.getName());
        // the textures property is the skin and cape, signed by Mojang
        out.writeInt(profile.getProperties().size());
        for (Property property : profile.getProperties().values()) {
            out.writeUTF(property.getName());
            out.writeUTF(property.getValue());
            out.writeBoolean(property.hasSignature());
            if (property.hasSignature()) out.writeUTF(property.getSignature());
        }
    }

    public static void writeEntry(DataOutput out, int time, byte direction, byte[] data) throws IOException {
        out.writeInt(time);
        out.writeByte(direction);
        out.writeInt(data.length);
        out.write(data);
    }

    // ponytail: whole file in memory; stream from disk if recordings outgrow the heap
    public static Contents readAll(File file) throws IOException {
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (in.readInt() != MAGIC) throw new IOException("Not a recording: " + file.getName());
            int version = in.readInt();
            in.readLong();
            GameProfile profile = version >= 2 ? new GameProfile(new UUID(in.readLong(), in.readLong()), in.readUTF()) : null;
            for (int i = version >= 2 ? in.readInt() : 0; i > 0; i--) {
                String name = in.readUTF();
                String value = in.readUTF();
                profile.getProperties().put(name, new Property(name, value, in.readBoolean() ? in.readUTF() : null));
            }

            List<Entry> entries = new ArrayList<>();
            try {
                while (true) {
                    int time = in.readInt();
                    byte direction = in.readByte();
                    byte[] data = new byte[in.readInt()];
                    in.readFully(data);
                    entries.add(new Entry(time, direction, data));
                }
            } catch (EOFException e) {
                // a crash mid-write leaves a partial tail; everything before it is still good
                return new Contents(profile, entries);
            }
        }
    }
}
