package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.VideoProfile;
import net.minecraft.client.Minecraft;
import org.jcodec.api.awt.AWTFrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 从外部视频导入为模组可用的帧序列。
 * <p>
 * <b>默认不依赖系统 FFmpeg</b>：使用纯 Java 的 jcodec 解码 MP4/MOV。
 * 若本机安装了 FFmpeg，会自动优先使用（更快、支持 WebM/MKV 等更多格式）。
 * <p>
 * 用法：把视频放到 {@code .minecraft/videorecorder_imports/}，点「导入视频」。
 */
public final class VideoImporter {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VideoRecorder-Importer");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final int DEFAULT_IMPORT_FPS = 12;
    private static final int MAX_IMPORT_FRAMES = Math.min(ModConstants.MAX_STORED_FRAMES, 3600);

    private VideoImporter() {}

    public static boolean isBusy() {
        return BUSY.get();
    }

    public static File importRoot() {
        return new File(Minecraft.getInstance().gameDirectory, "videorecorder_imports");
    }

    public static void importAllAsync(Consumer<String> onProgress,
                                      Consumer<Integer> onAllDone,
                                      Consumer<String> onError) {
        if (!BUSY.compareAndSet(false, true)) {
            onError.accept("已有导入任务正在进行，请稍后再试");
            return;
        }
        WORKER.execute(() -> {
            int success = 0;
            List<String> errors = new ArrayList<>();
            try {
                File root = importRoot();
                if (!root.exists() && !root.mkdirs()) {
                    throw new IllegalStateException("无法创建导入目录：" + root.getAbsolutePath());
                }
                File[] videos = root.listFiles((d, name) -> {
                    String lower = name.toLowerCase(Locale.ROOT);
                    return lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v")
                            || lower.endsWith(".webm") || lower.endsWith(".mkv");
                });
                if (videos == null || videos.length == 0) {
                    Minecraft.getInstance().execute(() -> {
                        BUSY.set(false);
                        onError.accept("导入目录是空的。请把视频放到：\n" + root.getAbsolutePath()
                                + "\n纯 Java 支持：mp4 / mov / m4v\n有 FFmpeg 时额外支持：webm / mkv");
                    });
                    return;
                }
                String ffmpeg = findFFmpeg(); // 可选
                for (File video : videos) {
                    try {
                        String name = video.getName();
                        Minecraft.getInstance().execute(() -> onProgress.accept("正在导入：" + name + " …"));
                        String clipId = importOne(video, ffmpeg);
                        success++;
                        Minecraft.getInstance().execute(() -> onProgress.accept("✓ 已导入：" + name + " → " + clipId));
                    } catch (Exception e) {
                        String err = video.getName() + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
                        errors.add(err);
                        System.err.println("[VideoImporter] " + err);
                    }
                }
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Minecraft.getInstance().execute(() -> {
                    BUSY.set(false);
                    onError.accept(msg);
                });
                return;
            }
            int finalSuccess = success;
            String errSummary = errors.isEmpty() ? null : String.join("；", errors);
            Minecraft.getInstance().execute(() -> {
                BUSY.set(false);
                if (finalSuccess == 0 && errSummary != null) {
                    onError.accept("全部导入失败：" + errSummary);
                } else {
                    onAllDone.accept(finalSuccess);
                    if (errSummary != null) {
                        onError.accept("部分失败：" + errSummary);
                    }
                }
            });
        });
    }

    private static String importOne(File video, String ffmpeg) throws Exception {
        String lower = video.getName().toLowerCase(Locale.ROOT);
        boolean needsFfmpeg = lower.endsWith(".webm") || lower.endsWith(".mkv");

        if (ffmpeg != null) {
            try {
                return importWithFFmpeg(ffmpeg, video);
            } catch (Exception e) {
                System.err.println("[VideoImporter] FFmpeg 失败，尝试 jcodec: " + e.getMessage());
                if (needsFfmpeg) throw e;
            }
        } else if (needsFfmpeg) {
            throw new IllegalStateException("WebM/MKV 需要 FFmpeg。MP4/MOV 可用纯 Java（jcodec）导入，无需 FFmpeg。");
        }
        return importWithJCodec(video, ffmpeg);
    }

    private static String importWithJCodec(File video, String ffmpeg) throws Exception {
        String clipId = makeClipId(video);
        File clipDir = prepareClipDir(clipId);
        int frameCount = 0, width = 0, height = 0;
        double detectedFps = DEFAULT_IMPORT_FPS;

        try (SeekableByteChannel ch = NIOUtils.readableChannel(video)) {
            AWTFrameGrab grab = AWTFrameGrab.createAWTFrameGrab(ch);
            try {
                if (grab.getVideoTrack() != null && grab.getVideoTrack().getMeta() != null) {
                    double totalFrames = grab.getVideoTrack().getMeta().getTotalFrames();
                    double totalSec = grab.getVideoTrack().getMeta().getTotalDuration();
                    if (totalSec > 0.01 && totalFrames > 0) {
                        detectedFps = totalFrames / totalSec;
                    }
                }
            } catch (Exception ignored) {}

            double srcFps = detectedFps > 1 ? detectedFps : DEFAULT_IMPORT_FPS;
            int frameInterval = Math.max(1, (int) Math.round(srcFps / DEFAULT_IMPORT_FPS));
            int decoded = 0;
            while (frameCount < MAX_IMPORT_FRAMES) {
                BufferedImage img;
                try {
                    img = grab.getFrame();
                } catch (Exception e) {
                    break;
                }
                if (img == null) break;
                decoded++;
                if ((decoded - 1) % frameInterval != 0) continue;

                BufferedImage out = fitAndEven(img, ModConstants.MAX_VIDEO_WIDTH, ModConstants.MAX_VIDEO_HEIGHT);
                if (width <= 0) {
                    width = out.getWidth();
                    height = out.getHeight();
                }
                File dest = new File(clipDir, String.format(Locale.ROOT, "frame_%04d.jpg", frameCount));
                ImageIO.write(out, "jpg", dest);
                frameCount++;
            }
        }

        if (frameCount < ModConstants.MIN_FRAMES) {
            deleteDirQuietly(clipDir);
            throw new IllegalStateException("jcodec 解码帧太少（" + frameCount
                    + "）。该文件可能不是 H.264 MP4，请用常见播放器转成 H.264 MP4 后再导入。");
        }
        writeMeta(clipDir, width, height, frameCount, DEFAULT_IMPORT_FPS, video.getName());
        preserveAudio(video, clipDir, ffmpeg);
        ClipStore.dropInfo(clipId);
        return clipId;
    }

    private static String importWithFFmpeg(String ffmpeg, File video) throws Exception {
        String clipId = makeClipId(video);
        File clipDir = prepareClipDir(clipId);
        File pattern = new File(clipDir, "frame_%04d.jpg");
        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg); cmd.add("-y"); cmd.add("-hide_banner"); cmd.add("-loglevel"); cmd.add("error");
        cmd.add("-i"); cmd.add(video.getAbsolutePath());
        cmd.add("-start_number"); cmd.add("0");
        cmd.add("-vf");
        cmd.add(String.format(Locale.ROOT,
                "fps=%d,scale='min(%d,iw)':'min(%d,ih)':force_original_aspect_ratio=decrease,scale=trunc(iw/2)*2:trunc(ih/2)*2",
                DEFAULT_IMPORT_FPS, ModConstants.MAX_VIDEO_WIDTH, ModConstants.MAX_VIDEO_HEIGHT));
        cmd.add("-frames:v"); cmd.add(String.valueOf(MAX_IMPORT_FRAMES));
        cmd.add("-q:v"); cmd.add("3");
        cmd.add(pattern.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) log.append(line).append('\n');
        }
        if (p.waitFor() != 0) {
            deleteDirQuietly(clipDir);
            throw new IllegalStateException("FFmpeg 失败: " + (log.length() > 0 ? log.substring(0, Math.min(200, log.length())) : "无输出"));
        }
        File[] frames = clipDir.listFiles((d, n) -> n.toLowerCase(Locale.ROOT).matches("frame_\\d+\\.jpg"));
        int frameCount = frames == null ? 0 : frames.length;
        if (frameCount < ModConstants.MIN_FRAMES) {
            deleteDirQuietly(clipDir);
            throw new IllegalStateException("抽帧太少（" + frameCount + "）");
        }
        int width = 640, height = 360;
        try {
            BufferedImage img = ImageIO.read(new File(clipDir, "frame_0000.jpg"));
            if (img != null) { width = img.getWidth(); height = img.getHeight(); }
        } catch (Exception ignored) {}
        writeMeta(clipDir, width, height, frameCount, DEFAULT_IMPORT_FPS, video.getName());
        preserveAudio(video, clipDir, ffmpeg);
        ClipStore.dropInfo(clipId);
        return clipId;
    }

    /**
     * 给导入片段保留一份音频源。有 FFmpeg 时只抽音轨成 m4a；没有时对
     * MP4/MOV/M4V 直接复制原文件，播放时只读其中的 AAC 音轨。
     */
    private static void preserveAudio(File video, File clipDir, String ffmpeg) {
        try {
            if (ffmpeg != null && extractAudio(ffmpeg, video, new File(clipDir, "audio_source.m4a"))) {
                return;
            }
            String lower = video.getName().toLowerCase(Locale.ROOT);
            if (lower.endsWith(".mp4") || lower.endsWith(".mov") || lower.endsWith(".m4v")) {
                Files.copy(video.toPath(), new File(clipDir, "audio_source.mp4").toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            System.err.println("[VideoImporter] 保留音频失败: " + e.getMessage());
        }
    }

    private static boolean extractAudio(String ffmpeg, File video, File target) {
        try {
            if (target.exists()) {
                target.delete();
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-i");
            cmd.add(video.getAbsolutePath());
            cmd.add("-vn");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add(target.getAbsolutePath());
            Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                while (r.readLine() != null) {
                }
            }
            if (p.waitFor() == 0 && target.isFile() && target.length() > 0L) {
                return true;
            }
            if (target.exists()) {
                target.delete();
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static String makeClipId(File video) {
        String base = video.getName().replaceAll("\\.[^.]+$", "").replaceAll("[/\\\\:\\x00-\\x1f]", "_");
        if (base.isEmpty()) base = "import";
        if (base.length() > 60) base = base.substring(0, 60);
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String clipId = "import_" + base + "_" + timestamp;
        return ClipWire.isSafeClipName(clipId) ? clipId : "import_" + timestamp;
    }

    private static File prepareClipDir(String clipId) throws Exception {
        File clipDir = new File(new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips"), clipId);
        if (!clipDir.mkdirs() && !clipDir.isDirectory()) {
            throw new IllegalStateException("无法创建录像目录：" + clipDir);
        }
        return clipDir;
    }

    private static void writeMeta(File clipDir, int width, int height, int frames, int fps, String sourceName) throws Exception {
        VideoProfile profile = VideoProfile.bySize(width, height);
        if (profile == null) profile = width >= 480 ? VideoProfile.HIGH_DEFINITION : VideoProfile.STANDARD;
        long durationMs = (long) frames * 1000L / Math.max(1, fps);
        String safeSource = sourceName.replace("\\", "\\\\").replace("\"", "'");
        String meta = String.format(Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"frames\":%d,\"fps\":%d,\"duration_ms\":%d,\"profile\":\"%s\",\"frame_format\":\"jpg\",\"imported\":true,\"source\":\"%s\"}",
                width, height, frames, fps, durationMs, profile.id(), safeSource);
        Files.writeString(new File(clipDir, "meta.json").toPath(), meta, StandardCharsets.UTF_8);
    }

    private static BufferedImage fitAndEven(BufferedImage src, int maxW, int maxH) {
        int w = src.getWidth(), h = src.getHeight();
        double scale = Math.min(1.0, Math.min((double) maxW / w, (double) maxH / h));
        int nw = Math.max(2, ((int) (w * scale)) & ~1);
        int nh = Math.max(2, ((int) (h * scale)) & ~1);
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_3BYTE_BGR);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        return out;
    }

    private static void deleteDirQuietly(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    private static String findFFmpeg() {
        String[] candidates = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{"ffmpeg.exe", "ffmpeg"} : new String[]{"ffmpeg"};
        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "-version").redirectErrorStream(true).start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) {}
                }
                if (p.waitFor() == 0) return cmd;
            } catch (Exception ignored) {}
        }
        return null;
    }
}
