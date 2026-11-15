package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 从服务器按块下载 video.mp4 到本机 videorecorder_clips/&lt;id&gt;/。
 * <p>
 * 网络层复用「帧请求/帧回包」：在服务端若存在 video.mp4，则 index 表示块号，
 * data 为文件块（见 ModNetworking 补丁）。块大小与上传一致：256KB。
 * <p>
 * 下载完成后先写临时文件，再原子替换 video.mp4，避免解码器读到半截文件。
 */
public final class ClipFileFetcher {
    public static final int CHUNK_SIZE = 256 * 1024;
    private static final int MAX_INFLIGHT = 4;
    private static final int SENDS_PER_TICK = 3;
    private static final long RESEND_MS = 8000L;
    private static final int MAX_ATTEMPTS = 5;
    private static final long JOB_TIMEOUT_MS = 5L * 60L * 1000L;

    private static final class Job {
        final String clip;
        final long expectedSize; // 0 = 未知，收到空块后结束
        int nextToRequest;
        int nextToWrite; // 连续已落盘的下一块号
        final Set<Integer> received = new HashSet<>();
        final Set<Integer> retry = new HashSet<>();
        final Set<Integer> inflight = new HashSet<>();
        final Map<Integer, Long> inflightAt = new HashMap<>();
        final Map<Integer, Integer> attempts = new HashMap<>();
        long lastActivityMs = System.currentTimeMillis();
        boolean finished;
        boolean failed;

        Job(String clip, long expectedSize) {
            this.clip = clip;
            this.expectedSize = expectedSize;
        }

        int totalChunks() {
            if (expectedSize <= 0) {
                return Integer.MAX_VALUE; // 未知时一直要，直到空包
            }
            long total = (expectedSize + CHUNK_SIZE - 1) / CHUNK_SIZE;
            return (int) Math.min(Integer.MAX_VALUE, total);
        }
    }

    private static final Map<String, Job> JOBS = new HashMap<>();

    private ClipFileFetcher() {
    }

    /** 是否正在下载该片段的 MP4。 */
    public static synchronized boolean isDownloading(String clip) {
        Job j = JOBS.get(clip);
        return j != null && !j.finished && !j.failed;
    }

    /**
     * 确保本机有 video.mp4。若已有完整文件则忽略。
     *
     * @param expectedSize video.mp4 的精确字节数，未知可传 0
     */
    public static synchronized void ensureLocal(String clip, long expectedSize) {
        if (!ClipWire.isSafeClipName(clip)) {
            return;
        }
        File dir = new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips/" + clip);
        File mp4 = new File(dir, "video.mp4");
        if (mp4.isFile() && mp4.length() > 0) {
            if (expectedSize <= 0 || mp4.length() == expectedSize) {
                return;
            }
        }
        Job existing = JOBS.get(clip);
        if (existing != null && !existing.finished && !existing.failed) {
            return;
        }
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        // 只清理自己下载过的半截文件，避免误删玩家本机录制的原件。
        if (mp4.isFile() && expectedSize > 0 && mp4.length() != expectedSize
                && new File(dir, ClipWire.netMarker()).isFile()) {
            mp4.delete();
        }
        clearParts(dir);
        // 先关掉可能还开着旧文件/半截文件的解码器，Windows 上才能原子替换。
        ClipStore.dropCached(clip);
        JOBS.put(clip, new Job(clip, Math.max(0L, expectedSize)));
    }

    /** 客户端 tick：发请求、处理超时。需在 ClientForgeEvents 里与 ClipFetcher.tick 一起调用。 */
    public static synchronized void tick() {
        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, Job>> jobs = JOBS.entrySet().iterator();
        while (jobs.hasNext()) {
            Job job = jobs.next().getValue();
            if (job.finished || job.failed) {
                jobs.remove();
                continue;
            }
            if (now - job.lastActivityMs > JOB_TIMEOUT_MS) {
                failJob(job);
                jobs.remove();
                continue;
            }
            expireInflight(job, now);
            if (job.failed) {
                jobs.remove();
                continue;
            }

            int sent = 0;
            // 先补发丢掉的块，再按顺序请求新块。
            Iterator<Integer> retries = job.retry.iterator();
            while (retries.hasNext() && job.inflight.size() < MAX_INFLIGHT && sent < SENDS_PER_TICK) {
                int idx = retries.next();
                retries.remove();
                if (job.received.contains(idx) || job.inflight.contains(idx)) {
                    continue;
                }
                if (!send(job, idx, now)) {
                    break;
                }
                sent++;
            }
            if (job.failed) {
                jobs.remove();
                continue;
            }

            int total = job.totalChunks();
            while (job.inflight.size() < MAX_INFLIGHT && sent < SENDS_PER_TICK
                    && job.nextToRequest < total) {
                int idx = job.nextToRequest;
                job.nextToRequest++;
                if (job.received.contains(idx) || job.inflight.contains(idx) || job.retry.contains(idx)) {
                    continue;
                }
                if (!send(job, idx, now)) {
                    break;
                }
                sent++;
            }
        }
    }

    private static void expireInflight(Job job, long now) {
        Iterator<Map.Entry<Integer, Long>> it = job.inflightAt.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Integer, Long> entry = it.next();
            if (now - entry.getValue() <= RESEND_MS) {
                continue;
            }
            int index = entry.getKey();
            it.remove();
            job.inflight.remove(index);
            if (job.attempts.getOrDefault(index, 0) >= MAX_ATTEMPTS) {
                failJob(job);
                return;
            }
            job.retry.add(index);
        }
    }

    private static boolean send(Job job, int index, long now) {
        if (job.attempts.getOrDefault(index, 0) >= MAX_ATTEMPTS) {
            failJob(job);
            return false;
        }
        job.attempts.merge(index, 1, Integer::sum);
        job.inflight.add(index);
        job.inflightAt.put(index, now);
        ModNetworking.requestClipFrame(job.clip, index);
        return true;
    }

    /**
     * 收到一块（或一帧）。由 ClipFrameMessage 客户端处理里分流调用：
     * 若该 clip 在文件下载任务中，则走这里；否则仍走 ClipFetcher.onFrame。
     *
     * @return true 表示已作为文件块处理，调用方不要再当 JPG 帧写入
     */
    public static synchronized boolean onChunk(String clip, int index, byte[] data) {
        Job job = JOBS.get(clip);
        if (job == null || job.finished || job.failed) {
            return false;
        }
        job.inflight.remove(index);
        job.inflightAt.remove(index);
        job.lastActivityMs = System.currentTimeMillis();

        if (data == null || data.length == 0) {
            if (job.expectedSize <= 0 && index >= job.nextToWrite) {
                if (job.nextToWrite == 0) {
                    failJob(job);
                } else {
                    finishMerge(job);
                }
            } else {
                markLost(job, index);
            }
            return true;
        }
        if (data.length > CHUNK_SIZE) {
            failJob(job);
            return true;
        }

        File dir = new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips/" + clip);
        try {
            if (!dir.exists()) {
                dir.mkdirs();
            }
            Files.write(partFile(dir, index).toPath(), data);
            job.received.add(index);
            job.attempts.remove(index);
        } catch (Exception e) {
            failJob(job);
            return true;
        }

        while (job.received.contains(job.nextToWrite)
                || partFile(dir, job.nextToWrite).isFile()) {
            job.received.remove(job.nextToWrite);
            job.nextToWrite++;
        }

        int total = job.totalChunks();
        if (job.expectedSize > 0 && job.nextToWrite >= total) {
            finishMerge(job);
        }
        return true;
    }

    private static void markLost(Job job, int index) {
        if (job.attempts.getOrDefault(index, 0) >= MAX_ATTEMPTS) {
            failJob(job);
        } else {
            job.retry.add(index);
        }
    }

    private static void finishMerge(Job job) {
        if (job.finished || job.failed) {
            return;
        }
        File dir = new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips/" + job.clip);
        File out = new File(dir, "video.mp4");
        File tmp = new File(dir, "video.mp4.tmp");
        try (OutputStream os = new FileOutputStream(tmp)) {
            for (int i = 0; i < job.nextToWrite; i++) {
                File part = partFile(dir, i);
                if (!part.isFile()) {
                    failJob(job);
                    return;
                }
                Files.copy(part.toPath(), os);
            }
        } catch (Exception e) {
            failJob(job);
            return;
        }
        if (job.expectedSize > 0 && tmp.length() != job.expectedSize) {
            failJob(job);
            return;
        }
        try {
            try {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(tmp.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.writeString(new File(dir, ClipWire.netMarker()).toPath(), "server");
        } catch (Exception e) {
            failJob(job);
            return;
        }
        job.finished = true;
        clearParts(dir);
        ClipStore.dropInfo(job.clip);
        ClipStore.dropCached(job.clip);
    }

    private static void failJob(Job job) {
        if (job.finished) {
            return;
        }
        job.failed = true;
        File dir = new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips/" + job.clip);
        clearParts(dir);
        new File(dir, "video.mp4.tmp").delete();
        ClipStore.dropInfo(job.clip);
        ClipStore.dropCached(job.clip);
    }

    private static File partFile(File dir, int index) {
        return new File(dir, "video.mp4.part." + index);
    }

    private static void clearParts(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (File f : files) {
            if (f.isFile() && (f.getName().startsWith("video.mp4.part.")
                    || f.getName().equals("video.mp4.tmp"))) {
                f.delete();
            }
        }
    }

    public static synchronized void forget(String clip) {
        JOBS.remove(clip);
    }

    public static synchronized void clear() {
        JOBS.clear();
    }
}
