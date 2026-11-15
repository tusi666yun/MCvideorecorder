package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.ModNetworking;
import cn.blockforge.generated.mod38575362.ServerClipStore;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;

import java.io.File;
import java.io.RandomAccessFile;
import java.util.ArrayDeque;

/**
 * 把本机录像上传到服务器。
 * <ul>
 *   <li>旧格式：按帧上传 JPG</li>
 *   <li>新格式：把 video.mp4 按块上传（复用同一套 Begin/Frame/End 网络包，index=块序号）</li>
 * </ul>
 */
public final class ClipUploader {
    /** 每刻最多发几包。 */
    private static final int PACKETS_PER_TICK = 3;
    /** 每块大小（须 ≤ ClipWire.MAX_FRAME_BYTES）。 */
    private static final int CHUNK_SIZE = 256 * 1024;
    private static final long GRANT_TIMEOUT_MS = 120_000L;
    private static final int QUEUE_LIMIT = 64;

    private static String clip = "";
    private static ClipStore.Clip data;
    private static String metaJson = "";
    private static int totalUnits;   // 帧数 或 块数
    private static int nextIndex;
    private static boolean waitingGrant;
    private static boolean sending;
    private static boolean fileMode; // true = 传 MP4 文件
    private static File filePath;
    private static long fileSize;
    private static long stageSinceMs;
    private static final ArrayDeque<String> WAITING = new ArrayDeque<>();

    private ClipUploader() {
    }

    public static synchronized String uploading() {
        return clip;
    }

    public static synchronized int uploadedFrames() {
        return nextIndex;
    }

    public static synchronized int uploadTotal() {
        return totalUnits;
    }

    public static void start(String clipId) {
        if (!ClipWire.isSafeClipName(clipId)) {
            return;
        }
        if (Minecraft.getInstance().isSingleplayer()) {
            return;
        }
        if (Minecraft.getInstance().getConnection() == null) {
            return;
        }
        synchronized (ClipUploader.class) {
            if (clipId.equals(clip) || WAITING.contains(clipId)) {
                return;
            }
            if (!clip.isEmpty() || WAITING.size() >= QUEUE_LIMIT) {
                WAITING.addLast(clipId);
                return;
            }
            begin(clipId);
        }
    }

    public static void onAskedByServer(String clipId) {
        if (!ClipStore.hasLocal(clipId)) {
            return;
        }
        start(clipId);
    }

    private static void begin(String clipId) {
        ClipStore.Clip local = ClipStore.localClip(clipId);
        if (local == null || local.frameCount <= 0) {
            return;
        }
        File dir = new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips/" + clipId);
        File mp4 = Mp4FrameSource.videoFile(dir);
        // 有 video.mp4 就走文件块上传；否则仍按 JPG 逐帧上传（兼容旧录像）
        boolean isMp4 = mp4 != null && mp4.isFile() && mp4.length() > 0;

        clip = clipId;
        data = local;
        nextIndex = 0;
        waitingGrant = true;
        sending = false;
        stageSinceMs = System.currentTimeMillis();

        if (isMp4) {
            fileMode = true;
            filePath = mp4;
            fileSize = mp4.length();
            if (fileSize > ClipWire.MAX_CLIP_BYTES) {
                finish();
                notify("MP4 超过服务器单段上限（" + (ClipWire.MAX_CLIP_BYTES / 1024 / 1024) + " MB），无法上传");
                return;
            }
            totalUnits = (int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
            if (totalUnits <= 0) {
                finish();
                return;
            }
            // meta 里标明容器与文件大小，供服务端 sanitize / 会话使用
            metaJson = buildMp4Meta(local, fileSize);
        } else {
            fileMode = false;
            filePath = null;
            fileSize = 0;
            totalUnits = Math.min(local.frameCount, ModConstants.MAX_STORED_FRAMES);
            metaJson = ClipStore.buildMeta(local);
        }
        // 第二个参数对服务端仍叫 frames：文件模式下表示「块数」
        ModNetworking.beginUpload(clipId, metaJson, totalUnits);
    }

    private static String buildMp4Meta(ClipStore.Clip local, long bytes) {
        return String.format(java.util.Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"frames\":%d,\"fps\":%d,\"duration_ms\":%d,"
                        + "\"profile\":\"%s\",\"frame_format\":\"mp4\",\"container\":\"video.mp4\","
                        + "\"file_size\":%d,\"upload_mode\":\"file\"}",
                local.width, local.height, local.frameCount, local.fps, local.durationMs,
                local.profile != null ? local.profile.id() : "standard", bytes);
    }

    public static void onGrant(String clipId, int code, String reason) {
        synchronized (ClipUploader.class) {
            if (!clipId.equals(clip)) {
                return;
            }
            if (waitingGrant) {
                if (code == ServerClipStore.GRANT_ACCEPT) {
                    waitingGrant = false;
                    sending = true;
                    nextIndex = 0;
                    stageSinceMs = System.currentTimeMillis();
                    return;
                }
                if (code == ServerClipStore.GRANT_HAVE) {
                    finish();
                    return;
                }
                finish();
                if (reason != null && !reason.isEmpty()) {
                    notify("服务器没有收下「" + clipId + "」：" + reason);
                }
                return;
            }
            if (sending && code != ServerClipStore.GRANT_ACCEPT) {
                finish();
                notify("「" + clipId + "」的上传被服务器中断："
                        + (reason == null || reason.isEmpty() ? "服务端不要了" : reason));
            }
        }
    }

    public static void tick() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        synchronized (ClipUploader.class) {
            if (clip.isEmpty()) {
                if (!WAITING.isEmpty()) {
                    String next = WAITING.pollFirst();
                    if (next != null) {
                        begin(next);
                    }
                }
                return;
            }
            long now = System.currentTimeMillis();
            if (waitingGrant) {
                if (now - stageSinceMs > GRANT_TIMEOUT_MS) {
                    finish();
                    notify("上传超时：连不上服务器的录像库");
                }
                return;
            }
            if (!sending) {
                return;
            }
            int sent = 0;
            while (sending && nextIndex < totalUnits && sent < PACKETS_PER_TICK) {
                byte[] bytes;
                if (fileMode) {
                    bytes = readChunk(filePath, nextIndex);
                } else {
                    bytes = ClipStore.readFrameBytes(data, nextIndex);
                }
                if (bytes == null) {
                    finish();
                    notify("本地文件不全，这段录像的上传已中断");
                    return;
                }
                ModNetworking.uploadFrame(clip, nextIndex, bytes);
                nextIndex++;
                sent++;
            }
            if (nextIndex >= totalUnits) {
                String done = clip;
                int units = totalUnits;
                boolean wasFile = fileMode;
                ModNetworking.endUpload(done);
                finish();
                notify("「" + done + "」" + (wasFile ? "文件块" : "帧") + "已发完，等待服务器入库（" + units + "）");
            }
        }
    }

    private static byte[] readChunk(File file, int chunkIndex) {
        if (file == null || !file.isFile()) {
            return null;
        }
        long offset = (long) chunkIndex * CHUNK_SIZE;
        if (offset >= file.length()) {
            return null;
        }
        int len = (int) Math.min(CHUNK_SIZE, file.length() - offset);
        byte[] buf = new byte[len];
        try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
            raf.seek(offset);
            raf.readFully(buf);
            return buf;
        } catch (Exception e) {
            return null;
        }
    }

    private static void finish() {
        clip = "";
        data = null;
        metaJson = "";
        totalUnits = 0;
        nextIndex = 0;
        waitingGrant = false;
        sending = false;
        fileMode = false;
        filePath = null;
        fileSize = 0;
        stageSinceMs = 0L;
    }

    public static synchronized void cancel() {
        finish();
        WAITING.clear();
    }

    private static void notify(String text) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player != null) {
            minecraft.player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GREEN), true);
        }
    }
}
