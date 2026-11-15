package cn.blockforge.generated.mod38575362;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class VideoScreenBlockEntity extends BlockEntity {
    /** 一面屏幕墙最多联动的方块数，超出部分按独立屏幕处理。 */
    public static final int MAX_WALL = 144;
    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };

    private String clip = "";
    private int frames;
    private int fps = VideoProfile.STANDARD.fps();
    private boolean playing = true;
    /** 服务端给的播放起点（Unix 毫秒）。0 表示这份数据里没有，客户端自己起表。 */
    private long startMs;
    /** 服务端暂停那一刻停在哪一帧，-1 表示没停。 */
    private int pausedFrame = -1;

    // 客户端渲染器使用的播放时钟；不写入存档。整面墙统一读锚点块的这份数据。
    public long playStartMs;
    public int uploadedFrame = -1;

    // 客户端缓存的屏幕墙几何信息；不写入存档。
    private boolean wallCacheValid;
    private long wallCacheGameTime;
    private int wallCols = 1;
    private int wallRows = 1;
    private int wallColFromLeft;
    private int wallRowFromTop;
    private BlockPos wallAnchorPos;

    public VideoScreenBlockEntity(BlockPos pos, BlockState state) {
        super(ModRegistry.FILM_SCREEN_ENTITY.get(), pos, state);
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
        insertClip(newClip, newFrames, newFps, System.currentTimeMillis());
    }

    /**
     * 装片。整面屏幕墙要用同一个起点时间，否则拼在一起的几块屏各起各的表，
     * 一幅画面会被切成几段错位显示。
     */
    public void insertClip(String newClip, int newFrames, int newFps, long wallStartMs) {
        if (newClip != null && !newClip.isEmpty() && !ClipWire.isSafeClipName(newClip)) {
            return;
        }
        clip = newClip == null ? "" : newClip;
        frames = Math.max(0, newFrames);
        fps = Math.max(1, newFps);
        playing = true;
        startMs = wallStartMs > 0L ? wallStartMs : System.currentTimeMillis();
        pausedFrame = -1;
        playStartMs = startMs;
        uploadedFrame = -1;
        wallCacheValid = false;
        syncToClients();
    }

    public void clearClip() {
        clip = "";
        frames = 0;
        startMs = 0L;
        pausedFrame = -1;
        playStartMs = 0L;
        uploadedFrame = -1;
        wallCacheValid = false;
        syncToClients();
    }

    public void setPlaying(boolean value) {
        if (value == playing) {
            return;
        }
        playing = value;
        long now = System.currentTimeMillis();
        if (value) {
            // 继续播放：把起点往前挪到暂停那一帧，画面接着那一帧走。
            int keep = pausedFrame >= 0 ? pausedFrame : frameAt(now);
            startMs = now - (long) Math.max(0, keep) * 1000L / Math.max(1, fps);
            pausedFrame = -1;
        } else {
            pausedFrame = frameAt(now);
        }
        syncToClients();
    }

    /** 此刻服务端认为应该放到第几帧（循环播放，所以取模）。 */
    private int frameAt(long now) {
        if (frames <= 0 || startMs <= 0L) {
            return 0;
        }
        long elapsed = Math.max(0L, now - startMs);
        return (int) (elapsed * Math.max(1, fps) / 1000L % frames);
    }

    /** 服务端记下的播放起点，墙上的其他块共用这一个值。 */
    public long getStartMs() {
        return startMs;
    }

    /** 观众面对屏幕时的“左”方向：视频左半边要贴到这一侧的屏幕上。 */
    public static Direction viewerLeftOf(Direction facing) {
        return switch (facing) {
            case NORTH -> Direction.EAST;
            case EAST -> Direction.SOUTH;
            case SOUTH -> Direction.WEST;
            case WEST -> Direction.NORTH;
            default -> Direction.EAST;
        };
    }

    /** 重算本块所属的屏幕墙（相邻 + 同朝向 + 同一卷胶卷），客户端每 20 游戏刻刷新一次。 */
    public void refreshWallInfo() {
        Level level = getLevel();
        if (level == null || !level.isClientSide) {
            return;
        }
        long gameTime = level.getGameTime();
        if (wallCacheValid && gameTime - wallCacheGameTime < 20L) {
            return;
        }
        wallCacheValid = true;
        wallCacheGameTime = gameTime;
        wallCols = 1;
        wallRows = 1;
        wallColFromLeft = 0;
        wallRowFromTop = 0;
        wallAnchorPos = worldPosition;
        if (clip.isEmpty()) {
            return;
        }

        Direction facing = getBlockState().getValue(HorizontalDirectionalBlock.FACING);
        List<BlockPos> members = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(worldPosition);
        queue.add(worldPosition);
        while (!queue.isEmpty() && members.size() < MAX_WALL) {
            BlockPos current = queue.poll();
            members.add(current);
            for (Direction side : SIDES) {
                BlockPos next = current.relative(side);
                if (!visited.add(next)) {
                    continue;
                }
                BlockState state = level.getBlockState(next);
                if (!(state.getBlock() instanceof VideoScreenBlock)) {
                    continue;
                }
                if (state.getValue(HorizontalDirectionalBlock.FACING) != facing) {
                    continue;
                }
                if (!(level.getBlockEntity(next) instanceof VideoScreenBlockEntity other)
                        || !other.getClip().equals(clip)) {
                    continue;
                }
                queue.add(next);
            }
        }

        Direction left = viewerLeftOf(facing);
        int minLane = Integer.MAX_VALUE;
        int maxLane = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (BlockPos member : members) {
            int lane = member.getX() * left.getStepX() + member.getZ() * left.getStepZ();
            minLane = Math.min(minLane, lane);
            maxLane = Math.max(maxLane, lane);
            minY = Math.min(minY, member.getY());
            maxY = Math.max(maxY, member.getY());
        }
        wallCols = Math.max(1, maxLane - minLane + 1);
        wallRows = Math.max(1, maxY - minY + 1);
        int myLane = worldPosition.getX() * left.getStepX() + worldPosition.getZ() * left.getStepZ();
        wallColFromLeft = maxLane - myLane;
        wallRowFromTop = maxY - worldPosition.getY();
        members.sort(Comparator.naturalOrder());
        wallAnchorPos = members.get(0);
    }

    /** 整面墙读同一个锚点块的播放时钟，保证各块屏幕永远显示同一帧，不会各放各的。 */
    public VideoScreenBlockEntity getClockSource() {
        Level level = getLevel();
        if (level != null && wallAnchorPos != null && !wallAnchorPos.equals(worldPosition)
                && level.getBlockEntity(wallAnchorPos) instanceof VideoScreenBlockEntity anchor
                && anchor.getClip().equals(clip)) {
            return anchor;
        }
        return this;
    }

    public int getWallCols() {
        return Math.max(1, wallCols);
    }

    public int getWallRows() {
        return Math.max(1, wallRows);
    }

    public int getWallColFromLeft() {
        return Math.min(Math.max(wallColFromLeft, 0), getWallCols() - 1);
    }

    public int getWallRowFromTop() {
        return Math.min(Math.max(wallRowFromTop, 0), getWallRows() - 1);
    }

    private void syncToClients() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_ALL);
        }
    }

    @Override
    public void onLoad() {
        super.onLoad();
        wallCacheValid = false;
        Level level = getLevel();
        // 新放下的空白屏：延迟一拍后自动继承相邻同朝向屏幕墙的影像。
        if (level != null && !level.isClientSide && clip.isEmpty()) {
            level.scheduleTick(worldPosition, getBlockState().getBlock(), 1);
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
        wallCacheValid = false;
        // 客户端这一侧：有服务端给的起点就用它，整面墙和所有玩家都按同一个时刻算帧。
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
