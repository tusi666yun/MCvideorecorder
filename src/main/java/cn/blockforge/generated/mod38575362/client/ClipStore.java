package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipMeta;
import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.VideoProfile;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 录像片段的文件仓库：一个片段就是游戏目录里 videorecorder_clips 下的一个文件夹，
 * 里面是一串 frame_0000.jpg 加一份 meta.json。
 *
 * <p>高清一档一帧就是 640×360，整卷全解码进内存要两百多兆，所以这里改成
 * 「只读要播的那一帧」：解码放到后台线程预取，内存里按字节预算滚动缓存，
 * 播完一段只留最近用到的那几十帧。
 *
 * <p>多人游戏时本机没有文件也不等于看不了：服务器上有这段，就按服务器给的名片
 * 先建一个「待下载」的壳，播到哪一帧就现取哪一帧，取回来的帧缓存进本机目录，
 * 之后重播直接走本地路径，不再占服务器带宽。
 */
public final class ClipStore {
    /** 帧缓存的内存上限，一帧高清约 0.9 MB，够缓冲将近两秒的画面。 */
    private static final long MAX_FRAME_CACHE_BYTES = 24L * 1024 * 1024;
    private static final int MAX_FRAME_CACHE_ENTRIES = 320;
    private static final int MAX_CLIPS = 32;
    private static final int PREFETCH_LIMIT = 4;
    /** 外部塞进来的文件夹帧可能比摄像机出的还大，认到 1080p 为止；缓存按字节淘汰，不会撑爆内存。 */
    private static final int MAX_PROBE_WIDTH = 1920;
    private static final int MAX_PROBE_HEIGHT = 1080;
    /** 没有 meta.json 可参考时，按内容识别的片段默认 12 帧/秒。 */
    private static final int DEFAULT_PROBE_FPS = 12;
    /** 帧缓存与片段信息都由这一把锁保护：解码线程只写自己新建的图，读的人拿不到半成品。 */
    private static final Object LOCK = new Object();
    private static final LinkedHashMap<String, Clip> CLIPS = new LinkedHashMap<>(16, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Clip> eldest) {
            return size() > MAX_CLIPS;
        }
    };
    private static final LinkedHashMap<String, NativeImage> FRAMES = new LinkedHashMap<>(32, 0.75F, true);
    /** MP4 解码器缓存：每个 clip 一个，顺序播放更顺。 */
    private static final java.util.HashMap<String, Mp4FrameSource> MP4_SOURCES = new java.util.HashMap<>();
    private static long frameCacheBytes;
    private static final Set<String> PENDING = new HashSet<>();
    private static ExecutorService decoder;

    private ClipStore() {
    }

    private static File root() {
        return new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips");
    }

    private static File directory(String clip) {
        return new File(root(), clip);
    }

    public static void begin(String clip) throws IOException {
        if (!ClipWire.isSafeClipName(clip)) {
            throw new IOException("非法的录像编号：" + clip);
        }
        File dir = directory(clip);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            throw new IOException("无法创建录像目录：" + dir);
        }
    }

    /** 写一帧。在录像工作线程上调用，不碰渲染线程。 */
    public static void saveFrame(String clip, VideoProfile profile, int frame, NativeImage image) throws IOException {
        if (!ClipWire.isSafeClipName(clip)) {
            throw new IOException("非法的录像编号：" + clip);
        }
        File file = new File(directory(clip), profile.frameName(frame));
        JpegCodec.writeJpeg(image, file, profile.jpegQuality());
    }

    public static void finish(String clip, VideoProfile profile, int frames, long durationMs) throws IOException {
        if (!ClipWire.isSafeClipName(clip)) {
            throw new IOException("非法的录像编号：" + clip);
        }
        File dir = directory(clip);
        JsonObject meta = new JsonObject();
        meta.addProperty("width", profile.width());
        meta.addProperty("height", profile.height());
        meta.addProperty("frames", frames);
        meta.addProperty("fps", profile.fps());
        meta.addProperty("duration_ms", durationMs);
        meta.addProperty("profile", profile.id());
        meta.addProperty("frame_format", ModConstants.FRAME_EXTENSION);
        Files.writeString(new File(dir, "meta.json").toPath(), meta.toString(), StandardCharsets.UTF_8);
    }

    /** 这一卷在硬盘上占了多少字节，用来告诉玩家录完一个片段有多大。 */
    public static long totalSize(String clip) {
        if (!ClipWire.isSafeClipName(clip)) {
            return 0L;
        }
        File[] files = directory(clip).listFiles();
        if (files == null) {
            return 0L;
        }
        long bytes = 0L;
        for (File file : files) {
            if (file.isFile()) {
                bytes += file.length();
            }
        }
        return bytes;
    }

    /** 扫描录像库：本机目录里的片段排在前面，服务器上还有、本机没文件的片段也一并列出。 */
    public static java.util.List<Clip> listClips() {
        java.util.List<Clip> result = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        File[] folders = root().listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                Clip clip = load(folder.getName());
                if (clip != null && seen.add(clip.id)) {
                    if (clip.created <= 0L) {
                        clip.created = folder.lastModified();
                    }
                    result.add(clip);
                }
            }
        }
        for (ClipMeta meta : ClipRegistry.all()) {
            if (seen.contains(meta.id())) {
                continue;
            }
            Clip clip = load(meta.id());
            if (clip != null && seen.add(clip.id)) {
                result.add(clip);
            }
        }
        result.sort(java.util.Comparator.comparingLong((Clip clip) -> clip.created).reversed());
        return result;
    }

    /** 本机有没有这段录像的文件（至少一帧图片）。上传给服务器之前要用它判一下。 */
    public static boolean hasLocal(String clip) {
        if (clip == null || clip.isEmpty() || !ClipWire.isSafeClipName(clip)) {
            return false;
        }
        File dir = directory(clip);
        if (Mp4FrameSource.videoFile(dir) != null) {
            return true;
        }
        return countLocalImages(dir) > 0;
    }

    private static int countLocalImages(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0;
        }
        int count = 0;
        for (File file : files) {
            if (file.isFile() && JpegCodec.looksLikeImage(file.getName())) {
                count++;
            }
        }
        return count;
    }

    /** 这份复制是服务器下载来的（目录里有来源标记），不是玩家自己录的原件。 */
    public static boolean isServerCopy(String clip) {
        return clip != null && ClipWire.isSafeClipName(clip)
                && new File(directory(clip), ClipWire.netMarker()).isFile();
    }

    public static void delete(String clip) {
        if (!ClipWire.isSafeClipName(clip)) {
            return;
        }
        ClipFetcher.forget(clip);
        dropCached(clip);
        File dir = directory(clip);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /** 把某个片段占的内存全部吐出来（换卷、删卷时用）。 */
    public static void dropCached(String clip) {
        dropInfo(clip);
        synchronized (LOCK) {
            Iterator<Map.Entry<String, NativeImage>> it = FRAMES.entrySet().iterator();
            String prefix = clip + '#';
            while (it.hasNext()) {
                Map.Entry<String, NativeImage> entry = it.next();
                if (entry.getKey().startsWith(prefix)) {
                    frameCacheBytes -= imageBytes(entry.getValue());
                    entry.getValue().close();
                    it.remove();
                }
            }
            Mp4FrameSource src = MP4_SOURCES.remove(clip);
            if (src != null) {
                src.close();
            }
        }
    }

    /** 只丢掉片段的名片缓存（解好码的帧留着）：服务器清单变了时用。 */
    public static void dropInfo(String clip) {
        synchronized (LOCK) {
            CLIPS.remove(clip);
        }
    }

    /**
     * 上传用的本地片段：只按本机目录里的文件认，绝不返回「等服务器下载」的那种壳，
     * 免得把本机并没有的文件当成品传上去。
     */
    public static Clip localClip(String clipId) {
        if (!hasLocal(clipId)) {
            return null;
        }
        File dir = directory(clipId);
        Clip clip = loadFromMeta(dir, clipId);
        if (clip == null) {
            clip = loadByProbe(dir, clipId);
        }
        return clip;
    }

    /** 按片段自己的编号规则读出第 index 帧的原始字节（上传给服务器用，不解码）。 */
    public static byte[] readFrameBytes(Clip clip, int index) {
        if (clip == null || clip.remote || index < 0 || index >= clip.frameCount) {
            return null;
        }
        File file = resolveFrame(clip, index);
        if (file == null || !file.isFile() || file.length() > ClipWire.MAX_FRAME_BYTES) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 给上传用的元数据文本：尺寸、帧数、帧率、画质、时长、录制时刻。 */
    public static String buildMeta(Clip clip) {
        JsonObject meta = new JsonObject();
        meta.addProperty("width", clip.width);
        meta.addProperty("height", clip.height);
        meta.addProperty("frames", clip.frameCount);
        meta.addProperty("fps", Math.max(1, clip.fps));
        meta.addProperty("duration_ms", clip.durationMs);
        meta.addProperty("profile", clip.profile == null ? VideoProfile.STANDARD.id() : clip.profile.id());
        meta.addProperty("frame_format", ModConstants.FRAME_EXTENSION);
        meta.addProperty("created_ms", clip.created > 0L ? clip.created : System.currentTimeMillis());
        return meta.toString();
    }

    /**
     * 把服务器发回来的一帧存进本机目录（带来源标记），下次再播就走本地文件。
     * 本机已经有同名不同内容的目录时一律不写，绝不污染玩家自己录的原件。
     */
    public static boolean writeServerFrame(String clip, int index, byte[] data) {
        if (!ClipWire.isSafeClipName(clip) || index < 0 || index > ModConstants.MAX_STORED_FRAMES
                || data == null || data.length == 0 || data.length > ClipWire.MAX_FRAME_BYTES) {
            return false;
        }
        File dir = directory(clip);
        if (dir.isDirectory() && !isServerCopy(clip) && countLocalImages(dir) > 0) {
            return false;
        }
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        try {
            Files.write(new File(dir, ClipWire.canonicalFrameName(index)).toPath(), data);
            File marker = new File(dir, ClipWire.netMarker());
            if (!marker.isFile()) {
                Files.writeString(marker.toPath(), "server", StandardCharsets.UTF_8);
            }
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    /**
     * 服务器说这段已经没了：只清理带来源标记的下载副本，玩家自己录的原件一概不动。
     */
    public static void purgeServerCopy(String clip) {
        ClipFetcher.forget(clip);
        dropCached(clip);
        if (!isServerCopy(clip)) {
            return;
        }
        File dir = directory(clip);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
        dir.delete();
    }

    /**
     * 读取一个片段：先按标准格式读 meta.json；读不出来就按文件夹内容识别
     * （任何名字的 jpg/png 帧都算，玩家自己建或改名的文件夹也能列出来、能播）；
     * 本机一份文件都没有、但服务器上有这段，就按服务器给的名片建一个待下载的壳，
     * 播放时缺哪帧取哪帧。都不行才返回 null，显示屏/记录器保持黑屏而不是崩掉。
     */
    public static Clip load(String clipId) {
        if (!ClipWire.isSafeClipName(clipId)) {
            return null;
        }
        synchronized (LOCK) {
            Clip cached = CLIPS.get(clipId);
            if (cached != null) {
                return cached;
            }
        }

        File dir = directory(clipId);
        Clip clip = loadFromMeta(dir, clipId);
        if (clip == null) {
            clip = loadByProbe(dir, clipId);
        }
        ClipMeta onServer = ClipRegistry.find(clipId);
        if (clip == null) {
            clip = fromServer(clipId, onServer);
        }
        if (clip == null) {
            if (onServer == null) {
                // 本机没有、服务器清单里也没有：问一下服务器（服务器会再问谁本地有），内部有节流。
                ClipFetcher.requestInfo(clipId);
            }
            return null;
        }
        if (clip.created <= 0L) {
            clip.created = createdMsOf(clipId, dir);
        }
        applyServerInfo(clip, onServer);
        // 尺寸或帧数还没认出来的壳先不缓存：等下载到的帧能认出画面再说。
        if (clip.width > 0 && clip.height > 0 && clip.frameCount > 0) {
            synchronized (LOCK) {
                CLIPS.put(clipId, clip);
            }
        }
        return clip;
    }

    /** 本机没有文件、服务器有这段：按服务器名片建一个「播到哪帧取哪帧」的壳。 */
    private static Clip fromServer(String clipId, ClipMeta meta) {
        if (!ClipWire.isSafeClipName(clipId) || meta == null || meta.frames() <= 0) {
            return null;
        }
        String format = meta.format();
        if ("mp4".equalsIgnoreCase(format)) {
            format = "mp4";
        } else if ("png".equalsIgnoreCase(format)) {
            format = "png";
        } else {
            format = ModConstants.FRAME_EXTENSION;
        }
        if (!meta.sizeKnown()) {
            // 服务器只知道有内容、不知道画面多大：JPG 先取一帧回来认尺寸。
            // MP4 没有尺寸就无法建纹理，暂不下载；等服务器补全 meta 后再播。
            if (!"mp4".equals(format)) {
                ClipFetcher.requestFrame(clipId, 0);
            }
            return null;
        }
        int fps = Math.max(1, meta.fps());
        long durationMs = meta.durationMs() > 0L ? meta.durationMs() : (long) meta.frames() * 1000L / fps;
        Clip clip = new Clip(clipId, directory(clipId), meta.width(), meta.height(), meta.frames(), fps,
                VideoProfile.byId(meta.profileId()), format, durationMs);
        clip.remote = true;
        clip.serverVideoBytes = meta.videoBytes();
        return clip;
    }

    /** 把服务器那份名片上的信息并进本地片段：归属、体积，以及缺帧时允许联网取帧。 */
    private static void applyServerInfo(Clip clip, ClipMeta meta) {
        if (meta == null) {
            return;
        }
        clip.owner = meta.owner();
        clip.serverBytes = meta.bytes();
        clip.serverVideoBytes = meta.videoBytes();
        if (clip.created <= 0L && meta.created() > 0L) {
            clip.created = meta.created();
        }
        if (clip.width <= 0 && meta.sizeKnown()) {
            clip.width = meta.width();
            clip.height = meta.height();
        }
        if (clip.fps <= 0 && meta.fps() > 0) {
            clip.fps = meta.fps();
        }
        // 只有「本机一份都没有」或「这是服务器下载的副本」才允许联网补帧；
        // 完整存在于本机的原件一律只读本地，不联网、也不往玩家自己的目录里塞文件。
        if (clip.remote || isServerCopy(clip.id)) {
            clip.remote = true;
            if (meta.frames() > clip.frameCount) {
                clip.frameCount = meta.frames();
            }
        }
    }

    /** 标准片段：meta.json + frame_xxxx.jpg/png。任何一步不对就返回 null，交给按内容识别。 */
    private static Clip loadFromMeta(File dir, String clipId) {
        File metaFile = new File(dir, "meta.json");
        if (!metaFile.isFile() || metaFile.length() > ClipWire.MAX_META_CHARS) {
            return null;
        }
        try {
            JsonObject meta = JsonParser.parseString(Files.readString(metaFile.toPath(), StandardCharsets.UTF_8)).getAsJsonObject();
            int width = meta.get("width").getAsInt();
            int height = meta.get("height").getAsInt();
            int frames = meta.get("frames").getAsInt();
            int fps = Math.max(1, meta.get("fps").getAsInt());
            String profileId = meta.has("profile") ? meta.get("profile").getAsString() : "";
            String format = meta.has("frame_format") ? meta.get("frame_format").getAsString() : ModConstants.FRAME_EXTENSION;
            if ("mp4".equalsIgnoreCase(format)) {
                format = "mp4";
            } else if ("png".equalsIgnoreCase(format)) {
                format = "png";
            } else {
                format = ModConstants.FRAME_EXTENSION;
            }
            if (width <= 0 || height <= 0 || width > ModConstants.MAX_VIDEO_WIDTH || height > ModConstants.MAX_VIDEO_HEIGHT
                    || frames <= 0 || frames > ModConstants.MAX_STORED_FRAMES) {
                return null;
            }
            VideoProfile profile = meta.has("profile") ? VideoProfile.byId(profileId) : VideoProfile.bySize(width, height);
            if (profile == null) {
                profile = VideoProfile.STANDARD;
            }
            long durationMs = meta.has("duration_ms") ? meta.get("duration_ms").getAsLong()
                    : (long) frames * 1000L / fps;
            // MP4 容器：不需要 frame_0000.jpg
            if ("mp4".equalsIgnoreCase(format) || Mp4FrameSource.videoFile(dir) != null) {
                if (!"mp4".equalsIgnoreCase(format)) {
                    format = "mp4";
                }
                return new Clip(clipId, dir, width, height, frames, fps, profile, format, durationMs);
            }
            File first = frameFile(dir, profile, format, 0);
            if (first == null) {
                return null;
            }
            return new Clip(clipId, dir, width, height, frames, fps, profile, format, durationMs);
        } catch (Exception ignored) {
            return null;
        }
    }

    /** 按内容识别：把所有图片按文件名自然排序当帧序列，尺寸以第一帧为准。 */
    private static Clip loadByProbe(File dir, String clipId) {
        File[] images = listSortedImages(dir);
        if (images == null) {
            return null;
        }
        if (images[0].length() > ClipWire.MAX_FRAME_BYTES) {
            return null;
        }
        NativeImage first;
        try {
            first = JpegCodec.readFrame(images[0], Files.readAllBytes(images[0].toPath()));
        } catch (Exception ignored) {
            return null;
        }
        try {
            int width = first.getWidth();
            int height = first.getHeight();
            if (width <= 0 || height <= 0 || width > MAX_PROBE_WIDTH || height > MAX_PROBE_HEIGHT) {
                return null;
            }
            int frames = Math.min(images.length, ModConstants.MAX_STORED_FRAMES);
            VideoProfile profile = VideoProfile.bySize(width, height);
            int fps = profile != null ? profile.fps() : DEFAULT_PROBE_FPS;
            Clip clip = new Clip(clipId, dir, width, height, frames, fps,
                    profile != null ? profile : VideoProfile.STANDARD,
                    ModConstants.FRAME_EXTENSION, (long) frames * 1000L / fps);
            clip.frameFiles = images;
            return clip;
        } finally {
            first.close();
        }
    }

    /** 收集并排序一个文件夹里所有认识的图片文件；一个都没有返回 null。 */
    private static File[] listSortedImages(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<File> images = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && JpegCodec.looksLikeImage(file.getName())) {
                images.add(file);
            }
        }
        if (images.isEmpty()) {
            return null;
        }
        images.sort(ClipStore::compareFrameNames);
        return images.toArray(new File[0]);
    }

    /** 帧文件名自然排序：数字段按数值比，frame_2 排在 frame_10 前面。 */
    private static int compareFrameNames(File a, File b) {
        String x = a.getName();
        String y = b.getName();
        int i = 0;
        int j = 0;
        while (i < x.length() && j < y.length()) {
            char cx = x.charAt(i);
            char cy = y.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int sx = i;
                int sy = j;
                while (i < x.length() && Character.isDigit(x.charAt(i))) {
                    i++;
                }
                while (j < y.length() && Character.isDigit(y.charAt(j))) {
                    j++;
                }
                String dx = stripZeros(x.substring(sx, i));
                String dy = stripZeros(y.substring(sy, j));
                if (dx.length() != dy.length()) {
                    return dx.length() - dy.length();
                }
                int cmp = dx.compareTo(dy);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (cx != cy) {
                    return cx - cy;
                }
                i++;
                j++;
            }
        }
        return (x.length() - i) - (y.length() - j);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    /** 片段编号里就带着录制时刻（clip_<毫秒>_<随机>），解析不出来再退到目录修改时间。 */
    private static long createdMsOf(String clipId, File dir) {
        if (clipId.startsWith("clip_")) {
            String[] parts = clipId.split("_");
            if (parts.length >= 2) {
                try {
                    long millis = Long.parseLong(parts[1]);
                    if (millis > 0L) {
                        return millis;
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return dir.lastModified();
    }

    private static File frameFile(File dir, VideoProfile profile, String format, int index) {
        if (profile != null) {
            File jpg = new File(dir, profile.frameName(index));
            if (jpg.isFile()) {
                return jpg;
            }
            File legacy = new File(dir, profile.legacyFrameName(index));
            if (legacy.isFile()) {
                return legacy;
            }
        }
        File byFormat = new File(dir, String.format("frame_%04d.%s", index, format));
        if (byFormat.isFile()) {
            return byFormat;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            String stem = String.format("frame_%04d.", index);
            for (File file : files) {
                if (file.getName().startsWith(stem) && JpegCodec.looksLikeImage(file.getName())) {
                    return file;
                }
            }
        }
        return null;
    }

    /** 只查缓存，不碰硬盘。 */
    private static NativeImage cachedFrame(String key) {
        synchronized (LOCK) {
            return FRAMES.get(key);
        }
    }

    /** 取第 index 帧的文件：联网补的片段按规范文件名找，其余查排序列表或标准命名。 */
    private static File resolveFrame(Clip clip, int index) {
        if (index < 0) {
            return null;
        }
        if (clip.remote) {
            // 从服务器取帧一律用规范文件名：序号即文件名，跳着下也不会错位。
            File canonical = new File(clip.dir, ClipWire.canonicalFrameName(index));
            if (canonical.isFile()) {
                return canonical;
            }
            File legacy = new File(clip.dir, ClipWire.legacyFrameName(index));
            return legacy.isFile() ? legacy : null;
        }
        if (clip.frameFiles != null) {
            return index < clip.frameFiles.length ? clip.frameFiles[index] : null;
        }
        return frameFile(clip.dir, clip.profile, clip.format, index);
    }

    /** 缓存里没有就当场解码（这一步在锁外做，不挡后台预取），保证这一帧进缓存。 */
    private static void ensureFrame(Clip clip, int index) {
        String key = clip.id + '#' + index;
        if (cachedFrame(key) != null) {
            return;
        }
        // 新格式：整段 MP4
        if (clip.format != null && "mp4".equalsIgnoreCase(clip.format)) {
            try {
                File mp4File = Mp4FrameSource.videoFile(clip.dir);
                if (mp4File == null || !mp4File.isFile()) {
                    // 本机没有：向服务器按块下载
                    long expected = clip.serverVideoBytes > 0 ? clip.serverVideoBytes : 0L;
                    ClipFileFetcher.ensureLocal(clip.id, expected);
                    return;
                }
                if (clip.remote || isServerCopy(clip.id)) {
                    if (ClipFileFetcher.isDownloading(clip.id)) {
                        return;
                    }
                    if (clip.serverVideoBytes > 0 && mp4File.length() != clip.serverVideoBytes) {
                        ClipFileFetcher.ensureLocal(clip.id, clip.serverVideoBytes);
                        return;
                    }
                }
                Mp4FrameSource src;
                synchronized (LOCK) {
                    src = MP4_SOURCES.get(clip.id);
                    if (src == null) {
                        src = new Mp4FrameSource(mp4File);
                        MP4_SOURCES.put(clip.id, src);
                    }
                }
                NativeImage image = src.getFrame(index);
                if (image == null) {
                    return;
                }
                putFrame(key, image);
                // 预取后续 2 帧进 Mp4FrameSource 内部缓存
                try {
                    Mp4FrameSource srcNow;
                    synchronized (LOCK) {
                        srcNow = MP4_SOURCES.get(clip.id);
                    }
                    if (srcNow != null) {
                        srcNow.prefetch(index + 1, 2);
                    }
                } catch (Exception ignored) {
                }
            } catch (Exception e) {
                System.err.println("[ClipStore] mp4 frame " + index + ": " + e.getMessage());
            }
            return;
        }
        File file = resolveFrame(clip, index);
        if (file == null) {
            if (clip.remote) {
                ClipFetcher.requestFrame(clip.id, index);
            }
            return;
        }
        if (!file.isFile() || file.length() > ClipWire.MAX_FRAME_BYTES) {
            return;
        }
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            NativeImage image = JpegCodec.readFrame(file, bytes);
            if (image.getWidth() != clip.width || image.getHeight() != clip.height) {
                image.close();
                return;
            }
            putFrame(key, image);
        } catch (Exception ignored) {
        }
    }

    /** 后台线程先把后面几帧解好，播放时就不会卡在磁盘解码上。 */
    private static void prefetch(Clip clip, int from, int count) {
        ExecutorService queue = decoder;
        if (queue == null || queue.isShutdown()) {
            synchronized (LOCK) {
                queue = decoder;
                if (queue == null || queue.isShutdown()) {
                    queue = Executors.newSingleThreadExecutor(runnable -> {
                        Thread thread = new Thread(runnable, "videorecorder-frame-loader");
                        thread.setDaemon(true);
                        return thread;
                    });
                    decoder = queue;
                }
            }
        }
        final ExecutorService job = queue;
        for (int i = 0; i < count; i++) {
            int index = from + i;
            if (index < 0 || index >= clip.frameCount) {
                continue;
            }
            String key = clip.id + '#' + index;
            boolean submit;
            synchronized (LOCK) {
                submit = !FRAMES.containsKey(key) && PENDING.size() < PREFETCH_LIMIT * 4 && PENDING.add(key);
            }
            if (!submit) {
                continue;
            }
            final int frameIndex = index;
            try {
                job.execute(() -> {
                    try {
                        File file = resolveFrame(clip, frameIndex);
                        if (file == null || !file.isFile()
                                || file.length() > ClipWire.MAX_FRAME_BYTES) {
                            if (clip.remote) {
                                ClipFetcher.requestFrame(clip.id, frameIndex);
                            }
                            return;
                        }
                        byte[] bytes = Files.readAllBytes(file.toPath());
                        NativeImage image = JpegCodec.readFrame(file, bytes);
                        if (image.getWidth() != clip.width || image.getHeight() != clip.height) {
                            image.close();
                            return;
                        }
                        putFrame(key, image);
                    } catch (Exception ignored) {
                    } finally {
                        synchronized (LOCK) {
                            PENDING.remove(key);
                        }
                    }
                });
            } catch (RuntimeException e) {
                synchronized (LOCK) {
                    PENDING.remove(key);
                }
            }
        }
    }

    private static void putFrame(String key, NativeImage image) {
        synchronized (LOCK) {
            NativeImage old = FRAMES.put(key, image);
            if (old != null) {
                frameCacheBytes -= imageBytes(old);
                old.close();
            }
            frameCacheBytes += imageBytes(image);
            while (FRAMES.size() > 1 && (frameCacheBytes > MAX_FRAME_CACHE_BYTES || FRAMES.size() > MAX_FRAME_CACHE_ENTRIES)) {
                Iterator<Map.Entry<String, NativeImage>> it = FRAMES.entrySet().iterator();
                boolean evicted = false;
                while (it.hasNext()) {
                    Map.Entry<String, NativeImage> eldest = it.next();
                    if (eldest.getKey().equals(key)) {
                        // 刚放进来的这一帧不能丢：渲染线程马上就要拷它。
                        break;
                    }
                    it.remove();
                    frameCacheBytes -= imageBytes(eldest.getValue());
                    eldest.getValue().close();
                    evicted = true;
                    break;
                }
                if (!evicted) {
                    break;
                }
            }
        }
    }

    private static long imageBytes(NativeImage image) {
        return (long) image.getWidth() * image.getHeight() * 4L;
    }

    /**
     * 把某一段的第 index 帧铺进显示屏的纹理。
     * 解码在锁外做（慢的那一步不挡别人），拷像素和淘汰缓存都在锁内做，
     * 这样不会出现「后台线程刚把这一帧释放、渲染线程还在拿它喂 GPU」。
     *
     * <p>进度条是直接烤进这张纹理底部的：整面屏幕墙共用同一张纹理，
     * 条形自然在墙内连续，不需要额外的几何面，也不会和画面互相闪。
     * bar 传 null 表示这一帧不画进度条。
     */
    public static boolean showFrame(Clip clip, int index, net.minecraft.client.renderer.texture.DynamicTexture texture,
                                    boolean prefetchNext, BarState bar) {
        if (clip == null || texture == null || index < 0 || index >= clip.frameCount) {
            return false;
        }
        String key = clip.id + '#' + index;
        ensureFrame(clip, index);
        synchronized (LOCK) {
            NativeImage image = FRAMES.get(key);
            if (image == null) {
                return false;
            }
            NativeImage pixels = texture.getPixels();
            if (pixels == null) {
                return false;
            }
            pixels.copyFrom(image);
            if (bar != null) {
                bakeBar(pixels, bar);
            }
        }
        texture.upload();
        if (prefetchNext) {
            // 联网补帧的片段要多预备几帧：等服务器回传的那点时间不会被看成卡顿。
            prefetch(clip, index + 1, clip.remote ? PREFETCH_LIMIT * 4 : PREFETCH_LIMIT);
        }
        return true;
    }

    /** 进度条的样子：走到整卷的哪一步（0~1）、条占多少个像素高、是否处于暂停。 */
    public record BarState(float progress, int heightPx, boolean paused) {
    }

    /** 在画面纹理的最底部画一条进度条：深色轨道 + 亮色已播部分 + 白色滑块 + 顶部描边。 */
    private static void bakeBar(NativeImage pixels, BarState bar) {
        int width = pixels.getWidth();
        int height = pixels.getHeight();
        int barH = Math.max(1, Math.min(bar.heightPx(), height / 4));
        int top = height - barH;
        float progress = Math.min(1.0F, Math.max(0.0F, bar.progress()));
        int filled = Math.round(progress * width);
        // NativeImage 的 int 像素按小端打包：低字节是 R，所以这里手工拼 A/B/G/R。
        int track = pack(32, 34, 38);
        int fill = bar.paused() ? pack(208, 156, 42) : pack(62, 188, 96);
        int edge = pack(8, 8, 10);
        int thumb = pack(238, 240, 242);
        for (int y = top; y < height; y++) {
            for (int x = 0; x < width; x++) {
                pixels.setPixelRGBA(x, y, x < filled ? fill : track);
            }
        }
        if (top > 0) {
            for (int x = 0; x < width; x++) {
                pixels.setPixelRGBA(x, top - 1, edge);
            }
        }
        // 滑块：跟着进度走的白色小竖条，比轨道略高一点，方便一眼看到播到哪了。
        // 条变细以后，滑块只向上多出 1 像素、最宽 2 像素，免得小条被滑块盖掉。
        int thumbW = Math.max(1, Math.min(2, width / 64));
        int mx = Math.round(progress * (width - 1));
        int left = Math.min(width - thumbW, Math.max(0, mx - thumbW / 2));
        for (int y = Math.max(0, top - 1); y < height; y++) {
            for (int x = left; x < left + thumbW && x < width; x++) {
                pixels.setPixelRGBA(x, y, thumb);
            }
        }
    }

    private static int pack(int r, int g, int b) {
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    /** 一个已经读进内存的片段：只有尺寸和帧数这些信息，帧本体按需解码。 */
    public static final class Clip {
        public final String id;
        public int width;
        public int height;
        public int frameCount;
        public int fps;
        public final VideoProfile profile;
        /** 录制时长（毫秒），来自 meta.json，缺失时按帧数折算。 */
        public final long durationMs;
        /** 录制时刻（Unix 毫秒），给录像库排序和显示用；不进解码缓存。 */
        public long created;
        /** 本机一份文件都没有（或只下到一部分），画面要从服务器按需取帧。 */
        public boolean remote;
        /** 服务器上有这段时它属于谁；空串表示服务器上没有、或服务器目录里手动放的没记归属。 */
        public String owner = "";
        /** 服务器记录的体积，本机没文件时用它显示占用。 */
        public long serverBytes;
        /** 服务器上 video.mp4 的精确字节数；用来判断下载是否完整。 */
        public long serverVideoBytes;
        private final File dir;
        private final String format;

        /** jpg / png / mp4 */
        public String format() {
            return format;
        }
        /** 按内容识别的片段：帧文件按名字排好序直接存下来；标准片段是 null。 */
        private File[] frameFiles;

        private Clip(String id, File dir, int width, int height, int frameCount, int fps, VideoProfile profile,
                     String format, long durationMs) {
            this.id = id;
            this.dir = dir;
            this.width = width;
            this.height = height;
            this.frameCount = frameCount;
            this.fps = Math.max(1, fps);
            this.profile = profile;
            this.format = format;
            this.durationMs = Math.max(0L, durationMs);
        }

        /** 本机有文件就报本机体积，一个字节都没有就报服务器那份的。 */
        public long sizeBytes() {
            long local = totalSize(id);
            return local > 0L ? local : Math.max(0L, serverBytes);
        }

        /** 播放进度（0~1）：按已缓存到本机的帧数算，用来在界面上显示下载进度。 */
        public float cachedRatio() {
            if (frameCount <= 0) {
                return 0.0F;
            }
            int have;
            synchronized (LOCK) {
                have = 0;
                for (String key : FRAMES.keySet()) {
                    if (key.startsWith(id + '#')) {
                        have++;
                    }
                }
            }
            have = Math.max(have, countLocalImages(dir));
            return Math.min(1.0F, have / (float) frameCount);
        }
    }
}
