# Recorder

A Forge mod for Minecraft 1.8.9 that records the raw packets a server sends you, then replays them later or turns them into a single player world.

**[Download the latest build](https://github.com/Toshimichi0915/recorder/releases/latest/download/recorder.jar)** and drop it into `.minecraft/mods`.

## Usage

- **Record:** in-game, open the pause menu and press **Start Recording**. It stops on **Stop Recording** or when you disconnect. Files go to `.minecraft/recordings/<date>.mcrc`.
- **Replay:** on the main menu, press **R** (left of Multiplayer), pick a recording and press **Replay**. The pause menu then has Pause/Resume, a seek slider and a game mode switch.
- **Convert to World:** in the same screen, saves every chunk the recording loaded into a new single player world.

The file format is documented in [`RECORDING_FORMAT.md`](src/main/resources/RECORDING_FORMAT.md).

## Building

```sh
./gradlew build
```

The mod jar is written to `build/libs/recorder.jar`. Drop it into `.minecraft/mods`.

## License

[MIT](LICENSE)
