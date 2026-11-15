package cn.blockforge.generated.mod38575362;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import javax.annotation.Nullable;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class VideoScreenBlock extends HorizontalDirectionalBlock implements EntityBlock {
    private static final Direction[] SIDES = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP, Direction.DOWN
    };
    // 整面薄板：占满方块的 16x16 截面，相邻两块屏幕的显示面完全共面，中间没有缝。
    private static final VoxelShape SHAPE_NS = Shapes.box(0.0, 0.0, 6.5 / 16.0, 1.0, 1.0, 9.5 / 16.0);
    private static final VoxelShape SHAPE_EW = Shapes.box(6.5 / 16.0, 0.0, 0.0, 9.5 / 16.0, 1.0, 1.0);

    public VideoScreenBlock() {
        super(Properties.of().strength(2.5F).noOcclusion());
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        // 显示屏正面朝向玩家。
        return defaultBlockState().setValue(BlockStateProperties.HORIZONTAL_FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return switch (state.getValue(FACING)) {
            case EAST, WEST -> SHAPE_EW;
            default -> SHAPE_NS;
        };
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new VideoScreenBlockEntity(pos, state);
    }

    @Override
    public void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        // 空白屏放下后自动继承相邻同朝向屏幕墙的影像，不用重新装卷。
        if (!(level.getBlockEntity(pos) instanceof VideoScreenBlockEntity screen) || screen.hasClip()) {
            return;
        }
        Direction facing = state.getValue(FACING);
        for (Direction side : SIDES) {
            if (level.getBlockEntity(pos.relative(side)) instanceof VideoScreenBlockEntity neighbour
                    && neighbour.hasClip()
                    && neighbour.getBlockState().getValue(FACING) == facing) {
                // 播放起点跟着邻居走，新加的这块屏才会和墙上其他块同帧。
                screen.insertClip(neighbour.getClip(), neighbour.getFrames(), neighbour.getFps(),
                        neighbour.getStartMs());
                return;
            }
        }
    }

    @Override
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player,
                                 InteractionHand hand, BlockHitResult hit) {
        VideoScreenBlockEntity screen = level.getBlockEntity(pos) instanceof VideoScreenBlockEntity value ? value : null;
        if (screen == null) {
            return InteractionResult.PASS;
        }

        Direction facing = state.getValue(FACING);
        ItemStack held = player.getItemInHand(hand);
        if (held.is(ModRegistry.FILM.get())) {
            if (!level.isClientSide) {
                if (FilmItem.hasClip(held)) {
                    String newClip = FilmItem.getClip(held);
                    if (!ClipWire.isSafeClipName(newClip)) {
                        player.displayClientMessage(Component.literal("这卷胶卷的录像编号无效。"), true);
                        return InteractionResult.sidedSuccess(false);
                    }
                    // 装卷时整面墙一起换片：相邻、同朝向的空白屏或旧片屏全部并入。
                    List<VideoScreenBlockEntity> wall = collectWall(level, pos, facing,
                            candidate -> candidate.getClip().isEmpty() || candidate.getClip().equals(newClip));
                    ClipMeta serverMeta = ServerClipStore.find(newClip);
                    int frames = serverMeta != null && serverMeta.frames() > 0
                            ? serverMeta.frames() : FilmItem.getFrames(held);
                    int fps = serverMeta != null && serverMeta.fps() > 0
                            ? serverMeta.fps() : FilmItem.getFps(held);
                    long startMs = System.currentTimeMillis();
                    for (VideoScreenBlockEntity member : wall) {
                        member.insertClip(newClip, frames, fps, startMs);
                    }
                    player.displayClientMessage(Component.literal(wall.size() > 1
                            ? "胶卷已装入，" + wall.size() + " 块显示屏已合并为一整幅画面。"
                            : "胶卷已装入显示屏，开始播放。"), true);
                } else {
                    player.displayClientMessage(Component.literal("这卷胶卷还是空白的，先用摄像机录制。"), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        if (held.isEmpty()) {
            if (!level.isClientSide) {
                if (player.isShiftKeyDown()) {
                    if (screen.hasClip()) {
                        for (VideoScreenBlockEntity member : sameClipWall(level, pos, facing, screen)) {
                            member.clearClip();
                        }
                        player.displayClientMessage(Component.literal("显示屏已清空。"), true);
                    } else {
                        player.displayClientMessage(Component.literal("显示屏里还没有胶卷。"), true);
                    }
                } else if (screen.hasClip()) {
                    boolean target = !screen.isPlaying();
                    for (VideoScreenBlockEntity member : sameClipWall(level, pos, facing, screen)) {
                        member.setPlaying(target);
                    }
                    player.displayClientMessage(Component.literal(target ? "录像继续播放。" : "录像已暂停。"), true);
                } else {
                    player.displayClientMessage(Component.literal("请拿着录制好的胶卷右键显示屏。"), true);
                }
            }
            return InteractionResult.sidedSuccess(level.isClientSide);
        }

        return InteractionResult.PASS;
    }

    /** 与点击块属于同一面墙（同朝向、同一卷胶卷）的所有成员。 */
    private static List<VideoScreenBlockEntity> sameClipWall(Level level, BlockPos pos, Direction facing,
                                                             VideoScreenBlockEntity screen) {
        String clipId = screen.getClip();
        return collectWall(level, pos, facing, candidate -> candidate.getClip().equals(clipId));
    }

    /** 从 start 向六个方向漫延，收集朝向相同且通过筛选的显示屏；起点块本身总是包含。 */
    private static List<VideoScreenBlockEntity> collectWall(Level level, BlockPos start, Direction facing,
                                                            Predicate<VideoScreenBlockEntity> accept) {
        List<VideoScreenBlockEntity> members = new ArrayList<>();
        Set<BlockPos> visited = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        visited.add(start);
        queue.add(start);
        while (!queue.isEmpty() && members.size() < VideoScreenBlockEntity.MAX_WALL) {
            BlockPos current = queue.poll();
            if (level.getBlockEntity(current) instanceof VideoScreenBlockEntity be) {
                members.add(be);
            }
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
                if (!(level.getBlockEntity(next) instanceof VideoScreenBlockEntity other) || !accept.test(other)) {
                    continue;
                }
                queue.add(next);
            }
        }
        return members;
    }
}
