package cn.blockforge.generated.mod38575362;

import net.minecraft.network.FriendlyByteBuf;

/**
 * 一段录像在服务器录像库里的“名片”：只放不需要传图像就能说清楚的信息。
 *
 * <p>尺寸、帧数这些如果服务器不知道（比如有人手动往服务器目录里丢了一串图，
 * 却没有 meta.json），对应字段给 0，让客户端下载第一帧后自己识别——
 * 服务器不加载任何图像解码的原生库，所以它压根不该去解帧。
 *
 * @param id        文件夹名称，同时也是界面显示名与网络编号
 * @param width     画面宽，0 表示服务器不知道
 * @param height    画面高，0 表示服务器不知道
 * @param frames    帧数，0 表示服务器数不出来
 * @param fps       帧率
 * @param durationMs 时长（毫秒）
 * @param bytes     占用磁盘字节数（整个片段目录）
 * @param videoBytes video.mp4 的精确字节数；没有 MP4 时为 0
 * @param created   录制时刻（Unix 毫秒）
 * @param owner     上传者名字，空串表示服务器上手动放的
 * @param profileId 画质档位编号
 * @param format    帧格式：mp4 / jpg / png
 */
public record ClipMeta(String id, int width, int height, int frames, int fps, long durationMs,
                       long bytes, long videoBytes, long created, String owner, String profileId,
                       String format) {

    public void encode(FriendlyByteBuf buffer) {
        buffer.writeUtf(id, ClipWire.MAX_CLIP_NAME_CHARS * 2);
        buffer.writeVarInt(clamp(width, 0, ModConstants.MAX_VIDEO_WIDTH));
        buffer.writeVarInt(clamp(height, 0, ModConstants.MAX_VIDEO_HEIGHT));
        buffer.writeVarInt(clamp(frames, 0, ModConstants.MAX_STORED_FRAMES));
        buffer.writeVarInt(clamp(fps, 1, 240));
        buffer.writeLong(Math.max(0L, durationMs));
        buffer.writeLong(clamp(bytes, 0L, ClipWire.MAX_SERVER_BYTES));
        buffer.writeLong(clamp(videoBytes, 0L, ClipWire.MAX_CLIP_BYTES));
        buffer.writeLong(Math.max(0L, created));
        buffer.writeUtf(owner == null ? "" : owner, 64);
        buffer.writeUtf(profileId == null ? "" : profileId, 32);
        buffer.writeUtf(normalizeFormat(format), 16);
    }

    public static ClipMeta decode(FriendlyByteBuf buffer) {
        String id = buffer.readUtf(ClipWire.MAX_CLIP_NAME_CHARS * 2);
        int width = clamp(buffer.readVarInt(), 0, ModConstants.MAX_VIDEO_WIDTH);
        int height = clamp(buffer.readVarInt(), 0, ModConstants.MAX_VIDEO_HEIGHT);
        int frames = clamp(buffer.readVarInt(), 0, ModConstants.MAX_STORED_FRAMES);
        int fps = clamp(buffer.readVarInt(), 1, 240);
        long durationMs = Math.max(0L, buffer.readLong());
        long bytes = clamp(buffer.readLong(), 0L, ClipWire.MAX_SERVER_BYTES);
        long videoBytes = clamp(buffer.readLong(), 0L, ClipWire.MAX_CLIP_BYTES);
        long created = Math.max(0L, buffer.readLong());
        String owner = buffer.readUtf(64);
        String profileId = buffer.readUtf(32);
        String format = normalizeFormat(buffer.readUtf(16));
        return new ClipMeta(id, width, height, frames, fps, durationMs, bytes, videoBytes,
                created, owner, profileId, format);
    }

    /** 服务器是否知道这段录像的画面尺寸（不知道就先下一帧自己认）。 */
    public boolean sizeKnown() {
        return width > 0 && height > 0;
    }

    private static String normalizeFormat(String value) {
        if (value == null) {
            return ModConstants.FRAME_EXTENSION;
        }
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if ("mp4".equals(lower) || "png".equals(lower) || "jpg".equals(lower) || "jpeg".equals(lower)) {
            return "jpeg".equals(lower) ? "jpg" : lower;
        }
        return ModConstants.FRAME_EXTENSION;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }
}
