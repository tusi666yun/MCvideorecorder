package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.FilmItem;
import cn.blockforge.generated.mod38575362.ModConstants;
import cn.blockforge.generated.mod38575362.ModNetworking;
import cn.blockforge.generated.mod38575362.ModRegistry;
import cn.blockforge.generated.mod38575362.VideoProfile;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端录像器：录制时仅把采集帧写成 JPG；停止后交给 {@link EncodeSession}
 * 在后台优先用 FFmpeg 编码 MP4（不可用或失败时回退 JCodec），带进度 HUD、可按 K 取消。
 * 编码不占用游戏渲染线程。
 */
public final class VideoRecorder {
    public static final String VIDEO_FILE_NAME = "video.mp4";

    private static boolean recording;
    private static String clipId = "";
    private static int frameCount;
    private static long nextCaptureMs;
    private static Player boundPlayer;
    private static ExecutorService writer;
    private static VideoProfile profile = VideoProfile.STANDARD;
    private static File audioFile;
    private static final AtomicInteger PENDING_JOBS = new AtomicInteger();
    private static final int MAX_PENDING_JOBS = 8;
    private static int droppedFrames;
    private static boolean capReached;

    private VideoRecorder() {
    }

    public static boolean isRecording() {
        return recording;
    }

    public static int framesRecorded() {
        return frameCount;
    }

    public static VideoProfile activeProfile() {
        return profile;
    }

    public static void toggle(Player player, VideoProfile wanted) {
        if (recording) {
            stop(player, false);
        } else {
            start(player, wanted);
        }
    }

    private static boolean hasBlankFilm(Player player) {
        for (ItemStack stack : player.getInventory().items) {
            if (stack.is(ModRegistry.FILM.get()) && !FilmItem.hasClip(stack)) {
                return true;
            }
        }
        ItemStack offhand = player.getOffhandItem();
        return offhand.is(ModRegistry.FILM.get()) && !FilmItem.hasClip(offhand);
    }

    private static void start(Player player, VideoProfile wanted) {
        if (!hasBlankFilm(player)) {
            player.displayClientMessage(Component.literal("录制前请拿一卷空白胶卷").withStyle(ChatFormatting.RED), true);
            return;
        }
        clipId = "clip_" + System.currentTimeMillis() + "_" + Integer.toHexString(player.getRandom().nextInt());
        File dir = new File(new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips"), clipId);
        if (!dir.mkdirs() && !dir.isDirectory()) {
            player.displayClientMessage(Component.literal("无法创建录像目录").withStyle(ChatFormatting.RED), true);
            return;
        }
        profile = wanted == null ? VideoProfile.STANDARD : wanted;
        try {
            ClipStore.begin(clipId);
        } catch (IOException e) {
            player.displayClientMessage(Component.literal("无法创建录像文件夹：" + e.getMessage()).withStyle(ChatFormatting.RED), true);
            return;
        }
        audioFile = new File(dir, "audio.wav");
        writer = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "videorecorder-frame-writer");
            t.setDaemon(true);
            return t;
        });
        recording = true;
        frameCount = 0;
        droppedFrames = 0;
        capReached = false;
        PENDING_JOBS.set(0);
        nextCaptureMs = System.currentTimeMillis() + profile.frameIntervalMs();
        boundPlayer = player;
        player.displayClientMessage(Component.literal("● 开始录制 MP4 " + profile.describe() + "，再右键停止")
                .withStyle(ChatFormatting.RED), true);
    }

    public static void onRenderTick(Minecraft mc) {
        if (!recording) {
            return;
        }
        if (mc.level == null || mc.player == null || mc.player != boundPlayer || boundPlayer.isRemoved()) {
            stop(null, true);
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextCaptureMs) {
            return;
        }
        nextCaptureMs = Math.max(nextCaptureMs + profile.frameIntervalMs(), now);
        captureFrame(mc);
        if (frameCount >= ModConstants.MAX_STORED_FRAMES) {
            capReached = true;
            stop(mc.player, false);
        }
    }

    private static void captureFrame(Minecraft mc) {
        if (PENDING_JOBS.get() >= MAX_PENDING_JOBS) {
            droppedFrames++;
            return;
        }
        NativeImage full = null;
        try {
            if (frameCount == 0 && audioFile != null) {
                GameAudioRecorder.start(audioFile);
            }
            full = Screenshot.takeScreenshot(mc.getMainRenderTarget());
            NativeImage frame = new NativeImage(profile.width(), profile.height(), false);
            full.resizeSubRectTo(0, 0, full.getWidth(), full.getHeight(), frame);
            final String clip = clipId;
            final int index = frameCount;
            final VideoProfile used = profile;
            ExecutorService queue = writer;
            if (queue == null || queue.isShutdown()) {
                frame.close();
                return;
            }
            PENDING_JOBS.incrementAndGet();
            try {
                queue.execute(() -> {
                    try {
                        ClipStore.saveFrame(clip, used, index, frame);
                    } catch (Exception e) {
                        System.err.println("[VideoRecorder] save frame " + index + ": " + e.getMessage());
                    } finally {
                        frame.close();
                        PENDING_JOBS.decrementAndGet();
                    }
                });
            } catch (RuntimeException e) {
                PENDING_JOBS.decrementAndGet();
                frame.close();
                return;
            }
            frameCount++;
        } catch (Exception ignored) {
        } finally {
            if (full != null) {
                full.close();
            }
        }
    }

    /** NativeImage (ABGR) → BufferedImage TYPE_3BYTE_BGR，宽高取偶数 */
    static BufferedImage nativeToBuffered(NativeImage src) {
        int w = src.getWidth() & ~1;
        int h = src.getHeight() & ~1;
        if (w < 2) w = 2;
        if (h < 2) h = 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_3BYTE_BGR);
        for (int y = 0; y < h && y < src.getHeight(); y++) {
            for (int x = 0; x < w && x < src.getWidth(); x++) {
                int abgr = src.getPixelRGBA(x, y);
                int r = abgr & 0xFF;
                int g = (abgr >> 8) & 0xFF;
                int b = (abgr >> 16) & 0xFF;
                int rgb = (r << 16) | (g << 8) | b;
                img.setRGB(x, y, rgb);
            }
        }
        return img;
    }

    private static void stop(Player player, boolean discarded) {
        if (!recording) {
            return;
        }
        recording = false;
        GameAudioRecorder.stop();
        audioFile = null;
        final VideoProfile used = profile;
        final String clip = clipId;
        final int frames = frameCount;
        final long durationMs = frames * 1000L / Math.max(1, used.fps());
        final ExecutorService queue = writer;
        writer = null;
        boundPlayer = null;
        if (queue != null) {
            if (!discarded) {
                queue.execute(() -> {
                    try {
                        ClipStore.finish(clip, used, frames, durationMs);
                    } catch (Exception ignored) {
                    }
                });
            }
            queue.shutdown();
            Thread finalizer = new Thread(() -> {
                try {
                    queue.awaitTermination(120, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                }

                if (discarded) {
                    Minecraft.getInstance().execute(() -> finalizeStop(true, used, clip, frames, durationMs));
                    return;
                }

                File dir = new File(new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips"), clip);
                File out = new File(dir, VIDEO_FILE_NAME);

                // 先通知「录制结束，正在后台编码」，进度由 EncodeSession HUD 展示
                Minecraft.getInstance().execute(() -> {
                    Player p = Minecraft.getInstance().player;
                    if (p != null) {
                        p.displayClientMessage(Component.literal("录制结束，正在后台编码 MP4…").withStyle(ChatFormatting.YELLOW), true);
                    }
                });

                EncodeSession.encodeAsync(clip, dir, out, used.fps(),
                        () -> {
                            // 编码完成后在后台把 WAV 合进 MP4，不阻塞渲染线程。
                            Thread mux = new Thread(() -> {
                                try {
                                    ClipMuxer.mux(dir, clip);
                                } catch (Throwable e) {
                                    System.err.println("[VideoRecorder] mux error: " + e.getMessage());
                                }
                                Minecraft.getInstance().execute(() -> {
                                    try {
                                        writeMeta(clip, used, frames, durationMs);
                                    } catch (Exception ignored) {
                                    }
                                    // 此时 video.mp4（可能已带音轨）已存在，finalizeStop 会发起上传
                                    finalizeStop(false, used, clip, frames, durationMs);
                                });
                            }, "videorecorder-mux");
                            mux.setDaemon(true);
                            mux.start();
                        },
                        err -> {
                            Player p = Minecraft.getInstance().player;
                            if (p != null) {
                                p.displayClientMessage(Component.literal("编码失败: " + err).withStyle(ChatFormatting.RED), true);
                            }
                            // 仍保留 JPG 帧，可稍后重试或仅用帧播放
                            finalizeStop(false, used, clip, frames, durationMs);
                        });
            }, "videorecorder-finalize");
            finalizer.setDaemon(true);
            finalizer.start();
        } else {
            finalizeStop(discarded, used, clip, frames, durationMs);
        }
    }

    private static void writeMeta(String clip, VideoProfile used, int frames, long durationMs) throws IOException {
        File dir = new File(new File(Minecraft.getInstance().gameDirectory, "videorecorder_clips"), clip);
        String meta = String.format(Locale.ROOT,
                "{\"width\":%d,\"height\":%d,\"frames\":%d,\"fps\":%d,\"duration_ms\":%d,\"profile\":\"%s\",\"frame_format\":\"jpg\",\"container\":\"%s\"}",
                used.width(), used.height(), frames, used.fps(), durationMs, used.id(), VIDEO_FILE_NAME);
        Files.writeString(new File(dir, "meta.json").toPath(), meta, StandardCharsets.UTF_8);
    }

    private static void finalizeStop(boolean discarded, VideoProfile used, String clip, int frames, long durationMs) {
        Minecraft mc = Minecraft.getInstance();
        Player player = mc.player;
        File dir = new File(new File(mc.gameDirectory, "videorecorder_clips"), clip);
        File mp4 = new File(dir, VIDEO_FILE_NAME);
        if (discarded) {
            deleteDir(dir);
            if (player != null) {
                player.displayClientMessage(Component.literal("录制已中断").withStyle(ChatFormatting.GRAY), true);
            }
            return;
        }
        File[] savedFrames = dir.listFiles((d, name) -> name.matches("frame_[0-9]{4,}\\.jpg"));
        if (frames < ModConstants.MIN_FRAMES || savedFrames == null || savedFrames.length < ModConstants.MIN_FRAMES) {
            deleteDir(dir);
            if (player != null) player.displayClientMessage(Component.literal("录制太短或帧保存失败，已取消").withStyle(ChatFormatting.YELLOW), true);
            return;
        }
        boolean mp4Ready = mp4.isFile() && mp4.length() > 100;
        ClipStore.dropInfo(clip);
        if (mc.getConnection() != null) {
            ModNetworking.saveClipToServer(clip, used, frames, durationMs);
        }
        // MP4 编码失败时仍保留 JPG 帧，单机内可逐帧回放；仅在 MP4 可用时上传视频文件。
        if (mp4Ready) ClipUploader.start(clip);
        if (player != null) {
            String text = "✔ 录制完成 " + used.describe() + " · " + frames + " 帧" + (mp4Ready ? " · MP4 " + humanSize(mp4.length()) : " · 已保存帧序列（MP4 编码失败，可在游戏内回放）");
            if (capReached) text += "（达到上限自动停止）";
            if (droppedFrames > 0) text += "（跳过 " + droppedFrames + " 帧）";
            if (!mc.isSingleplayer() && mp4Ready) {
                text += "，正在上传到服务器…";
            }
            player.displayClientMessage(Component.literal(text).withStyle(ChatFormatting.GREEN), true);
        }
    }

    private static void deleteDir(File dir) {
        if (dir == null || !dir.isDirectory()) return;
        File[] files = dir.listFiles();
        if (files != null) for (File f : files) f.delete();
        dir.delete();
    }

    private static String humanSize(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) return String.format(Locale.ROOT, "%.0f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
