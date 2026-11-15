package cn.blockforge.generated.mod38575362;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

import java.util.List;

public class FilmItem extends Item {
    public FilmItem(Properties properties) {
        super(properties);
    }

    public static boolean hasClip(ItemStack stack) {
        return stack.hasTag() && !stack.getOrCreateTag().getString(ModConstants.TAG_CLIP).isEmpty();
    }

    public static String getClip(ItemStack stack) {
        return stack.getOrCreateTag().getString(ModConstants.TAG_CLIP);
    }

    /** 这一卷是用哪一档画质录的；老胶卷没记这个字段，按标清处理。 */
    public static VideoProfile getProfile(ItemStack stack) {
        return VideoProfile.byId(stack.getOrCreateTag().getString(ModConstants.TAG_PROFILE));
    }

    public static int getFrames(ItemStack stack) {
        return stack.getOrCreateTag().getInt(ModConstants.TAG_FRAMES);
    }

    public static int getFps(ItemStack stack) {
        int fps = stack.getOrCreateTag().getInt(ModConstants.TAG_FPS);
        return fps <= 0 ? getProfile(stack).fps() : fps;
    }

    /** 客户端录完后由服务端调用，把片段编号和画质一起写进胶卷。 */
    public static void writeClip(ItemStack stack, String clip, VideoProfile profile, int frames, int fps, long durationMs) {
        stack.getOrCreateTag().putString(ModConstants.TAG_CLIP, clip);
        stack.getOrCreateTag().putString(ModConstants.TAG_PROFILE, profile.id());
        stack.getOrCreateTag().putInt(ModConstants.TAG_FRAMES, frames);
        stack.getOrCreateTag().putInt(ModConstants.TAG_FPS, fps);
        stack.getOrCreateTag().putLong(ModConstants.TAG_DURATION, durationMs);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (hasClip(stack)) {
            VideoProfile profile = getProfile(stack);
            tooltip.add(Component.literal("已录制视频：" + profile.resolution() + " · " + getFrames(stack) + " 帧")
                    .withStyle(profile == VideoProfile.HIGH_DEFINITION ? ChatFormatting.AQUA : ChatFormatting.LIGHT_PURPLE));
            tooltip.add(Component.literal("画质：" + profile.label() + "（" + profile.fps() + " 帧/秒）")
                    .withStyle(ChatFormatting.GRAY));
        } else {
            tooltip.add(Component.literal("空白胶卷：拿着摄像机右键开始录制").withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        return hasClip(stack);
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        return 13;
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return hasClip(stack) && getProfile(stack) == VideoProfile.HIGH_DEFINITION ? 0x18C8FF : 0xAA44FF;
    }
}
