package cn.blockforge.generated.mod38575362;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraftforge.fml.loading.FMLPaths;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 服务器端录像库。
 * <p>
 * 支持两种上传形态（同一套 Begin / writeFrame / End 网络包）：
 * <ul>
 *   <li>旧：按帧 JPG（index=帧号，data=图片字节）</li>
 *   <li>新：整文件 MP4（index=块序号，data=文件块，meta 含 frame_format=mp4 / upload_mode=file）</li>
 * </ul>
 */
public final class ServerClipStore {
    public static final int GRANT_ACCEPT = 1;
    public static final int GRANT_HAVE = 2;
    public static final int GRANT_DENY = 0;

    private static final long LIST_TTL_MS = 1500L;
    private static final int MAX_LISTED = 200;
    /** 上传会话超时：留足大文件上传时间。 */
    private static final long SESSION_TTL_MS = 3_600_000L;
    /** 文件模式下允许的最大块数（约 4TB），实际上限由服务器磁盘决定。 */
    public static final int MAX_FILE_CHUNKS = 16_777_216;

    private static final Object LOCK = new Object();
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    private static File root;
    private static List<ClipMeta> cachedList;
    private static long cachedAt;

    private ServerClipStore() {
    }

    public static File root() {
        if (root == null) {
            Path base;
            try {
                base = FMLPaths.GAMEDIR.get();
            } catch (Throwable ignored) {
                base = Path.of("").toAbsolutePath();
            }
            if (base == null) {
                base = Path.of("").toAbsolutePath();
            }
            root = new File(base.toFile(), "videorecorder_clips");
        }
        return root;
    }

    public static File dirOf(String clip) {
        return new File(root(), clip);
    }

    /** 服务端是否已有完整可播内容（JPG 帧 或 video.mp4）。 */
    public static boolean has(String clip) {
        File dir = dirOf(clip);
        if (hasCompleteMp4(dir)) {
            return true;
        }
        return frameCountOf(dir) > 0;
    }

    private static boolean hasCompleteMp4(File dir) {
        File mp4 = new File(dir, "video.mp4");
        File meta = new File(dir, "meta.json");
        return mp4.isFile() && mp4.length() > 0 && meta.isFile();
    }

    public static List<ClipMeta> list() {
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (cachedList != null && now - cachedAt < LIST_TTL_MS) {
                return new ArrayList<>(cachedList);
            }
        }
        List<ClipMeta> scanned = scan();
        synchronized (LOCK) {
            cachedList = scanned;
            cachedAt = now;
        }
        return new ArrayList<>(scanned);
    }

    public static ClipMeta find(String clip) {
        for (ClipMeta meta : list()) {
            if (meta.id().equals(clip)) {
                return meta;
            }
        }
        return null;
    }

    public static void invalidate() {
        synchronized (LOCK) {
            cachedList = null;
            cachedAt = 0L;
        }
    }

    private static List<ClipMeta> scan() {
        List<ClipMeta> result = new ArrayList<>();
        File[] folders = root().listFiles(File::isDirectory);
        if (folders == null) {
            return result;
        }
        Arrays.sort(folders, Comparator.comparingLong(File::lastModified));
        for (File folder : folders) {
            ClipMeta meta = metaOf(folder.getName(), folder);
            if (meta != null) {
                result.add(meta);
            }
        }
        result.sort(Comparator.comparingLong(ClipMeta::created).reversed());
        if (result.size() > MAX_LISTED) {
            return new ArrayList<>(result.subList(0, MAX_LISTED));
        }
        return result;
    }

    public static ClipMeta metaOf(String clip, File dir) {
        if (dir == null || !dir.isDirectory() || !ClipWire.isSafeClipName(clip)) {
            return null;
        }
        int width = 0;
        int height = 0;
        int frames = -1;
        int fps = 12;
        long durationMs = 0L;
        String profileId = "";
        File metaFile = new File(dir, "meta.json");
        if (metaFile.isFile() && metaFile.length() <= ClipWire.MAX_META_CHARS) {
            try {
                JsonObject json = JsonParser.parseString(
                        new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
                width = json.has("width") ? json.get("width").getAsInt() : 0;
                height = json.has("height") ? json.get("height").getAsInt() : 0;
                frames = json.has("frames") ? json.get("frames").getAsInt() : -1;
                fps = json.has("fps") ? Math.max(1, json.get("fps").getAsInt()) : 12;
                durationMs = json.has("duration_ms") ? Math.max(0L, json.get("duration_ms").getAsLong()) : 0L;
                profileId = json.has("profile") ? json.get("profile").getAsString() : "";
            } catch (Exception ignored) {
            }
        }
        long countedImages = frameCountOf(dir);
        boolean mp4 = hasCompleteMp4(dir);
        if (frames < 0) {
            frames = (int) countedImages;
        }
        // 仅有 MP4、meta 帧数可信时用 meta；两边都没有则无效
        if (frames <= 0 && !mp4) {
            return null;
        }
        if (frames <= 0 && mp4) {
            frames = 1; // 至少占位，客户端以本地解码为准
        }
        width = boundValue(width, 0, ModConstants.MAX_VIDEO_WIDTH);
        height = boundValue(height, 0, ModConstants.MAX_VIDEO_HEIGHT);
        frames = boundValue(frames, 0, ModConstants.MAX_STORED_FRAMES);
        fps = boundValue(fps, 1, 240);
        long size = sizeOf(dir);
        File video = new File(dir, "video.mp4");
        long videoBytes = mp4 && video.isFile() ? video.length() : 0L;
        String format = mp4 ? "mp4" : ModConstants.FRAME_EXTENSION;
        long created = metaFile.isFile() ? metaFile.lastModified() : dir.lastModified();
        try {
            if (metaFile.isFile()) {
                JsonObject json = JsonParser.parseString(
                        new String(Files.readAllBytes(metaFile.toPath()), StandardCharsets.UTF_8)).getAsJsonObject();
                if (json.has("created_ms")) {
                    created = Math.max(0L, json.get("created_ms").getAsLong());
                }
            }
        } catch (Exception ignored) {
        }
        String owner = readOwner(dir);
        if (durationMs <= 0L && fps > 0) {
            durationMs = frames * 1000L / fps;
        }
        return new ClipMeta(clip, width, height, frames, fps, durationMs, size, videoBytes,
                created, owner, profileId, format);
    }

    private static long frameCountOf(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long count = 0L;
        for (File file : files) {
            if (file.isFile() && ClipWire.looksLikeImage(file.getName())) {
                count++;
            }
        }
        return count;
    }

    private static long sizeOf(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return 0L;
        }
        long bytes = 0L;
        for (File file : files) {
            if (file.isFile() && !file.getName().equals("owner.txt")) {
                bytes += file.length();
            }
        }
        return bytes;
    }

    private static String readOwner(File dir) {
        File owner = new File(dir, "owner.txt");
        if (!owner.isFile()) {
            return "";
        }
        try {
            return new String(Files.readAllBytes(owner.toPath()), StandardCharsets.UTF_8).trim();
        } catch (Exception ignored) {
            return "";
        }
    }

    public static long totalBytes() {
        long bytes = 0L;
        File[] folders = root().listFiles(File::isDirectory);
        if (folders != null) {
            for (File folder : folders) {
                bytes += sizeOf(folder);
            }
        }
        return bytes;
    }

    public static byte[] readFrame(String clip, int index) {
        if (!ClipWire.isSafeClipName(clip) || index < 0) {
            return null;
        }
        File dir = dirOf(clip);
        if (!dir.isDirectory()) {
            return null;
        }
        File file = new File(dir, ClipWire.canonicalFrameName(index));
        if (!file.isFile()) {
            File legacy = new File(dir, ClipWire.legacyFrameName(index));
            file = legacy.isFile() ? legacy : nthImage(dir, index);
        }
        if (file == null || !file.isFile() || file.length() > ClipWire.MAX_FRAME_BYTES) {
            return null;
        }
        try {
            return Files.readAllBytes(file.toPath());
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * 按块读取 video.mp4（供后续客户端下载用）。
     * chunkSize 建议与客户端上传块大小一致（如 256KB）。
     */
    public static byte[] readFileChunk(String clip, int chunkIndex, int chunkSize) {
        if (!ClipWire.isSafeClipName(clip) || chunkIndex < 0 || chunkSize <= 0
                || chunkSize > ClipWire.MAX_FRAME_BYTES) {
            return null;
        }
        File mp4 = new File(dirOf(clip), "video.mp4");
        if (!mp4.isFile()) {
            return null;
        }
        long offset = (long) chunkIndex * chunkSize;
        if (offset >= mp4.length()) {
            return null;
        }
        int len = (int) Math.min(chunkSize, mp4.length() - offset);
        byte[] buf = new byte[len];
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(mp4, "r")) {
            raf.seek(offset);
            raf.readFully(buf);
            return buf;
        } catch (Exception ignored) {
            return null;
        }
    }

    public static long fileSizeOf(String clip) {
        if (!ClipWire.isSafeClipName(clip)) {
            return 0L;
        }
        File mp4 = new File(dirOf(clip), "video.mp4");
        return mp4.isFile() ? mp4.length() : 0L;
    }

    private static File nthImage(File dir, int index) {
        File[] files = dir.listFiles();
        if (files == null) {
            return null;
        }
        List<File> images = new ArrayList<>();
        for (File file : files) {
            if (file.isFile() && ClipWire.looksLikeImage(file.getName())) {
                images.add(file);
            }
        }
        images.sort((a, b) -> ClipWire.compareFrameNames(a.getName(), b.getName()));
        return index < images.size() ? images.get(index) : null;
    }

    // ---------------------------------------------------------------- 上传会话

    private static final class Session {
        private final String owner;
        private final String metaJson;
        /** 帧数（JPG）或块数（MP4）。 */
        private final int frames;
        private final BitSet received = new BitSet();
        private long bytes;
        private long lastMs;
        private final boolean fileMode;
        private final long expectedFileSize;

        private Session(String owner, String metaJson, int frames, long bytes,
                        boolean fileMode, long expectedFileSize) {
            this.owner = owner;
            this.metaJson = metaJson;
            this.frames = frames;
            this.bytes = bytes;
            this.lastMs = System.currentTimeMillis();
            this.fileMode = fileMode;
            this.expectedFileSize = expectedFileSize;
        }
    }

    public static int beginUpload(String clip, String requester, String metaJson, int frames,
                                  String[] reasonOut) {
        if (!ClipWire.isSafeClipName(clip)) {
            reasonOut[0] = "这段录像的文件夹名字不能被服务器接受";
            return GRANT_DENY;
        }
        String safeMeta = sanitizeMeta(metaJson);
        if (safeMeta == null) {
            reasonOut[0] = "录像的元数据不合格式，服务器不收";
            return GRANT_DENY;
        }
        boolean fileMode = isFileUploadMeta(safeMeta);
        int maxUnits = fileMode ? MAX_FILE_CHUNKS : ModConstants.MAX_STORED_FRAMES;
        if (frames <= 0 || frames > maxUnits) {
            reasonOut[0] = fileMode ? "文件块数不在允许范围内" : "帧数不在允许范围内";
            return GRANT_DENY;
        }
        long expectedSize = readFileSizeFromMeta(safeMeta);
        if (fileMode && expectedSize > ClipWire.MAX_CLIP_BYTES) {
            reasonOut[0] = "MP4 超过单段上限";
            return GRANT_DENY;
        }
        synchronized (LOCK) {
            pruneSessions();
            Session running = SESSIONS.get(clip);
            if (running != null) {
                if (!running.owner.equals(requester)) {
                    reasonOut[0] = "服务器正在接收别人上传的同名录像";
                    return GRANT_DENY;
                }
                return GRANT_ACCEPT;
            }
            File dir = dirOf(clip);
            if (hasCompleteMp4(dir) || frameCountOf(dir) > 0L) {
                if (!new File(dir, "meta.json").isFile()) {
                    clearFiles(dir);
                } else {
                    String owner = readOwner(dir);
                    if (!owner.isEmpty() && !owner.equals(requester)) {
                        reasonOut[0] = "服务器上这个名字已经是 " + owner + " 的录像了";
                        return GRANT_DENY;
                    }
                    return GRANT_HAVE;
                }
            }
            if (totalBytes() > ClipWire.MAX_SERVER_BYTES) {
                reasonOut[0] = "服务器录像库已满（超过 1 GB）";
                return GRANT_DENY;
            }
            SESSIONS.put(clip, new Session(requester, safeMeta, frames, 0L, fileMode, expectedSize));
            return GRANT_ACCEPT;
        }
    }

    public static boolean writeFrame(String clip, String requester, int index, byte[] data) {
        if (data == null || data.length == 0 || data.length > ClipWire.MAX_FRAME_BYTES
                || index < 0 || !ClipWire.isSafeClipName(clip)) {
            return false;
        }
        Session session;
        synchronized (LOCK) {
            session = SESSIONS.get(clip);
            if (session == null || !session.owner.equals(requester) || index >= session.frames
                    || System.currentTimeMillis() - session.lastMs > SESSION_TTL_MS) {
                return false;
            }
            if (session.received.get(index)) {
                session.lastMs = System.currentTimeMillis();
            } else {
                if (session.bytes + data.length > ClipWire.MAX_CLIP_BYTES) {
                    SESSIONS.remove(clip);
                    return false;
                }
                session.received.set(index);
                session.bytes += data.length;
                session.lastMs = System.currentTimeMillis();
            }
        }
        File dir = dirOf(clip);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return false;
        }
        try {
            if (session.fileMode) {
                Files.write(new File(dir, partName(index)).toPath(), data);
                return true;
            }
            if (index > ModConstants.MAX_STORED_FRAMES) {
                return false;
            }
            Files.write(new File(dir, ClipWire.canonicalFrameName(index)).toPath(), data);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static String partName(int index) {
        return "video.mp4.part." + index;
    }

    public static ClipMeta endUpload(String clip, String requester) {
        Session session;
        synchronized (LOCK) {
            session = SESSIONS.remove(clip);
        }
        if (session == null || !session.owner.equals(requester)
                || session.received.cardinality() != session.frames) {
            return null;
        }
        File dir = dirOf(clip);
        if (!dir.isDirectory() && !dir.mkdirs()) {
            return null;
        }
        try {
            if (session.fileMode) {
                File out = new File(dir, "video.mp4");
                try (OutputStream os = new FileOutputStream(out)) {
                    for (int i = 0; i < session.frames; i++) {
                        File part = new File(dir, partName(i));
                        if (!part.isFile()) {
                            // 缺块：清理残留
                            clearPartFiles(dir);
                            if (out.exists()) {
                                out.delete();
                            }
                            return null;
                        }
                        Files.copy(part.toPath(), os);
                        part.delete();
                    }
                }
                if (session.expectedFileSize > 0 && out.length() != session.expectedFileSize) {
                    // 允许轻微差异？严格则失败。这里严格匹配。
                    out.delete();
                    clearPartFiles(dir);
                    return null;
                }
            }
            Files.writeString(new File(dir, "meta.json").toPath(), session.metaJson, StandardCharsets.UTF_8);
            Files.writeString(new File(dir, "owner.txt").toPath(), requester, StandardCharsets.UTF_8);
        } catch (Exception ignored) {
            return null;
        }
        invalidate();
        return find(clip);
    }

    public static void cancelUpload(String clip) {
        synchronized (LOCK) {
            SESSIONS.remove(clip);
        }
    }

    public static boolean deleteClip(String clip, String requester) {
        if (!ClipWire.isSafeClipName(clip)) {
            return false;
        }
        File dir = dirOf(clip);
        if (!dir.isDirectory()) {
            return false;
        }
        String owner = readOwner(dir);
        if (!owner.isEmpty() && !owner.equals(requester)) {
            return false;
        }
        synchronized (LOCK) {
            SESSIONS.remove(clip);
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
        boolean gone = dir.delete();
        invalidate();
        return gone;
    }

    private static String sanitizeMeta(String raw) {
        if (raw == null || raw.isEmpty() || raw.length() > ClipWire.MAX_META_CHARS) {
            return null;
        }
        try {
            JsonObject json = JsonParser.parseString(raw).getAsJsonObject();
            JsonObject clean = new JsonObject();
            clean.addProperty("width", bound(json, "width", 0, ModConstants.MAX_VIDEO_WIDTH));
            clean.addProperty("height", bound(json, "height", 0, ModConstants.MAX_VIDEO_HEIGHT));
            clean.addProperty("frames", bound(json, "frames", 0, ModConstants.MAX_STORED_FRAMES));
            clean.addProperty("fps", bound(json, "fps", 1, 240));
            clean.addProperty("duration_ms", Math.max(0L,
                    json.has("duration_ms") ? json.get("duration_ms").getAsLong() : 0L));
            String profile = json.has("profile") ? json.get("profile").getAsString() : "";
            clean.addProperty("profile", profile.length() > 32 ? "" : profile);

            String fmt = ModConstants.FRAME_EXTENSION;
            if (json.has("frame_format")) {
                String f = json.get("frame_format").getAsString();
                if ("mp4".equals(f) || "jpg".equals(f) || "jpeg".equals(f) || "png".equals(f)) {
                    fmt = "jpeg".equals(f) ? "jpg" : f;
                }
            }
            clean.addProperty("frame_format", fmt);

            if ("mp4".equals(fmt) || (json.has("upload_mode") && "file".equals(json.get("upload_mode").getAsString()))) {
                clean.addProperty("upload_mode", "file");
                clean.addProperty("container", "video.mp4");
                long fs = 0L;
                if (json.has("file_size")) {
                    fs = Math.max(0L, Math.min(ClipWire.MAX_CLIP_BYTES, json.get("file_size").getAsLong()));
                }
                clean.addProperty("file_size", fs);
            }

            clean.addProperty("created_ms", Math.max(0L,
                    json.has("created_ms") ? json.get("created_ms").getAsLong() : System.currentTimeMillis()));
            return clean.toString();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isFileUploadMeta(String safeMeta) {
        return safeMeta != null && (safeMeta.contains("\"upload_mode\":\"file\"")
                || safeMeta.contains("\"frame_format\":\"mp4\""));
    }

    private static long readFileSizeFromMeta(String safeMeta) {
        if (safeMeta == null) {
            return 0L;
        }
        try {
            JsonObject json = JsonParser.parseString(safeMeta).getAsJsonObject();
            if (json.has("file_size")) {
                return Math.max(0L, json.get("file_size").getAsLong());
            }
        } catch (Exception ignored) {
        }
        return 0L;
    }

    private static int boundValue(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int bound(JsonObject json, String key, int min, int max) {
        if (!json.has(key)) {
            return min;
        }
        try {
            return Math.min(max, Math.max(min, json.get(key).getAsInt()));
        } catch (Exception ignored) {
            return min;
        }
    }

    private static void clearFiles(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isFile()) {
                    file.delete();
                }
            }
        }
    }

    private static void clearPartFiles(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File file : files) {
            if (file.isFile() && file.getName().startsWith("video.mp4.part.")) {
                file.delete();
            }
        }
    }

    private static void pruneSessions() {
        long now = System.currentTimeMillis();
        SESSIONS.entrySet().removeIf(entry -> now - entry.getValue().lastMs > SESSION_TTL_MS);
    }
}
