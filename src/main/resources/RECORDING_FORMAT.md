# Recorder `.mcrc` file format

Written by the Recorder mod for Minecraft 1.8.9 (protocol 47). Files live in
`<.minecraft>/recordings/<yyyy-MM-dd_HH-mm-ss>.mcrc`, one per recording. Everything is
big-endian. The reference implementation is `net/toshimichi/recorder/RecordingFile.java`
in this jar.

## Layout

```
Header
  int32   magic        0x4D435243  ("MCRC")
  int32   version      2
  int64   start        recording start, Unix epoch milliseconds
  int64   uuid_msb     recording player's UUID, most significant bits    (version 2 and up)
  int64   uuid_lsb     recording player's UUID, least significant bits   (version 2 and up)
  uint16  name_length  byte count of `name`                              (version 2 and up)
  bytes   name         recording player's name, Java modified UTF-8      (version 2 and up)
  int32   count        number of profile properties                      (version 2 and up)
  Property             `count` times                                     (version 2 and up)

Property
  string  name         e.g. "textures"
  string  value        base64 JSON, as Mojang's session server returns it
  uint8   signed       1 if a signature follows, 0 if not
  string  signature    base64 Yggdrasil signature                        (only if `signed` is 1)

string = uint16 byte count, then that many bytes of Java modified UTF-8

Entries, repeated until end of file
  int32   time         milliseconds since `start`
  uint8   direction    see below
  int32   length       byte count of `data`
  bytes   data         `length` bytes
```

The player fields identify the account that made the recording, which may differ from
the account that later plays it back. Version 1 files stop the header after `start` and
carry no player; a replayer falls back to the viewer's own account. Minecraft names and
property values are ASCII, where modified UTF-8 is plain UTF-8.

The `textures` property is the recording player's skin and cape, fetched from Mojang's
session server when recording starts. It is there because the server's tab list entry for
the player may lack it (offline mode). A replayer should only fall back to it then: a skin
the server did list, such as one set by a nick plugin, is what other players saw. The count may be
0 if the session server could not be reached.

A recording that ended in a crash may stop in the middle of an entry. Drop the partial
tail; everything before it is intact.

## Direction values

| value | meaning | `data` contents |
|------:|---------|-----------------|
| 0 | clientbound | raw packet from server to client |
| 1 | serverbound | raw packet from client to server |
| 2 | snapshot | synthetic clientbound packet, see below |
| 3 | input | keyboard or mouse button event, see below |

## Packet data (direction 0, 1, 2)

The bytes are the packet exactly as it crossed the wire after decryption and
decompression, with the length prefix removed:

```
varint  packet id    (protocol 47 id within the PLAY state, for the given direction)
bytes   packet body  (vanilla 1.8.9 encoding for that packet)
```

Recording starts while already in the PLAY state, so no HANDSHAKE, STATUS or LOGIN
packets ever appear and the state never changes. Packet ids follow the vanilla protocol 47
tables, e.g. clientbound 0x01 Join Game, 0x08 Player Position And Look, 0x21 Chunk Data;
serverbound 0x03..0x06 player movement, 0x0A Animation, 0x0B Entity Action. Forge mod
channels, if any, arrive as 0x3F / 0x17 Plugin Message packets like any other.

Entries are in the order the client processed them. Timestamps come from the client's
wall clock when the packet passed the codec, not from any server clock.

## Snapshot entries (direction 2)

Recording can start in the middle of a session, so the recorder first writes a synthetic
set of clientbound packets describing the world as the client knew it at that instant:
Join Game, server brand, spawn position, the tab list with its header and footer, time,
one Chunk Data per loaded chunk, sign and tile entity data, map contents, weather, world
border, scoreboard, the player's inventory, health and experience, Player Position And
Look, then a spawn for every other entity in view, an Entity Teleport to the server's exact position for it (none for paintings), its equipment, head yaw, bed, attributes,
potion effects and riding or leash links. Right after Player Position And Look comes an Entity
Metadata for the player's own entity id holding only index 10, the skin layers (hat, jacket,
sleeves, trousers, cape) the client had switched on, and index 17, the absorption (golden hearts). They all carry `time` 0 and are encoded exactly like real
packets so a replayer can feed them unchanged.

They were never sent by the server. Analysis of server behaviour should skip direction 2.
Fields the client did not know are filled with placeholders, e.g. max players is 20.

## Input entries (direction 3)

Keyboard and mouse button transitions made while in the world (not while a GUI screen was
open). Mouse motion and scroll are not recorded.

```
uint8   device    0 keyboard, 1 mouse
int32   code      LWJGL 2 key code (keyboard) or button index (mouse: 0 left, 1 right, 2 middle)
uint8   pressed   1 pressed, 0 released
```

Key codes are LWJGL 2 `Keyboard.KEY_*` values (W = 17, A = 30, S = 31, D = 32, Space = 57,
Left Shift = 42, Left Control = 29, Escape = 1). They share the same `time` base as packets,
so a key press can be matched against the movement packets it caused.

## Minimal reader (Python)

```python
import struct
import uuid

def read_mcrc(path):
    with open(path, "rb") as f:
        magic, version, start = struct.unpack(">iiq", f.read(16))
        assert magic == 0x4D435243 and version in (1, 2)
        if version >= 2:
            player = uuid.UUID(bytes=f.read(16))
            name = f.read(struct.unpack(">H", f.read(2))[0]).decode("utf-8")
            print("recorded by", name, player)
        for _ in range(struct.unpack(">i", f.read(4))[0] if version >= 2 else 0):
            prop = [f.read(struct.unpack(">H", f.read(2))[0]).decode("utf-8") for _ in range(2)]
            if f.read(1)[0]:
                f.read(struct.unpack(">H", f.read(2))[0])   # signature
            print("property", prop[0], len(prop[1]), "chars")
        while True:
            head = f.read(9)
            if len(head) < 9:
                return                      # clean end, or truncated tail
            time, direction, length = struct.unpack(">iBi", head)
            data = f.read(length)
            if len(data) < length:
                return
            yield start, time, direction, data

def read_varint(data):
    value, shift = 0, 0
    for i, b in enumerate(data):
        value |= (b & 0x7F) << shift
        if not b & 0x80:
            return value, data[i + 1:]
        shift += 7

for start, t, direction, data in read_mcrc("recording.mcrc"):
    if direction in (0, 1):
        packet_id, body = read_varint(data)
        print(t, "S->C" if direction == 0 else "C->S", hex(packet_id), len(body))
    elif direction == 3:
        device, code, pressed = struct.unpack(">BiB", data)
        print(t, "key" if device == 0 else "mouse", code, "down" if pressed else "up")
```
