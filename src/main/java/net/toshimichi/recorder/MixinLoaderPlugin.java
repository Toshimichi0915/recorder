package net.toshimichi.recorder;

import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.mixin.MixinEnvironment;
import org.spongepowered.asm.mixin.Mixins;

import java.util.Map;

@IFMLLoadingPlugin.Name("RecorderMixinLoader")
@IFMLLoadingPlugin.MCVersion("1.8.9")
public class MixinLoaderPlugin implements IFMLLoadingPlugin {

    // singular addConfiguration: Essential relaunches under Mixin 0.8.7, which dropped addConfigurations(String...)
    public MixinLoaderPlugin() {
        MixinBootstrap.init();
        Mixins.addConfiguration("mixins.recorder.json");
        MixinEnvironment.getDefaultEnvironment().setObfuscationContext("searge");
    }

    @Override
    public String[] getASMTransformerClass() {
        return new String[0];
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
