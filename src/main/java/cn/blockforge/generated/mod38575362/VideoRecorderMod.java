package cn.blockforge.generated.mod38575362;

import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.event.BuildCreativeModeTabContentsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

@Mod(ModConstants.MODID)
public class VideoRecorderMod {
    public VideoRecorderMod() {
        // 客户端配置：config/mod_38575362-client.toml
        ModLoadingContext.get().registerConfig(ModConfig.Type.CLIENT, VideoRecorderConfig.SPEC);

        IEventBus modBus = FMLJavaModLoadingContext.get().getModEventBus();
        ModRegistry.BLOCKS.register(modBus);
        ModRegistry.ITEMS.register(modBus);
        ModRegistry.BLOCK_ENTITIES.register(modBus);
        modBus.addListener(this::addCreativeTabContents);
        ModNetworking.register(modBus);
    }

    private void addCreativeTabContents(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(new ItemStack(ModRegistry.CAMERA.get()));
            event.accept(new ItemStack(ModRegistry.HD_CAMERA.get()));
            event.accept(new ItemStack(ModRegistry.FILM.get()));
        } else if (event.getTabKey() == CreativeModeTabs.REDSTONE_BLOCKS) {
            event.accept(new ItemStack(ModRegistry.FILM_SCREEN.get()));
            event.accept(new ItemStack(ModRegistry.VIDEO_PLAYER.get()));
        }
    }
}
