package net.toshimichi.recorder;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class RecordingFileTest {

    @Test
    public void roundTripDropsTruncatedTail() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        GameProfile profile = new GameProfile(UUID.randomUUID(), "Steve");
        profile.getProperties().put("textures", new Property("textures", "skin", "signature"));
        profile.getProperties().put("unsigned", new Property("unsigned", "value"));
        RecordingFile.writeHeader(out, 1234L, profile);
        RecordingFile.writeEntry(out, 0, RecordingFile.SNAPSHOT, new byte[]{1});
        RecordingFile.writeEntry(out, 50, RecordingFile.CLIENTBOUND, new byte[]{2, 3});
        RecordingFile.writeEntry(out, 70, RecordingFile.SERVERBOUND, new byte[0]);
        out.writeInt(99); // a crash mid-entry

        File file = File.createTempFile("recording", ".mcrc");
        file.deleteOnExit();
        Files.write(file.toPath(), bytes.toByteArray());

        RecordingFile.Contents contents = RecordingFile.readAll(file);
        assertEquals(profile.getId(), contents.profile.getId());
        assertEquals("Steve", contents.profile.getName());
        assertEquals("signature", contents.profile.getProperties().get("textures").iterator().next().getSignature());
        assertEquals(null, contents.profile.getProperties().get("unsigned").iterator().next().getSignature());
        List<RecordingFile.Entry> entries = contents.entries;
        assertEquals(3, entries.size());
        assertEquals(50, entries.get(1).time);
        assertEquals(RecordingFile.CLIENTBOUND, entries.get(1).direction);
        assertArrayEquals(new byte[]{2, 3}, entries.get(1).data);
        assertEquals(0, entries.get(2).data.length);
    }
}
