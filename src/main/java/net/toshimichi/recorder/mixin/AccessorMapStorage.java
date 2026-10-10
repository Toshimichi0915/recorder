package net.toshimichi.recorder.mixin;

import net.minecraft.world.WorldSavedData;
import net.minecraft.world.storage.MapStorage;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.Map;

// the client's SaveDataMemoryStorage keeps every map the server sent here, keyed "map_<id>"
@Mixin(MapStorage.class)
public interface AccessorMapStorage {

    @Accessor("loadedDataMap")
    Map<String, WorldSavedData> getLoadedDataMap();
}
