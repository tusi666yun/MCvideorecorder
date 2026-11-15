package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.ModRegistry;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.EntityRenderersEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;

@Mod.EventBusSubscriber(modid = ModConstants.MODID, bus = Mod.EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class ClientModEvents {
    private ClientModEvents() {
    }

    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerBlockEntityRenderer(ModRegistry.FILM_SCREEN_ENTITY.get(), VideoScreenRenderer::new);
        event.registerBlockEntityRenderer(ModRegistry.VIDEO_PLAYER_ENTITY.get(), VideoPlayerRenderer::new);
    }
}
