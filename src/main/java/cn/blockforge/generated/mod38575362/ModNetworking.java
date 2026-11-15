package cn.blockforge.generated.mod38575362;

import net.minecraft.core.BlockPos;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.DistExecutor;
import net.minecraftforge.fml.event.lifecycle.FMLCommonSetupEvent;
import net.minecraftforge.network.NetworkEvent;
import net.minecraftforge.network.NetworkRegistry;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.simple.SimpleChannel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * 模组的全部网络消息。协议 5：录像本体可以上传到服务器、别的玩家按需下载，
 * 装片/暂停/继续改由服务端给出统一播放时间，多人看到的是同一帧。
 *
 * <p>新增消息一定要在 {@link #registerMessages} 里注册，否则 {@code sendToServer}
 * 发出去的东西通道不认识，会被直接丢掉（上一版「暂停没反应」就是这么来的）。
 */
public final class ModNetworking {
    private static final String PROTOCOL = "6";
    private static final SimpleChannel CHANNEL = NetworkRegistry.newSimpleChannel(
            new ResourceLocation(ModConstants.MODID, "main"),
            () -> PROTOCOL,
            PROTOCOL::equals,
            PROTOCOL::equals);
    private static int messageId;

    /** 录像库列表一次最多带多少段；与 ServerClipStore 的扫描上限保持一致，别漏掉后面的。 */
    private static final int LIST_PACKET_LIMIT = 200;
    /** 问全体玩家「这段谁有」的最小间隔，避免没人应答时刷屏。 */
    private static final long ASK_OWNER_MS = 8000L;
    private static final int MAX_ASKED_TRACKED = 64;
    /** 服务端记录：哪些缺失的片段已经问过、上次是什么时候问的。 */
    private static final Map<String, Long> ASKED = new HashMap<>();

    private ModNetworking() {
    }

    public static void register(IEventBus modBus) {
        modBus.addListener(ModNetworking::registerMessages);
    }

    private static void registerMessages(FMLCommonSetupEvent event) {
        CHANNEL.registerMessage(messageId++, SaveClipMessage.class, SaveClipMessage::encode,
                SaveClipMessage::decode, SaveClipMessage::handle);
        CHANNEL.registerMessage(messageId++, DeckClipMessage.class, DeckClipMessage::encode,
                DeckClipMessage::decode, DeckClipMessage::handle);
        // 上一版漏了这一条：没注册的消息发出去会被通道直接丢掉，暂停按钮因此没反应。
        CHANNEL.registerMessage(messageId++, DeckToggleMessage.class, DeckToggleMessage::encode,
                DeckToggleMessage::decode, DeckToggleMessage::handle);

        // ---- 客户端 → 服务端 ----
        CHANNEL.registerMessage(messageId++, ClipListRequestMessage.class, ClipListRequestMessage::encode,
                buffer -> new ClipListRequestMessage(), ClipListRequestMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipInfoRequestMessage.class, ClipInfoRequestMessage::encode,
                ClipInfoRequestMessage::decode, ClipInfoRequestMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipFrameRequestMessage.class, ClipFrameRequestMessage::encode,
                ClipFrameRequestMessage::decode, ClipFrameRequestMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipUploadBeginMessage.class, ClipUploadBeginMessage::encode,
                ClipUploadBeginMessage::decode, ClipUploadBeginMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipUploadFrameMessage.class, ClipUploadFrameMessage::encode,
                ClipUploadFrameMessage::decode, ClipUploadFrameMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipUploadEndMessage.class, ClipUploadEndMessage::encode,
                ClipUploadEndMessage::decode, ClipUploadEndMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipDeleteMessage.class, ClipDeleteMessage::encode,
                ClipDeleteMessage::decode, ClipDeleteMessage::handle);

        // ---- 服务端 → 客户端 ----
        CHANNEL.registerMessage(messageId++, ClipListMessage.class, ClipListMessage::encode,
                ClipListMessage::decode, ClipListMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipMetaMessage.class, ClipMetaMessage::encode,
                ClipMetaMessage::decode, ClipMetaMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipFrameMessage.class, ClipFrameMessage::encode,
                ClipFrameMessage::decode, ClipFrameMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipAskOwnerMessage.class, ClipAskOwnerMessage::encode,
                ClipAskOwnerMessage::decode, ClipAskOwnerMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipUploadGrantMessage.class, ClipUploadGrantMessage::encode,
                ClipUploadGrantMessage::decode, ClipUploadGrantMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipChangedMessage.class, ClipChangedMessage::encode,
                ClipChangedMessage::decode, ClipChangedMessage::handle);
        CHANNEL.registerMessage(messageId++, ClipNoticeMessage.class, ClipNoticeMessage::encode,
                ClipNoticeMessage::decode, ClipNoticeMessage::handle);
    }

    // ------------------------------------------------------------------ 发送入口

    public static void saveClipToServer(String clip, VideoProfile profile, int frames, long duration) {
        CHANNEL.sendToServer(new SaveClipMessage(clip, profile.id(), frames, profile.fps(), duration));
    }

    /** 录像库界面上按下「回放/停止」后，让服务端更新录像记录器方块里正在放哪一段。 */
    public static void setDeckClip(BlockPos pos, String clip, int frames, int fps) {
        CHANNEL.sendToServer(new DeckClipMessage(pos, clip, frames, fps));
    }

    /** 录像库界面上按下「暂停/继续」。 */
    public static void toggleDeckClip(BlockPos pos) {
        CHANNEL.sendToServer(new DeckToggleMessage(pos));
    }

    /** 向服务端要一份服务器录像库的清单。 */
    public static void requestClipList() {
        CHANNEL.sendToServer(new ClipListRequestMessage());
    }

    /** 问服务端某一段的具体信息（本机没有文件时先问这个）。 */
    public static void requestClipInfo(String clip) {
        CHANNEL.sendToServer(new ClipInfoRequestMessage(clip));
    }

    /** 索要某一段的第 index 帧。 */
    public static void requestClipFrame(String clip, int index) {
        CHANNEL.sendToServer(new ClipFrameRequestMessage(clip, index));
    }

    public static void beginUpload(String clip, String metaJson, int frames) {
        CHANNEL.sendToServer(new ClipUploadBeginMessage(clip, metaJson, frames));
    }

    public static void uploadFrame(String clip, int index, byte[] data) {
        CHANNEL.sendToServer(new ClipUploadFrameMessage(clip, index, data));
    }

    public static void endUpload(String clip) {
        CHANNEL.sendToServer(new ClipUploadEndMessage(clip));
    }

    /** 请求服务端把这段录像从服务器目录里也删掉。 */
    public static void deleteClipFromServer(String clip) {
        CHANNEL.sendToServer(new ClipDeleteMessage(clip));
    }

    /** 单发：只给某个玩家。 */
    private static void toPlayer(ServerPlayer player, Object message) {
        if (player != null) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    /** 广播：服务端 → 这台服务器上的所有人（含上传者，大家统一刷新缓存）。 */
    private static void broadcast(MinecraftServer server, Object message) {
        if (server == null) {
            return;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            CHANNEL.send(PacketDistributor.PLAYER.with(() -> player), message);
        }
    }

    /**
     * 服务器上没有这段录像时问一句「在线谁本地有」。有的人会主动把它传上来，
     * 局域网联机时房主就是这么把录像交给自己开的服务端的一 — 客户端不用提前上传。
     */
    private static void askOwnerFor(ServerPlayer requester, String clip) {
        MinecraftServer server = requester.server;
        long now = System.currentTimeMillis();
        synchronized (ASKED) {
            Long last = ASKED.get(clip);
            if (last != null && now - last < ASK_OWNER_MS) {
                return;
            }
            if (ASKED.size() >= MAX_ASKED_TRACKED) {
                ASKED.clear();
            }
            ASKED.put(clip, now);
        }
        broadcast(server, new ClipAskOwnerMessage(clip));
    }

    /** 找到背包里第一卷空白胶卷，把片段编号和画质写上去。 */
    private static void writeToFilm(ServerPlayer player, String clip, VideoProfile profile, int frames, int fps, long duration) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModRegistry.FILM.get()) && !FilmItem.hasClip(stack)) {
                FilmItem.writeClip(stack, clip, profile, frames, fps, duration);
                player.containerMenu.broadcastChanges();
                return;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        if (offhand.is(ModRegistry.FILM.get()) && !FilmItem.hasClip(offhand)) {
            FilmItem.writeClip(offhand, clip, profile, frames, fps, duration);
            player.containerMenu.broadcastChanges();
        }
    }

    // ------------------------------------------------------------------ 老三条消息

    private record SaveClipMessage(String clip, String profileId, int frames, int fps, long duration) {
        private static void encode(SaveClipMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 64);
            buffer.writeUtf(message.profileId, 32);
            buffer.writeInt(message.frames);
            buffer.writeInt(message.fps);
            buffer.writeLong(message.duration);
        }

        private static SaveClipMessage decode(FriendlyByteBuf buffer) {
            return new SaveClipMessage(buffer.readUtf(64), buffer.readUtf(32), buffer.readInt(), buffer.readInt(), buffer.readLong());
        }

        private static void handle(SaveClipMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                VideoProfile profile = VideoProfile.byId(message.profileId);
                if (player != null && ClipWire.isSafeClipName(message.clip)
                        && message.frames >= ModConstants.MIN_FRAMES
                        && message.frames <= ModConstants.MAX_STORED_FRAMES
                        && message.fps >= 1 && message.fps <= 30) {
                    writeToFilm(player, message.clip, profile, message.frames, message.fps, message.duration);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 录像库 → 记录器：把某段录像装上方块（clip 传空串表示取出）；clip 就是文件夹名，中文空格都行。 */
    private record DeckClipMessage(BlockPos pos, String clip, int frames, int fps) {
        private static void encode(DeckClipMessage message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.pos);
            buffer.writeUtf(message.clip, 1024);
            buffer.writeInt(message.frames);
            buffer.writeInt(message.fps);
        }

        private static DeckClipMessage decode(FriendlyByteBuf buffer) {
            return new DeckClipMessage(buffer.readBlockPos(), buffer.readUtf(1024), buffer.readInt(), buffer.readInt());
        }

        private static void handle(DeckClipMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !near(player, message.pos)) {
                    return;
                }
                Level level = player.level();
                if (!level.isLoaded(message.pos)
                        || !(level.getBlockEntity(message.pos) instanceof VideoPlayerBlockEntity deck)) {
                    return;
                }
                if (message.clip.isEmpty()) {
                    deck.clearClip();
                } else if (ClipWire.isSafeClipName(message.clip)
                        && message.frames >= 1 && message.frames <= ModConstants.MAX_STORED_FRAMES
                        && message.fps >= 1 && message.fps <= 240) {
                    // 服务器上有这段时以服务器记下的帧数/帧率为准，这样所有人数的帧号一致。
                    ClipMeta onServer = ServerClipStore.find(message.clip);
                    int useFrames = onServer != null && onServer.frames() > 0 ? onServer.frames() : message.frames;
                    int useFps = onServer != null && onServer.fps() > 0 ? onServer.fps() : message.fps;
                    deck.insertClip(message.clip, useFrames, useFps);
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 录像库 → 记录器：暂停/继续当前回放。 */
    private record DeckToggleMessage(BlockPos pos) {
        private static void encode(DeckToggleMessage message, FriendlyByteBuf buffer) {
            buffer.writeBlockPos(message.pos);
        }

        private static DeckToggleMessage decode(FriendlyByteBuf buffer) {
            return new DeckToggleMessage(buffer.readBlockPos());
        }

        private static void handle(DeckToggleMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !near(player, message.pos)) {
                    return;
                }
                Level level = player.level();
                if (!level.isLoaded(message.pos)
                        || !(level.getBlockEntity(message.pos) instanceof VideoPlayerBlockEntity deck)
                        || !deck.hasClip()) {
                    return;
                }
                deck.setPlaying(!deck.isPlaying());
            });
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ 录像库同步

    /** 客户端：给我一份服务器录像清单。 */
    private record ClipListRequestMessage() {
        private static void encode(ClipListRequestMessage message, FriendlyByteBuf buffer) {
        }

        private static void handle(ClipListRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player != null) {
                    List<ClipMeta> metas = new ArrayList<>();
                    for (ClipMeta meta : ServerClipStore.list()) {
                        if (metas.size() >= LIST_PACKET_LIMIT) {
                            break;
                        }
                        metas.add(meta);
                    }
                    toPlayer(player, new ClipListMessage(metas));
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：某一段服务器知道多少信息。 */
    private record ClipInfoRequestMessage(String clip) {
        private static void encode(ClipInfoRequestMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
        }

        private static ClipInfoRequestMessage decode(FriendlyByteBuf buffer) {
            return new ClipInfoRequestMessage(buffer.readUtf(1024));
        }

        private static void handle(ClipInfoRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !ClipWire.isSafeClipName(message.clip)) {
                    return;
                }
                ClipMeta meta = ServerClipStore.find(message.clip);
                if (meta != null) {
                    toPlayer(player, new ClipMetaMessage(meta));
                } else {
                    // 服务器也没有：问问在线的人谁本地有这段，让他传上来（局域网房主靠这条路）。
                    askOwnerFor(player, message.clip);
                    toPlayer(player, new ClipMetaMessage(null));
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：要某一段的第 index 帧。 */
    private record ClipFrameRequestMessage(String clip, int index) {
        private static void encode(ClipFrameRequestMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeVarInt(message.index);
        }

        private static ClipFrameRequestMessage decode(FriendlyByteBuf buffer) {
            return new ClipFrameRequestMessage(buffer.readUtf(1024), buffer.readVarInt());
        }

        private static void handle(ClipFrameRequestMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !ClipWire.isSafeClipName(message.clip)
                        || message.index < 0) {
                    return;
                }
                // MP4 整文件：按块返回（与 ClipFileFetcher.CHUNK_SIZE 一致）
                if (ServerClipStore.fileSizeOf(message.clip) > 0L) {
                    if (message.index > ServerClipStore.MAX_FILE_CHUNKS) {
                        return;
                    }
                    byte[] chunk = ServerClipStore.readFileChunk(message.clip, message.index, 256 * 1024);
                    toPlayer(player, new ClipFrameMessage(message.clip, message.index,
                            chunk == null ? new byte[0] : chunk));
                    return;
                }
                // 旧 JPG 帧
                if (message.index > ModConstants.MAX_STORED_FRAMES) {
                    return;
                }
                byte[] data = ServerClipStore.readFrame(message.clip, message.index);
                if (data == null) {
                    askOwnerFor(player, message.clip);
                    toPlayer(player, new ClipFrameMessage(message.clip, message.index, new byte[0]));
                } else {
                    toPlayer(player, new ClipFrameMessage(message.clip, message.index, data));
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：我打算把这段录像传到服务器上。 */
    private record ClipUploadBeginMessage(String clip, String metaJson, int frames) {
        private static void encode(ClipUploadBeginMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeUtf(message.metaJson, ClipWire.MAX_META_CHARS + 64);
            buffer.writeVarInt(message.frames);
        }

        private static ClipUploadBeginMessage decode(FriendlyByteBuf buffer) {
            return new ClipUploadBeginMessage(buffer.readUtf(1024),
                    buffer.readUtf(ClipWire.MAX_META_CHARS + 64), buffer.readVarInt());
        }

        private static void handle(ClipUploadBeginMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                String[] reason = new String[1];
                int grant = ServerClipStore.beginUpload(message.clip, player.getName().getString(),
                        message.metaJson, message.frames, reason);
                toPlayer(player, new ClipUploadGrantMessage(message.clip, grant,
                        reason[0] == null ? "" : reason[0]));
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：上传一帧原始 JPG 字节。 */
    private record ClipUploadFrameMessage(String clip, int index, byte[] data) {
        private static void encode(ClipUploadFrameMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeVarInt(message.index);
            buffer.writeByteArray(message.data);
        }

        private static ClipUploadFrameMessage decode(FriendlyByteBuf buffer) {
            return new ClipUploadFrameMessage(buffer.readUtf(1024), buffer.readVarInt(),
                    buffer.readByteArray(ClipWire.MAX_FRAME_BYTES + 1024));
        }

        private static void handle(ClipUploadFrameMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null) {
                    return;
                }
                if (!ServerClipStore.writeFrame(message.clip, player.getName().getString(),
                        message.index, message.data)) {
                    // 服务端不要了（超限/会话过期），终止这次上传并告诉玩家原因。
                    ServerClipStore.cancelUpload(message.clip);
                    toPlayer(player, new ClipUploadGrantMessage(message.clip, ServerClipStore.GRANT_DENY,
                            "服务器中断了这次上传（单帧太大、总量超限或超时）"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：这一段的帧传完了，请把 meta.json 落盘。 */
    private record ClipUploadEndMessage(String clip) {
        private static void encode(ClipUploadEndMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
        }

        private static ClipUploadEndMessage decode(FriendlyByteBuf buffer) {
            return new ClipUploadEndMessage(buffer.readUtf(1024));
        }

        private static void handle(ClipUploadEndMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !ClipWire.isSafeClipName(message.clip)) {
                    return;
                }
                ClipMeta saved = ServerClipStore.endUpload(message.clip, player.getName().getString());
                if (saved != null) {
                    toPlayer(player, new ClipNoticeMessage("录像「" + saved.id() + "」已存进服务器录像库，别人也能看了"));
                    broadcast(player.server, new ClipChangedMessage(saved.id(), ClipChangedMessage.UPSERT, saved));
                } else {
                    // 帧没到齐（掉线、半路退回）：明确告诉上传者这次没入库，别让他以为传好了。
                    toPlayer(player, new ClipNoticeMessage("「" + message.clip + "」没能存进服务器录像库：帧没传全，请再传一次"));
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 客户端：把这段从服务器上也删掉。 */
    private record ClipDeleteMessage(String clip) {
        private static void encode(ClipDeleteMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
        }

        private static ClipDeleteMessage decode(FriendlyByteBuf buffer) {
            return new ClipDeleteMessage(buffer.readUtf(1024));
        }

        private static void handle(ClipDeleteMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            context.enqueueWork(() -> {
                ServerPlayer player = context.getSender();
                if (player == null || !ClipWire.isSafeClipName(message.clip)) {
                    return;
                }
                if (ServerClipStore.deleteClip(message.clip, player.getName().getString())) {
                    toPlayer(player, new ClipNoticeMessage("服务器上的录像「" + message.clip + "」已删除"));
                    broadcast(player.server, new ClipChangedMessage(message.clip, ClipChangedMessage.REMOVED, null));
                } else {
                    ClipMeta meta = ServerClipStore.find(message.clip);
                    if (meta != null) {
                        toPlayer(player, new ClipNoticeMessage("服务器上这段录像是 " + meta.owner() + " 传的，没帮你删"));
                    }
                }
            });
            context.setPacketHandled(true);
        }
    }

    /** 服务端：一整套服务器录像清单。 */
    private record ClipListMessage(List<ClipMeta> clips) {
        private static void encode(ClipListMessage message, FriendlyByteBuf buffer) {
            buffer.writeVarInt(message.clips.size());
            for (ClipMeta meta : message.clips) {
                meta.encode(buffer);
            }
        }

        private static ClipListMessage decode(FriendlyByteBuf buffer) {
            int count = Math.min(buffer.readVarInt(), LIST_PACKET_LIMIT);
            List<ClipMeta> list = new ArrayList<>(Math.max(0, count));
            for (int i = 0; i < count; i++) {
                list.add(ClipMeta.decode(buffer));
            }
            return new ClipListMessage(list);
        }

        private static void handle(ClipListMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            List<ClipMeta> clips = message.clips;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleList(clips)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端：单段信息（meta 为 null 表示服务器也没有这段）。 */
    private record ClipMetaMessage(ClipMeta meta) {
        private static void encode(ClipMetaMessage message, FriendlyByteBuf buffer) {
            buffer.writeBoolean(message.meta != null);
            if (message.meta != null) {
                message.meta.encode(buffer);
            }
        }

        private static ClipMetaMessage decode(FriendlyByteBuf buffer) {
            return new ClipMetaMessage(buffer.readBoolean() ? ClipMeta.decode(buffer) : null);
        }

        private static void handle(ClipMetaMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            ClipMeta meta = message.meta;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleMeta(meta)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端：一帧的原始字节，空数组表示服务器暂时没有。 */
    private record ClipFrameMessage(String clip, int index, byte[] data) {
        private static void encode(ClipFrameMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeVarInt(message.index);
            buffer.writeByteArray(message.data);
        }

        private static ClipFrameMessage decode(FriendlyByteBuf buffer) {
            return new ClipFrameMessage(buffer.readUtf(1024), buffer.readVarInt(),
                    buffer.readByteArray(ClipWire.MAX_FRAME_BYTES + 1024));
        }

        private static void handle(ClipFrameMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            String clip = message.clip;
            int index = message.index;
            byte[] data = message.data;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleFrame(clip, index, data)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端问在线玩家：谁本地有这段？有的话传上来。 */
    private record ClipAskOwnerMessage(String clip) {
        private static void encode(ClipAskOwnerMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
        }

        private static ClipAskOwnerMessage decode(FriendlyByteBuf buffer) {
            return new ClipAskOwnerMessage(buffer.readUtf(1024));
        }

        private static void handle(ClipAskOwnerMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            String clip = message.clip;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleAskOwner(clip)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端：同意/拒绝这次上传。 */
    private record ClipUploadGrantMessage(String clip, int code, String reason) {
        private static void encode(ClipUploadGrantMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeVarInt(message.code);
            buffer.writeUtf(message.reason, 256);
        }

        private static ClipUploadGrantMessage decode(FriendlyByteBuf buffer) {
            return new ClipUploadGrantMessage(buffer.readUtf(1024), buffer.readVarInt(), buffer.readUtf(256));
        }

        private static void handle(ClipUploadGrantMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            String clip = message.clip;
            int code = message.code;
            String reason = message.reason;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleGrant(clip, code, reason)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端广播：服务器录像库有变化（新增/更新或删除）。 */
    private record ClipChangedMessage(String clip, int kind, ClipMeta meta) {
        /** 新增或内容变了。 */
        static final int UPSERT = 0;
        /** 从服务器上删掉了。 */
        static final int REMOVED = 1;

        private static void encode(ClipChangedMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.clip, 1024);
            buffer.writeVarInt(message.kind);
            buffer.writeBoolean(message.meta != null);
            if (message.meta != null) {
                message.meta.encode(buffer);
            }
        }

        private static ClipChangedMessage decode(FriendlyByteBuf buffer) {
            String clip = buffer.readUtf(1024);
            int kind = buffer.readVarInt();
            ClipMeta meta = buffer.readBoolean() ? ClipMeta.decode(buffer) : null;
            return new ClipChangedMessage(clip, kind, meta);
        }

        private static void handle(ClipChangedMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            String clip = message.clip;
            int kind = message.kind;
            ClipMeta meta = message.meta;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleChanged(clip, kind, meta)));
            context.setPacketHandled(true);
        }
    }

    /** 服务端给某个玩家的一句人话提示（上传成功、没能删除之类）。 */
    private record ClipNoticeMessage(String text) {
        private static void encode(ClipNoticeMessage message, FriendlyByteBuf buffer) {
            buffer.writeUtf(message.text, 512);
        }

        private static ClipNoticeMessage decode(FriendlyByteBuf buffer) {
            return new ClipNoticeMessage(buffer.readUtf(512));
        }

        private static void handle(ClipNoticeMessage message, Supplier<NetworkEvent.Context> contextSupplier) {
            NetworkEvent.Context context = contextSupplier.get();
            String text = message.text;
            context.enqueueWork(() -> onClient(() -> ClipClientProxy.handleNotice(text)));
            context.setPacketHandled(true);
        }
    }

    // ------------------------------------------------------------------ 小工具

    /** 只允许发送者附近 8 格内的方块被改动，防止远程乱改别人的记录器。 */
    private static boolean near(ServerPlayer player, BlockPos pos) {
        return player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0;
    }

    /** 服务端 → 客户端的包只在客户端处理，包一层 lambda 让专用服务端永远不加载客户端类。 */
    private static void onClient(Runnable clientWork) {
        DistExecutor.unsafeRunWhenOn(Dist.CLIENT, () -> clientWork::run);
    }
}
