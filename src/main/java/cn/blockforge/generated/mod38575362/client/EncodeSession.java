package cn.blockforge.generated.mod38575362.client;

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
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 停录后的后台编码：进度、可取消、不阻塞游戏主线程。
 * <p>
 * 优先 FFmpeg；否则 jcodec。编码过程中 HUD 可显示 {@link #statusLine()}。
 * 按 <b>K</b> 键可请求取消（见 ClientForgeEvents）。
 */
public final class EncodeSession {

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "VideoRecorder-Encode");
        t.setDaemon(true);
        return t;
    });

    private static final AtomicBoolean BUSY = new AtomicBoolean(false);
    private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
    private static final AtomicInteger CURRENT = new AtomicInteger(0);
    private static final AtomicInteger TOTAL = new AtomicInteger(0);
    private static volatile String phase = "";
    private static volatile String clipId = "";
    private static volatile Process ffmpegProcess;

    /** 关键帧间隔：写入 seek_index.txt，播放时可作缓存锚点。 */
    public static final int KEYFRAME_EVERY = 30;

    private EncodeSession() {
    }

    public static boolean isBusy() {
        return BUSY.get();
    }

    public static float progress() {
        int t = TOTAL.get();
        if (t <= 0) {
            return 0f;
        }
        return Math.min(1f, CURRENT.get() / (float) t);
    }

    public static String statusLine() {
        if (!BUSY.get()) {
            return "";
        }
        int t = TOTAL.get();
        int c = CURRENT.get();
        int pct = t > 0 ? (int) (100f * c / t) : 0;
        return "编码 " + pct + "% (" + c + "/" + t + ") " + phase + "  [K 取消]";
    }

    public static String activeClip() {
        return clipId;
    }

    public static void requestCancel() {
        CANCEL.set(true);
        Process p = ffmpegProcess;
        if (p != null) {
            p.destroyForcibly();
        }
    }

    /**
     * 后台把目录内 frame_*.jpg 编成 video.mp4，并写 seek_index.txt。
     */
    public static void encodeAsync(String clip, File dir, File output, int fps,
                                   Runnable onSuccess, Consumer<String> onError) {
        if (!BUSY.compareAndSet(false, true)) {
            if (onError != null) {
                onError.accept("已有编码任务在进行");
            }
            return;
        }
        CANCEL.set(false);
        CURRENT.set(0);
        TOTAL.set(0);
        phase = "准备";
        clipId = clip == null ? "" : clip;

        WORKER.execute(() -> {
            try {
                doEncode(dir, output, Math.max(1, fps));
                if (CANCEL.get()) {
                    if (output.exists()) {
                        output.delete();
                    }
                    fail(onError, "编码已取消");
                    return;
                }
                writeSeekIndex(dir, TOTAL.get());
                Minecraft.getInstance().execute(() -> {
                    reset();
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                });
            } catch (Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                if (CANCEL.get()) {
                    msg = "编码已取消";
                }
                if (output != null && output.exists() && (CANCEL.get() || output.length() < 100)) {
                    output.delete();
                }
                fail(onError, msg);
            }
        });
    }

    private static void fail(Consumer<String> onError, String msg) {
        Minecraft.getInstance().execute(() -> {
            reset();
            if (onError != null) {
                onError.accept(msg);
            }
        });
    }

    private static void reset() {
        BUSY.set(false);
        CANCEL.set(false);
        CURRENT.set(0);
        TOTAL.set(0);
        phase = "";
        clipId = "";
        ffmpegProcess = null;
    }

    private static void doEncode(File dir, File output, int fps) throws Exception {
        File[] frames = dir.listFiles((d, name) -> name.matches("frame_[0-9]{4,}\\.jpg"));
        if (frames == null || frames.length == 0) {
            throw new IllegalStateException("没有可编码的 JPG 帧");
        }
        Arrays.sort(frames, Comparator.comparing(File::getName));
        TOTAL.set(frames.length);
        CURRENT.set(0);

        phase = "FFmpeg";
        if (tryFfmpeg(dir, output, fps, frames.length)) {
            CURRENT.set(frames.length);
            phase = "完成";
            return;
        }
        if (CANCEL.get()) {
            throw new InterruptedException("cancelled");
        }

        phase = "jcodec";
        if (output.exists()) {
            output.delete();
        }
        AWTSequenceEncoder encoder = new AWTSequenceEncoder(
                NIOUtils.writableChannel(output), Rational.R(fps, 1));
        try {
            int i = 0;
            for (File file : frames) {
                if (CANCEL.get()) {
                    throw new InterruptedException("cancelled");
                }
                BufferedImage image = ImageIO.read(file);
                if (image == null) {
                    continue;
                }
                int w = image.getWidth() & ~1;
                int h = image.getHeight() & ~1;
                if (w < 2 || h < 2) {
                    continue;
                }
                BufferedImage rgb = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
                Graphics2D g = rgb.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, w, h, null);
                g.dispose();
                encoder.encodeImage(rgb);
                CURRENT.set(++i);
            }
            if (i == 0) {
                throw new IllegalStateException("没有可编码的帧");
            }
        } finally {
            encoder.finish();
        }
        phase = "完成";
    }

    private static boolean tryFfmpeg(File dir, File output, int fps, int total) {
        String ffmpeg = findFFmpeg();
        if (ffmpeg == null) {
            return false;
        }
        try {
            if (output.exists()) {
                output.delete();
            }
            // 进度：用 -progress pipe:1 太复杂，这里按时间粗估；取消靠 destroy
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-framerate");
            cmd.add(String.valueOf(fps));
            cmd.add("-i");
            cmd.add(new File(dir, "frame_%04d.jpg").getAbsolutePath());
            cmd.add("-frames:v");
            cmd.add(String.valueOf(total));
            cmd.add("-c:v");
            cmd.add("libx264");
            cmd.add("-preset");
            cmd.add("ultrafast");
            cmd.add("-crf");
            cmd.add("20");
            cmd.add("-pix_fmt");
            cmd.add("yuv420p");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add("-vf");
            cmd.add("scale=trunc(iw/2)*2:trunc(ih/2)*2");
            cmd.add(output.getAbsolutePath());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            ffmpegProcess = p;

            // 后台读输出，同时用线程模拟进度（FFmpeg 不方便拿帧号时）
            Thread drain = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    while (r.readLine() != null) {
                        if (CANCEL.get()) {
                            break;
                        }
                    }
                } catch (Exception ignored) {
                }
            }, "ffmpeg-drain");
            drain.setDaemon(true);
            drain.start();

            // 简单心跳进度，避免 HUD 一直 0%
            Thread pulse = new Thread(() -> {
                int step = Math.max(1, total / 50);
                while (p.isAlive() && !CANCEL.get()) {
                    int cur = CURRENT.get();
                    if (cur < total - 1) {
                        CURRENT.set(Math.min(total - 1, cur + step));
                    }
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }, "ffmpeg-pulse");
            pulse.setDaemon(true);
            pulse.start();

            int code = p.waitFor();
            ffmpegProcess = null;
            if (CANCEL.get()) {
                return false;
            }
            if (code == 0 && output.isFile() && output.length() > 100L) {
                CURRENT.set(total);
                return true;
            }
        } catch (Exception e) {
            System.err.println("[EncodeSession] FFmpeg: " + e.getMessage());
        }
        ffmpegProcess = null;
        return false;
    }

    private static void writeSeekIndex(File dir, int frameCount) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("version=1\n");
            sb.append("frames=").append(frameCount).append('\n');
            sb.append("keyframe_every=").append(KEYFRAME_EVERY).append('\n');
            for (int i = 0; i < frameCount; i += KEYFRAME_EVERY) {
                sb.append(i).append('\n');
            }
            Files.writeString(new File(dir, "seek_index.txt").toPath(), sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            System.err.println("[EncodeSession] seek_index: " + e.getMessage());
        }
    }

    private static String findFFmpeg() {
        String[] candidates = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{"ffmpeg.exe", "ffmpeg"}
                : new String[]{"ffmpeg"};
        for (String cmd : candidates) {
            try {
                Process p = new ProcessBuilder(cmd, "-version").redirectErrorStream(true).start();
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
}
