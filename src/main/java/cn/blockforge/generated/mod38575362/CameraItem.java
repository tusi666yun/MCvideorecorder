package cn.blockforge.generated.mod38575362;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.DistExecutor;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * 摄像机：手持右键开始录制，再右键停止。录像内容写入背包里的空白胶卷。
 * 真正的画面抓取只在客户端进行，服务端这条路径是空操作。
 *
 * <p>一台摄像机绑定一个画质档位（{@link VideoProfile}）：普通的是 192×108 / 8 帧/秒，
 * 高清摄像机是 640×360 / 12 帧/秒，同一卷胶卷都能用。
 */
public class CameraItem extends Item {
    private final VideoProfile profile;

    public CameraItem(Properties properties, VideoProfile profile) {
        super(properties);
        this.profile = profile == null ? VideoProfile.STANDARD : profile;
    }

    public VideoProfile profile() {
        return profile;
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CameraClientProxy.toggle(player, profile));
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, @Nullable Level level, List<Component> tooltip, TooltipFlag flag) {
        if (level != null && level.isClientSide) {
            DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> () -> CameraClientProxy.appendTooltip(tooltip, profile));
        } else {
            tooltip.add(Component.literal("右键开始录制（" + profile.describe() + "），需要空白胶卷").withStyle(ChatFormatting.GRAY));
        }
    }

    @Override
    public boolean isBarVisible(ItemStack stack) {
        Boolean visible = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> CameraClientProxy::isRecording);
        return visible != null && visible;
    }

    @Override
    public int getBarWidth(ItemStack stack) {
        Integer width = DistExecutor.unsafeCallWhenOn(Dist.CLIENT, () -> () -> CameraClientProxy.barWidth(profile));
        return width == null ? 0 : width;
    }

    @Override
    public int getBarColor(ItemStack stack) {
        return 0xFF3B30;
    }
}
