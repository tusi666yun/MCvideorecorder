package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.VideoProfile;
import net.minecraft.client.Minecraft;
import org.jcodec.api.awt.AWTSequenceEncoder;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.model.Rational;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/**
 * 把本机录像帧序列导出为真正的视频文件（MP4 / WebM）。
 *
 * 优先使用系统 FFmpeg（最稳定、画质最好）。
 * 没有 FFmpeg 时，对 MP4 使用 jcodec 纯 Java 兜底。
 */
public final class VideoExporter {

    public enum Format {
        MP4("mp4", "MP4 (H.264)"),
        WEBM("webm", "WebM (VP9)");

        public final String extension;
        public final String displayName;

        Format(String extension, String displayName) {
            this.extension = extension;
            this.displayName = displayName;
        }
    }

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VideoRecorder-Exporter");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);

    private VideoExporter() {
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    /** 导出目录：.minecraft/videorecorder_exports/ */
    public static File exportRoot() {
        return new File(Minecraft.getInstance().gameDirectory, "videorecorder_exports");
    }

    /**
     * 异步导出。完成后在客户端线程回调 onDone / onError。
     */
    public static void exportAsync(ClipStore.Clip clip, Format format,
                                   Consumer<File> onDone, Consumer<String> onError) {
        if (clip == null) {
            onError.accept("没有选中的录像");
            return;
        }
        if (!ClipStore.hasLocal(clip.id)) {
            onError.accept("这段录像的帧文件不在本机，无法导出（请先完整下载）");
            return;
        }
        if (!BUSY.compareAndSet(false, true)) {
            onError.accept("已有导出任务正在进行，请稍后再试");
            return;
        }

        WORKER.execute(() -> {
            try {
                File result = doExport(clip, format);
                Minecraft.getInstance().execute(() -> {
                    BUSY.set(false);
                    onDone.accept(result);
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                Minecraft.getInstance().execute(() -> {
                    BUSY.set(false);
                    onError.accept(msg);
                });
            }
        });
    }

    private static File doExport(ClipStore.Clip clip, Format format) throws Exception {
        File clipDir = new File(new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips"), clip.id);
        File[] frames = listFrameFiles(clipDir);
        if (frames == null || frames.length == 0) {
            throw new IllegalStateException("找不到任何帧文件（请确认该录像已完整保存在本机）");
        }

        File outDir = exportRoot();
        if (!outDir.exists() && !outDir.mkdirs()) {
            throw new IllegalStateException("无法创建导出目录：" + outDir.getAbsolutePath());
        }

        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.ROOT).format(new Date());
        String safeName = clip.id.replaceAll("[^a-zA-Z0-9_\\-]", "_");
        File output = new File(outDir, safeName + "_" + timestamp + "." + format.extension);

        int fps = Math.max(1, clip.fps);
        String ffmpeg = findFFmpeg(); // 可选，没有也能导出 MP4

        // 1) 优先使用 FFmpeg（画质更好，且支持 WebM）
        if (ffmpeg != null) {
            try {
                // 删除可能产生的空/坏文件
                if (output.exists()) output.delete();
                runFFmpegReliable(ffmpeg, clipDir, frames, fps, format, output);
                if (isValidVideo(output)) {
                    return output;
                }
            } catch (Exception e) {
                System.err.println("[VideoExporter] FFmpeg failed: " + e.getMessage());
            }
        }


        // 2) FFmpeg 不可用或失败时，用纯 Java JCodec 兜底
        if (format == Format.MP4) {
            try {
                encodeWithJCodec(frames, fps, output);
                if (isValidVideo(output)) {
                    return output;
                }
            } catch (Exception e) {
                System.err.println("[VideoExporter] jcodec failed: " + e.getMessage());
            }
        }

        if (format == Format.WEBM && ffmpeg == null) {
            throw new IllegalStateException("导出 WebM 需要 FFmpeg。MP4 可用纯 Java 导出，无需 FFmpeg。");
        }
        throw new IllegalStateException("导出失败。请确认录像帧文件完整；MP4 不需要 FFmpeg，WebM 需要。");
    }

    private static boolean isValidVideo(File f) {
        // 至少要有几 KB，否则基本是空的或只有容器头
        return f != null && f.isFile() && f.length() > 2048;
    }

    private static File[] listFrameFiles(File dir) {
        File[] files = dir.listFiles((d, name) -> {
            String lower = name.toLowerCase(Locale.ROOT);
            return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
        });
        if (files == null || files.length == 0) {
            return null;
        }
        Arrays.sort(files, Comparator.comparing(File::getName));
        return files;
    }

    private static String findFFmpeg() {
        String[] candidates = isWindows()
                ? new String[]{"ffmpeg.exe", "ffmpeg"}
                : new String[]{"ffmpeg"};
        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "-version")
                        .redirectErrorStream(true)
                        .start();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) { /* drain */ }
                }
                if (p.waitFor() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * 最可靠的 FFmpeg 调用方式：
     * 优先用 -framerate + frame_%04d.jpg 模式；
     * 如果帧编号不连续，再回退到 concat。
     */
    private static void runFFmpegReliable(String ffmpeg, File clipDir, File[] frames,
                                          int fps, Format format, File output) throws Exception {
        // 检查是否是连续的 frame_XXXX.jpg
        boolean sequential = true;
        for (int i = 0; i < frames.length; i++) {
            String expected = String.format(Locale.ROOT, "frame_%04d.jpg", i);
            String expectedPng = String.format(Locale.ROOT, "frame_%04d.png", i);
            String name = frames[i].getName().toLowerCase(Locale.ROOT);
            if (!name.equals(expected) && !name.equals(expectedPng)
                    && !name.equals(String.format(Locale.ROOT, "frame_%04d.jpeg", i))) {
                sequential = false;
                break;
            }
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(ffmpeg);
        cmd.add("-y");
        cmd.add("-hide_banner");
        cmd.add("-loglevel");
        cmd.add("error");

        if (sequential) {
            // 最佳路径：直接按编号序列读取
            String pattern = new File(clipDir, "frame_%04d.jpg").getAbsolutePath();
            // 如果实际是 png，换扩展名
            if (frames[0].getName().toLowerCase(Locale.ROOT).endsWith(".png")) {
                pattern = new File(clipDir, "frame_%04d.png").getAbsolutePath();
            }
            cmd.add("-framerate");
            cmd.add(String.valueOf(fps));
            cmd.add("-i");
            cmd.add(pattern);
        } else {
            // 回退：concat demuxer
            File listFile = File.createTempFile("vr_frames_", ".txt");
            listFile.deleteOnExit();
            List<String> lines = new ArrayList<>();
            double duration = 1.0 / fps;
            for (File f : frames) {
                String path = f.getAbsolutePath().replace("\\", "/").replace("'", "'\\''");
                lines.add("file '" + path + "'");
                lines.add(String.format(Locale.ROOT, "duration %.6f", duration));
            }
            // concat 需要最后一帧再写一次
            if (frames.length > 0) {
                String path = frames[frames.length - 1].getAbsolutePath().replace("\\", "/").replace("'", "'\\''");
                lines.add("file '" + path + "'");
            }
            Files.write(listFile.toPath(), lines, StandardCharsets.UTF_8);

            cmd.add("-f");
            cmd.add("concat");
            cmd.add("-safe");
            cmd.add("0");
            cmd.add("-r");
            cmd.add(String.valueOf(fps));
            cmd.add("-i");
            cmd.add(listFile.getAbsolutePath());
        }

        // 编码参数
        if (format == Format.MP4) {
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-crf");
            cmd.add("18");
            cmd.add("-preset");
            cmd.add("fast");
            cmd.add("-movflags");
            cmd.add("+faststart");
            // 强制偶数分辨率（H.264 要求）
            cmd.add("-vf");
            cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
        } else {
            cmd.add("-c:v");
            cmd.add("libvpx-vp9");
            cmd.add("-b:v");
            cmd.add("0");
            cmd.add("-crf");
            cmd.add("32");
            cmd.add("-row-mt");
            cmd.add("1");
            cmd.add("-vf");
            cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
        }

        cmd.add("-r");
        cmd.add(String.valueOf(fps));
        cmd.add(output.getAbsolutePath());

        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        StringBuilder log = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                log.append(line).append('\n');
            }
        }
        int code = p.waitFor();
        if (code != 0) {
            String msg = log.length() > 0 ? log.substring(0, Math.min(500, log.length())) : "无输出";
            throw new IllegalStateException("FFmpeg 退出码 " + code + "\n" + msg);
        }
    }

    private static void encodeWithJCodec(File[] frames, int fps, File output) throws Exception {
        AWTSequenceEncoder encoder = new AWTSequenceEncoder(
                NIOUtils.writableChannel(output),
                Rational.R(Math.max(1, fps), 1));
        try {
            int encoded = 0;
            for (File frameFile : frames) {
                BufferedImage img = ImageIO.read(frameFile);
                if (img == null) {
                    continue;
                }
                // 转成标准 RGB，并保证宽高为偶数（H.264 要求）
                int w = img.getWidth() & ~1;
                int h = img.getHeight() & ~1;
                if (w <= 0 || h <= 0) {
                    continue;
                }
                BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = rgb.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(img, 0, 0, w, h, null);
                g.dispose();
                encoder.encodeImage(rgb);
                encoded++;
            }
            if (encoded == 0) {
                throw new IllegalStateException("没有任何帧能被读取（可能帧文件已损坏）");
            }
        } finally {
            encoder.finish();
        }
    }
}
