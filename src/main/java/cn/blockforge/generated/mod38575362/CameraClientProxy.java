package cn.blockforge.generated.mod38575362;

import cn.blockforge.generated.mod38575362.client.CameraClient;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

/**
 * 服务端安全的转发层：只在这里引用客户端的 CameraClient，
 * 调用点全部包在 DistExecutor 的 lambda 里，服务端不会加载客户端类。
 */
final class CameraClientProxy {
    private CameraClientProxy() {
    }

    static void toggle(Player player, VideoProfile profile) {
        CameraClient.toggle(player, profile);
    }

    static void appendTooltip(List<Component> tooltip, VideoProfile profile) {
        CameraClient.appendTooltip(tooltip, profile);
    }

    static boolean isRecording() {
        return CameraClient.isRecording();
    }

    static int barWidth(VideoProfile profile) {
        return CameraClient.barWidth(profile);
    }
}
