package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ModConstants;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;
import net.minecraftforge.client.event.RenderGuiEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;

@Mod.EventBusSubscriber(modid = ModConstants.MODID, value = Dist.CLIENT)
public final class ClientForgeEvents {
    private static boolean kWasDown;

    private ClientForgeEvents() {
    }

    @SubscribeEvent
    public static void onRenderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            VideoRecorder.onRenderTick(Minecraft.getInstance());
        }
    }

    @SubscribeEvent
    public static void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) {
            return;
        }
        ClipUploader.tick();
        ClipFetcher.tick();
        ClipFileFetcher.tick();
        GameAudioRecorder.tick();
        Mp4AudioManager.tick();
        ClipRegistry.requestList();

        // 编码中按 K 取消
        Minecraft mc = Minecraft.getInstance();
        long window = mc.getWindow().getWindow();
        boolean kDown = InputConstants.isKeyDown(window, GLFW.GLFW_KEY_K);
        if (EncodeSession.isBusy() && kDown && !kWasDown) {
            EncodeSession.requestCancel();
            if (mc.player != null) {
                mc.player.displayClientMessage(Component.literal("已请求取消编码…"), true);
            }
        }
        kWasDown = kDown;
    }

    @SubscribeEvent
    public static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        if (EncodeSession.isBusy()) {
            EncodeSession.requestCancel();
        }
        ClipUploader.cancel();
        ClipFetcher.clear();
        ClipFileFetcher.clear();
        GameAudioRecorder.stop();
        Mp4AudioManager.stopAll();
        ClipRegistry.clear();
    }

    @SubscribeEvent
    public static void onRenderGui(RenderGuiEvent.Post event) {
        Minecraft minecraft = Minecraft.getInstance();
        GuiGraphics graphics = event.getGuiGraphics();
        int screenW = event.getWindow().getGuiScaledWidth();

        if (VideoRecorder.isRecording()) {
            int x = screenW - 74;
            int y = 8;
            graphics.fill(x, y, x + 8, y + 8, 0xFFFF3B30);
            graphics.drawString(minecraft.font, "REC " + VideoRecorder.framesRecorded(), x + 12, y, 0xFFFFB0B0);
        }

        if (EncodeSession.isBusy()) {
            String line = EncodeSession.statusLine();
            int w = minecraft.font.width(line) + 8;
            int x = Math.max(4, screenW - w - 4);
            int y = VideoRecorder.isRecording() ? 22 : 8;
            graphics.fill(x - 2, y - 2, x + w, y + 12, 0xAA000000);
            // 进度条
            int barW = Math.min(120, w);
            int filled = (int) (barW * EncodeSession.progress());
            graphics.fill(x - 2, y + 12, x - 2 + barW, y + 15, 0xFF333333);
            graphics.fill(x - 2, y + 12, x - 2 + filled, y + 15, 0xFF3CB371);
            graphics.drawString(minecraft.font, line, x, y, 0xFFE0FFE0);
        }
    }
}
