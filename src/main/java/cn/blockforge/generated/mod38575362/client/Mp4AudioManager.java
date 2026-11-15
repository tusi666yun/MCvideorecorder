package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;

import java.io.File;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * 画面播放的音频会话管理。
 * <p>
 * 每个「片段 + 服务端播放起点」对应一个会话，多个屏幕共用同一片段同一时刻时
 * 只放一份声音。渲染器每帧调用 update()，会话按距离调音量；超过一小段时间
 * 没有渲染器引用就自动停止，换片/清空/区块卸载都不需要额外的回调。
 */
public final class Mp4AudioManager {
    private static final long PRUNE_MS = 1500L;
    private static final float MAX_DISTANCE = 32.0F;
    private static final Object LOCK = new Object();
    private static final Map<String, Session> SESSIONS = new HashMap<>();
    /** 打不开的音频源按「文件 + 修改时间 + 大小」记住，避免每帧重试。 */
    private static final Map<String, String> FAILED = new HashMap<>();
    private static final java.util.Set<String> NO_AUDIO_LOGGED = new java.util.HashSet<>();

    private Mp4AudioManager() {
    }

    /**
     * 由渲染器每帧调用。startMs 是服务端的播放起点；loopMs 是画面循环周期。
     */
    public static void update(ClipStore.Clip clip, long startMs, boolean playing,
                              double x, double y, double z, long loopMs) {
        if (clip == null || startMs <= 0L || !ClipWire.isSafeClipName(clip.id)) {
            return;
        }
        File file = Mp4AudioSource.findAudioFile(clip.id);
        if (file == null) {
            synchronized (LOCK) {
                if (NO_AUDIO_LOGGED.add(clip.id)) {
                    System.out.println("[VideoRecorder] no audio source for clip " + clip.id);
                }
            }
            return;
        }
        if (!PcmOutput.isAvailable()) {
            synchronized (LOCK) {
                if (NO_AUDIO_LOGGED.add("backend:" + clip.id)) {
                    System.out.println("[VideoRecorder] no PCM output backend for clip " + clip.id);
                }
            }
            return;
        }
        String failKey = clip.id + '@' + file.lastModified() + ':' + file.length();
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            if (FAILED.containsKey(failKey)) {
                return;
            }
            String key = clip.id + '#' + startMs;
            // 换片/继续播放都可能换起点：同一片段只保留最新会话。
            // 两面不同起点的墙会各自持续被渲染，不要互相踢掉。
            Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Session old = it.next().getValue();
                if (old.clipId.equals(clip.id) && !old.key.equals(key)
                        && now - old.lastSeen > 200L) {
                    old.stop();
                    it.remove();
                }
            }
            Session session = SESSIONS.get(key);
            boolean created = session == null;
            if (created) {
                session = new Session(key, clip.id, file, startMs, Math.max(0L, loopMs), failKey);
                SESSIONS.put(key, session);
            }
            session.lastSeen = now;
            session.playing = playing;
            session.x = x;
            session.y = y;
            session.z = z;
            session.volume = volumeAt(x, y, z);
            if (created) {
                session.start();
            }
        }
    }

    private static float volumeAt(double x, double y, double z) {
        Player player = Minecraft.getInstance().player;
        if (player == null) {
            return 0.0F;
        }
        double distance = Math.sqrt(player.distanceToSqr(x, y, z));
        float volume = (float) Math.max(0.0, 1.0 - distance / MAX_DISTANCE);
        return volume * volume;
    }

    /** 客户端 tick：清理已经不再被渲染的会话；退出世界时全部停止。 */
    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            stopAll();
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            Iterator<Map.Entry<String, Session>> it = SESSIONS.entrySet().iterator();
            while (it.hasNext()) {
                Session session = it.next().getValue();
                if (now - session.lastSeen > PRUNE_MS) {
                    session.stop();
                    it.remove();
                }
            }
        }
    }

    public static void stopAll() {
        synchronized (LOCK) {
            for (Session session : SESSIONS.values()) {
                session.stop();
            }
            SESSIONS.clear();
        }
    }

    private static void markFailed(Session session) {
        synchronized (LOCK) {
            FAILED.put(session.failKey, session.key);
            SESSIONS.remove(session.key, session);
        }
    }

    private static final class Session implements Runnable {
        final String key;
        final String clipId;
        final File file;
        final long startMs;
        final long loopMs;
        final String failKey;
        final Thread thread;
        volatile boolean playing = true;
        volatile boolean stopped;
        volatile long lastSeen = System.currentTimeMillis();
        volatile double x;
        volatile double y;
        volatile double z;
        volatile float volume = 1.0F;
        private volatile PcmOutput output;

        Session(String key, String clipId, File file, long startMs, long loopMs, String failKey) {
            this.key = key;
            this.clipId = clipId;
            this.file = file;
            this.startMs = startMs;
            this.loopMs = loopMs;
            this.failKey = failKey;
            this.thread = new Thread(this, "videorecorder-audio-" + clipId);
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void stop() {
            stopped = true;
            PcmOutput out = output;
            if (out != null) {
                try {
                    out.pause();
                } catch (Exception ignored) {
                }
                try {
                    out.close();
                } catch (Exception ignored) {
                }
            }
        }

        @Override
        public void run() {
            PcmAudioSource source = null;
            PcmOutput out = null;
            try {
                System.out.println("[VideoRecorder] audio start " + clipId + " -> " + file.getName());
                source = PcmAudioSource.open(file);
                if (source == null) {
                    System.err.println("[VideoRecorder] audio decode failed: " + file.getName());
                    markFailed(this);
                    return;
                }
                out = PcmOutput.open(source.sampleRate(), source.outputChannels());
                if (out == null) {
                    System.err.println("[VideoRecorder] PCM output open failed: "
                            + source.sampleRate() + " Hz / " + source.outputChannels() + " ch");
                    markFailed(this);
                    return;
                }
                output = out;
                long loop = loopMs > 0L ? loopMs : source.durationMs();
                if (loop <= 0L) {
                    loop = source.durationMs();
                }
                long elapsed = Math.max(0L, System.currentTimeMillis() - startMs);
                long offset = loop > 0L ? elapsed % loop : 0L;
                if (source.durationMs() > 0L && offset > source.durationMs()) {
                    // 画面循环比音轨长：等下一轮画面循环再从 0 开始。
                    long wait = loop - offset;
                    if (wait > 0L && wait < 10_000L) {
                        Thread.sleep(wait);
                    }
                    offset = 0L;
                }
                source.seekToMs(offset);
                out.setVolume(volume);
                out.play();
                while (!stopped) {
                    if (!playing) {
                        out.pause();
                        Thread.sleep(40L);
                        continue;
                    }
                    out.play();
                    out.setVolume(volume);
                    if (loop > 0L && source.positionMs() >= loop) {
                        source.seekToMs(0L);
                        continue;
                    }
                    byte[] pcm;
                    try {
                        pcm = source.nextPcm();
                    } catch (Exception e) {
                        break;
                    }
                    if (pcm == null) {
                        source.seekToMs(0L);
                        continue;
                    }
                    int offsetBytes = 0;
                    while (offsetBytes < pcm.length && !stopped) {
                        int written = out.write(pcm, offsetBytes, pcm.length - offsetBytes);
                        if (written < 0) {
                            return;
                        }
                        if (written == 0) {
                            break;
                        }
                        offsetBytes += written;
                    }
                }
            } catch (Throwable ignored) {
                markFailed(this);
            } finally {
                output = null;
                if (out != null) {
                    out.close();
                }
                if (source != null) {
                    source.close();
                }
            }
        }
    }
}
