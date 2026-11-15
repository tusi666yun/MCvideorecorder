package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.VideoProfile;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;

import java.util.List;

public final class CameraClient {
    private CameraClient() {
    }

    public static void toggle(Player player, VideoProfile profile) {
        VideoRecorder.toggle(player, profile);
    }

    public static void appendTooltip(List<Component> tooltip, VideoProfile profile) {
        VideoProfile shown = profile == null ? VideoProfile.STANDARD : profile;
        if (VideoRecorder.isRecording()) {
            int seconds = VideoRecorder.framesRecorded() / shown.fps();
            tooltip.add(Component.literal("● 正在录制 " + VideoRecorder.framesRecorded() + " 帧（" + seconds + " 秒）")
                    .withStyle(ChatFormatting.RED));
            tooltip.add(Component.literal("右键停止录制").withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("画质：" + shown.describe()).withStyle(ChatFormatting.AQUA));
            tooltip.add(Component.literal("右键开始录制，时长不限，再右键停止")
                    .withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.literal("需要先拿一卷空白胶卷").withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public static boolean isRecording() {
        return VideoRecorder.isRecording();
    }

    /** 物品栏的红条：前 60 秒逐渐填满，之后保持全满（时长已不限，不再按上限走）。 */
    public static int barWidth(VideoProfile profile) {
        VideoProfile used = profile == null ? VideoProfile.STANDARD : profile;
        int framesPerMinute = Math.max(1, used.fps() * 60);
        return Math.min(13, Math.round(13.0F * VideoRecorder.framesRecorded() / framesPerMinute));
    }
}
