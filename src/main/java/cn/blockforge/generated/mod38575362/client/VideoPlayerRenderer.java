package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.VideoPlayerBlockEntity;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import org.joml.Matrix4f;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 录像记录器的渲染器：把选中的本地录像按帧铺在方块正面的小屏幕区域上，
 * 顺带在画面底部烤一条进度条（和显示屏一样的做法，纹理变了才重画）。
 */
public final class VideoPlayerRenderer implements BlockEntityRenderer<VideoPlayerBlockEntity> {
    private static final int MAX_TEXTURES = 4;
    // 正面屏幕窗口（对应 video_player_front 贴图里那块黑玻璃）：x 2..14，y 3..13。
    private static final float SCREEN_X0 = 2.0F / 16.0F;
    private static final float SCREEN_X1 = 14.0F / 16.0F;
    private static final float SCREEN_Y0 = 3.0F / 16.0F;
    private static final float SCREEN_Y1 = 13.0F / 16.0F;
    private static final float SCREEN_Z = -0.01F;
    private static final Map<String, TextureEntry> TEXTURES = new LinkedHashMap<>(4, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, TextureEntry> eldest) {
            if (size() <= MAX_TEXTURES) {
                return false;
            }
            Minecraft.getInstance().getTextureManager().release(eldest.getValue().location);
            return true;
        }
    };
    private static int textureId;

    public VideoPlayerRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoPlayerBlockEntity deck, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!deck.hasClip()) {
            return;
        }
        ClipStore.Clip clip = ClipStore.load(deck.getClip());
        if (clip == null || clip.frameCount <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        if (deck.playStartMs == 0L) {
            deck.playStartMs = now;
            deck.uploadedFrame = 0;
        }
        boolean playing = deck.isPlaying();
        net.minecraft.core.BlockPos pos = deck.getBlockPos();
        Mp4AudioManager.update(clip, deck.playStartMs, playing,
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, clip.durationMs);
        int frame;
        float barProgress;
        if (playing) {
            long elapsed = Math.max(0L, now - deck.playStartMs);
            double frameFloat = elapsed * Math.max(1, deck.getFps()) / 1000.0 % clip.frameCount;
            frame = Math.min((int) frameFloat, clip.frameCount - 1);
            deck.uploadedFrame = frame;
            barProgress = clip.frameCount > 1
                    ? (float) Math.min(1.0, frameFloat / (clip.frameCount - 1))
                    : 1.0F;
        } else {
            frame = Math.min(Math.max(0, deck.uploadedFrame), clip.frameCount - 1);
            barProgress = clip.frameCount > 1 ? (float) frame / (clip.frameCount - 1) : 1.0F;
        }
        barProgress = Math.round(barProgress * 64.0F) / 64.0F;
        // 进度条薄薄一线：小屏幕经不起粗条。
        int barPx = Math.max(2, clip.height / 20);

        TextureEntry entry = TEXTURES.get(deck.getClip());
        if (entry == null || entry.width != clip.width || entry.height != clip.height) {
            if (entry != null) {
                Minecraft.getInstance().getTextureManager().release(entry.location);
            }
            ResourceLocation location = new ResourceLocation(ModConstants.MODID, "video/deck_" + textureId++);
            DynamicTexture texture = new DynamicTexture(clip.width, clip.height, false);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            entry = new TextureEntry(location, texture, clip.width, clip.height);
            TEXTURES.put(deck.getClip(), entry);
        }
        boolean barDirty = entry.barProgress != barProgress || entry.barHeightPx != barPx || entry.barPaused != !playing;
        if (entry.uploadedFrame != frame || barDirty) {
            if (ClipStore.showFrame(clip, frame, entry.texture, playing,
                    new ClipStore.BarState(barProgress, barPx, !playing))) {
                entry.uploadedFrame = frame;
                entry.barProgress = barProgress;
                entry.barHeightPx = barPx;
                entry.barPaused = !playing;
            }
        }

        Direction facing = deck.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        float jsonRotation = switch (facing) {
            case NORTH -> 0.0F;
            case EAST -> 90.0F;
            case SOUTH -> 180.0F;
            case WEST -> 270.0F;
            default -> 0.0F;
        };
        poseStack.pushPose();
        poseStack.translate(0.5D, 0.5D, 0.5D);
        poseStack.mulPose(com.mojang.math.Axis.YP.rotationDegrees(-jsonRotation));
        poseStack.translate(-0.5D, -0.5D, -0.5D);

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(entry.location));
        Matrix4f matrix = poseStack.last().pose();
        // 和显示屏同理：局部 +X 是观众的左手边，画面左缘贴 x 大的一侧。
        addVertex(consumer, matrix, SCREEN_X1, SCREEN_Y1, 0.0F, 0.0F, packedOverlay);
        addVertex(consumer, matrix, SCREEN_X1, SCREEN_Y0, 0.0F, 1.0F, packedOverlay);
        addVertex(consumer, matrix, SCREEN_X0, SCREEN_Y0, 1.0F, 1.0F, packedOverlay);
        addVertex(consumer, matrix, SCREEN_X0, SCREEN_Y1, 1.0F, 0.0F, packedOverlay);
        poseStack.popPose();
    }

    private static void addVertex(VertexConsumer consumer, Matrix4f matrix, float x, float y,
                                  float u, float v, int overlay) {
        consumer.vertex(matrix, x, y, SCREEN_Z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(LightTexture.FULL_BRIGHT)
                .normal(0.0F, 0.0F, -1.0F)
                .endVertex();
    }

    private static final class TextureEntry {
        private final ResourceLocation location;
        private final DynamicTexture texture;
        private final int width;
        private final int height;
        private int uploadedFrame = -1;
        private float barProgress = -1.0F;
        private int barHeightPx = -1;
        private boolean barPaused;

        private TextureEntry(ResourceLocation location, DynamicTexture texture, int width, int height) {
            this.location = location;
            this.texture = texture;
            this.width = width;
            this.height = height;
        }
    }
}
