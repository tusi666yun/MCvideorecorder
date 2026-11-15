package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.VideoScreenBlockEntity;
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
 * 显示屏渲染器：按胶卷自己的分辨率建一张动态纹理，逐帧把硬盘上的画面铺上去。
 * 一整个屏幕墙共用同一张纹理，所以一帧只上传一次，高清片段也不会多花显存。
 */
public final class VideoScreenRenderer implements BlockEntityRenderer<VideoScreenBlockEntity> {
    private static final int MAX_TEXTURES = 8;
    // 视频面贴在面板正前方（面板正面在 6.5/16），铺满整个方块面，屏幕之间不留缝。
    private static final float VIDEO_Z = 6.47F / 16.0F;
    private static final Map<String, TextureEntry> TEXTURES = new LinkedHashMap<>(8, 0.75F, true) {
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

    public VideoScreenRenderer(BlockEntityRendererProvider.Context context) {
    }

    @Override
    public void render(VideoScreenBlockEntity screen, float partialTick, PoseStack poseStack,
                       MultiBufferSource buffers, int packedLight, int packedOverlay) {
        if (!screen.hasClip()) {
            return;
        }
        ClipStore.Clip clip = ClipStore.load(screen.getClip());
        if (clip == null || clip.frameCount <= 0) {
            return;
        }

        // 屏幕墙：所有成员共用锚点块的时钟，保证同一时刻显示同一帧。
        screen.refreshWallInfo();
        VideoScreenBlockEntity clock = screen.getClockSource();
        long now = System.currentTimeMillis();
        if (clock.playStartMs == 0L) {
            clock.playStartMs = now;
            clock.uploadedFrame = 0;
        }
        boolean playing = clock.isPlaying();
        if (clock == screen) {
            // 整面墙只由锚点块驱动音频，避免每块屏都开一个音源。
            net.minecraft.core.BlockPos pos = screen.getBlockPos();
            Mp4AudioManager.update(clip, clock.playStartMs, playing,
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D, clip.durationMs);
        }
        int frame;
        float barProgress;
        if (playing) {
            long elapsed = Math.max(0L, now - clock.playStartMs);
            double frameFloat = elapsed * Math.max(1, clock.getFps()) / 1000.0 % clip.frameCount;
            frame = Math.min((int) frameFloat, clip.frameCount - 1);
            clock.uploadedFrame = frame;
            barProgress = clip.frameCount > 1
                    ? (float) Math.min(1.0, frameFloat / (clip.frameCount - 1))
                    : 1.0F;
        } else {
            frame = Math.min(Math.max(0, clock.uploadedFrame), clip.frameCount - 1);
            barProgress = clip.frameCount > 1 ? (float) frame / (clip.frameCount - 1) : 1.0F;
        }
        // 进度按 1/64 格取整：播放时一秒最多重画几次条，不用每一帧都跟着走。
        barProgress = Math.round(barProgress * 64.0F) / 64.0F;
        // 条厚取画面高度的 1/16（约等于方块面的 1/16），再细一档：纹理是整卷共享的，
        // 厚度只跟片段尺寸挂钩，屏幕墙拼多大都只占最底下一线，
        // 也不会因为两块不同尺寸的墙放同一卷胶卷而来回重画。
        int barPx = Math.max(2, Math.min(24, clip.height / 16));

        TextureEntry entry = TEXTURES.get(screen.getClip());
        if (entry == null || entry.width != clip.width || entry.height != clip.height) {
            if (entry != null) {
                Minecraft.getInstance().getTextureManager().release(entry.location);
            }
            ResourceLocation location = new ResourceLocation(ModConstants.MODID, "video/clip_" + textureId++);
            DynamicTexture texture = new DynamicTexture(clip.width, clip.height, false);
            Minecraft.getInstance().getTextureManager().register(location, texture);
            entry = new TextureEntry(location, texture, clip.width, clip.height);
            TEXTURES.put(screen.getClip(), entry);
        }
        boolean barDirty = entry.barProgress != barProgress || entry.barHeightPx != barPx || entry.barPaused != !playing;
        if (entry.uploadedFrame != frame || barDirty) {
            // 取帧失败就继续显示上一帧，等下一格再试，免得黑一下。
            if (ClipStore.showFrame(clip, frame, entry.texture, playing,
                    new ClipStore.BarState(barProgress, barPx, !playing))) {
                entry.uploadedFrame = frame;
                entry.barProgress = barProgress;
                entry.barHeightPx = barPx;
                entry.barPaused = !playing;
            }
        }

        Direction facing = screen.getBlockState().getValue(HorizontalDirectionalBlock.FACING);
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

        // 本块在整面墙里负责画面的哪一块：列从左往右数，行从上往下数。
        int cols = screen.getWallCols();
        int rows = screen.getWallRows();
        int col = screen.getWallColFromLeft();
        int row = screen.getWallRowFromTop();
        float u0 = (float) col / cols;
        float u1 = (float) (col + 1) / cols;
        float v0 = (float) row / rows;
        float v1 = (float) (row + 1) / rows;

        VertexConsumer consumer = buffers.getBuffer(RenderType.entityCutoutNoCull(entry.location));
        Matrix4f matrix = poseStack.last().pose();
        // 局部 +X 是观众的左手边，所以画面左缘（u0）贴在 x=1 一侧。
        addVertex(consumer, matrix, 1.0F, 1.0F, u0, v0, LightTexture.FULL_BRIGHT, packedOverlay);
        addVertex(consumer, matrix, 1.0F, 0.0F, u0, v1, LightTexture.FULL_BRIGHT, packedOverlay);
        addVertex(consumer, matrix, 0.0F, 0.0F, u1, v1, LightTexture.FULL_BRIGHT, packedOverlay);
        addVertex(consumer, matrix, 0.0F, 1.0F, u1, v0, LightTexture.FULL_BRIGHT, packedOverlay);
        poseStack.popPose();
    }

    private static void addVertex(VertexConsumer consumer, Matrix4f matrix, float x, float y,
                                  float u, float v, int light, int overlay) {
        consumer.vertex(matrix, x, y, VIDEO_Z)
                .color(255, 255, 255, 255)
                .uv(u, v)
                .overlayCoords(overlay)
                .uv2(light)
                .normal(0.0F, 0.0F, -1.0F)
                .endVertex();
    }

    private static final class TextureEntry {
        private final ResourceLocation location;
        private final DynamicTexture texture;
        private final int width;
        private final int height;
        private int uploadedFrame = -1;
        // 上一次烤进纹理的进度条状态，用来判断"画面没换但条要挪一挪"。
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
