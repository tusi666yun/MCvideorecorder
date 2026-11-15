package cn.blockforge.generated.mod38575362;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.RegistryObject;

public final class ModRegistry {
    public static final DeferredRegister<Block> BLOCKS = DeferredRegister.create(Registries.BLOCK, ModConstants.MODID);
    public static final DeferredRegister<Item> ITEMS = DeferredRegister.create(Registries.ITEM, ModConstants.MODID);
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITIES = DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, ModConstants.MODID);

    public static final RegistryObject<Block> FILM_SCREEN = BLOCKS.register("film_screen", VideoScreenBlock::new);
    public static final RegistryObject<Item> FILM_SCREEN_ITEM = ITEMS.register("film_screen", () -> new BlockItem(FILM_SCREEN.get(), new Item.Properties()));
    /** 录像记录器：右键打开本地录像库，可以查看、回放、删除本机录过的片段。 */
    public static final RegistryObject<Block> VIDEO_PLAYER = BLOCKS.register("video_player", VideoPlayerBlock::new);
    public static final RegistryObject<Item> VIDEO_PLAYER_ITEM = ITEMS.register("video_player",
            () -> new BlockItem(VIDEO_PLAYER.get(), new Item.Properties().rarity(Rarity.UNCOMMON)));
    public static final RegistryObject<Item> CAMERA = ITEMS.register("camera",
            () -> new CameraItem(new Item.Properties().stacksTo(1), VideoProfile.STANDARD));
    /** 高清摄像机：640×360 / 12 帧/秒，配同样的胶卷，录出来的画面细节多一个数量级。 */
    public static final RegistryObject<Item> HD_CAMERA = ITEMS.register("hd_camera",
            () -> new CameraItem(new Item.Properties().stacksTo(1).rarity(Rarity.RARE), VideoProfile.HIGH_DEFINITION));
    public static final RegistryObject<Item> FILM = ITEMS.register("film_roll", () -> new FilmItem(new Item.Properties().stacksTo(1)));
    public static final RegistryObject<BlockEntityType<VideoScreenBlockEntity>> FILM_SCREEN_ENTITY = BLOCK_ENTITIES.register(
            "film_screen", () -> BlockEntityType.Builder.of(VideoScreenBlockEntity::new, FILM_SCREEN.get()).build(null));
    public static final RegistryObject<BlockEntityType<VideoPlayerBlockEntity>> VIDEO_PLAYER_ENTITY = BLOCK_ENTITIES.register(
            "video_player", () -> BlockEntityType.Builder.of(VideoPlayerBlockEntity::new, VIDEO_PLAYER.get()).build(null));

    private ModRegistry() {
    }
}
