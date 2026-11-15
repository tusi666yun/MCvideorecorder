package cn.blockforge.generated.mod38575362;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 录像记录器的存储：只记「正在放哪一段录像」和一个播放时钟。
 * 录像本体（JPG 帧）留在各人自己的电脑上，本机没有的片段会向服务器按需补帧。
 *
 * <p>多人一起看时播放起点由服务端给出（{@link #startMs} 是服务端的墙上时间），
 * 暂停时服务端记下停在哪一帧，所有客户端都按这两样算帧——
 * 不然每台机器各起各的表，几个人看到的画面会各放各的。
 */
public class VideoPlayerBlockEntity extends BlockEntity {
    private String clip = "";
    private int frames;
    private int fps = VideoProfile.STANDARD.fps();
    private boolean playing = true;
    /** 服务端给的播放起点（Unix 毫秒）。0 表示这份数据里没有，客户端自己起表。 */
    private long startMs;
    /** 服务端暂停那一刻停在哪一帧，-1 表示没停。 */
    private int pausedFrame = -1;

    // 客户端渲染器用的播放时钟；不进存档，由服务端的 startMs 同步过来。
    public long playStartMs;
    public int uploadedFrame = -1;

    public VideoPlayerBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.VIDEO_PLAYER_ENTITY.get(), pos, state);
    }

    public String getClip() {
        return clip;
    }

    public int getFrames() {
        return frames;
    }

    public int getFps() {
        return fps;
    }

    public boolean hasClip() {
        return !clip.isEmpty() && frames > 0;
    }

    public boolean isPlaying() {
        return playing;
    }

    public void insertClip(String newClip, int newFrames, int newFps) {
        if (newClip != null && !newClip.isEmpty() && !ClipWire.isSafeClipName(newClip)) {
            return;
        }
        clip = newClip == null ? "" : newClip;
        frames = Math.max(0, newFrames);
        fps = Math.max(1, newFps);
        playing = true;
        startMs = System.currentTimeMillis();
        pausedFrame = -1;
        playStartMs = startMs;
        uploadedFrame = -1;
        syncToClients();
    }

    public void clearClip() {
        clip = "";
        frames = 0;
        startMs = 0L;
        pausedFrame = -1;
        playStartMs = 0L;
        uploadedFrame = -1;
        syncToClients();
    }

    public void setPlaying(boolean value) {
        if (value == playing) {
            return;
        }
        playing = value;
        long now = System.currentTimeMillis();
        if (value) {
            // 继续播放：把起点往前挪到暂停那一帧，画面就接着那一帧往下走。
            int keep = pausedFrame >= 0 ? pausedFrame : frameAt(now);
            startMs = now - (long) Math.max(0, keep) * 1000L / Math.max(1, fps);
            pausedFrame = -1;
        } else {
            pausedFrame = frameAt(now);
        }
        syncToClients();
    }

    /** 按服务端的时钟算此刻应该是第几帧（循环播放，所以取模）。 */
    private int frameAt(long now) {
        if (frames <= 0 || startMs <= 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, now - startMs);
        return (int) (elapsed * Math.max(1, fps) / 1000L % frames);
    }

    private void syncToClients() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag) {
        super.saveAdditional(tag);
        tag.putString(ModConstants.TAG_CLIP, clip);
        tag.putInt(ModConstants.TAG_FRAMES, frames);
        tag.putInt(ModConstants.TAG_FPS, fps);
        tag.putBoolean("vr_playing", playing);
        tag.putLong("vr_start_ms", startMs);
        tag.putInt("vr_paused_frame", pausedFrame);
    }

    @Override
    public void load(CompoundTag tag) {
        super.load(tag);
        clip = tag.getString(ModConstants.TAG_CLIP);
        frames = tag.getInt(ModConstants.TAG_FRAMES);
        fps = Math.max(1, tag.getInt(ModConstants.TAG_FPS));
        playing = !tag.contains("vr_playing") || tag.getBoolean("vr_playing");
        startMs = tag.getLong("vr_start_ms");
        pausedFrame = tag.contains("vr_paused_frame") ? tag.getInt("vr_paused_frame") : -1;
        // 客户端这一侧：有服务端给的起点就用它，所有人数的帧号才对得上。
        playStartMs = startMs > 0L ? startMs : 0L;
        uploadedFrame = pausedFrame >= 0 ? Math.min(pausedFrame, Math.max(0, frames - 1)) : -1;
    }

    @Override
    public CompoundTag getUpdateTag() {
        return saveWithoutMetadata();
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
