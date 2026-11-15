package cn.blockforge.generated.mod38575362.client;

import com.mojang.blaze3d.audio.Channel;
import com.mojang.blaze3d.audio.SoundBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.sounds.ChannelAccess;
import net.minecraft.client.sounds.SoundBufferLibrary;
import net.minecraft.client.sounds.SoundEngine;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraftforge.fml.util.ObfuscationReflectionHelper;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

import javax.sound.sampled.AudioFormat;
import java.io.File;
import java.io.RandomAccessFile;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.locks.LockSupport;

/**
 * 录制 Minecraft 的声音引擎 PCM。
 * <p>
 * 第一批先覆盖静态音效：从 SoundEngine 的活动 SoundInstance 找到对应
 * SoundBuffer，按 OpenAL source 的播放偏移、实例音量、距离和类别音量混音。
 * 背景音乐/流式音频走 SoundBufferLibrary.getStream，不在这批范围内。
 */
final class GameAudioRecorder {
    private static final int OUTPUT_RATE = 48000;
    private static final int OUTPUT_CHANNELS = 2;
    private static final int BLOCK_FRAMES = 960; // 20ms
    private static final long BLOCK_NANOS = 1_000_000_000L * BLOCK_FRAMES / OUTPUT_RATE;

    private static Field fSoundEngine;
    private static Field fInstanceToChannel;
    private static Field fSoundBuffers;
    private static Field fExecutor;
    private static Field fCache;
    private static Field fHandleChannel;
    private static Field fChannelSource;
    private static Field fBufferData;
    private static Field fBufferFormat;
    private static boolean reflectionReady;

    private static volatile boolean running;
    private static volatile List<Voice> snapshot = Collections.emptyList();
    private static volatile long snapshotNanos;
    private static Thread thread;
    private static RandomAccessFile output;
    private static long dataBytes;
    private static long logAt;
    private static boolean snapshotErrorLogged;

    private GameAudioRecorder() {
    }

    static synchronized void start(File file) {
        stop();
        try {
            initReflection();
            if (!reflectionReady) {
                System.err.println("[VideoRecorder] game audio tap unavailable (reflection)");
                return;
            }
            output = new RandomAccessFile(file, "rw");
            output.setLength(0L);
            output.write(new byte[44]);
            dataBytes = 0L;
            logAt = 1L;
            snapshotErrorLogged = false;
            snapshot = Collections.emptyList();
            snapshotNanos = 0L;
            running = true;
            thread = new Thread(GameAudioRecorder::run, "videorecorder-game-audio");
            thread.setDaemon(true);
            thread.start();
            System.out.println("[VideoRecorder] game audio recording -> " + file.getName());
        } catch (Throwable t) {
            System.err.println("[VideoRecorder] game audio start failed: " + t);
            running = false;
            closeOutput();
        }
    }

    static synchronized void stop() {
        if (!running && thread == null) {
            return;
        }
        running = false;
        Thread t = thread;
        thread = null;
        if (t != null) {
            try {
                t.join(1500L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        finishWav();
        if (logAt != 0L) {
            System.out.println("[VideoRecorder] game audio recorded " + dataBytes
                    + " bytes, voices=" + snapshot.size());
            logAt = 0L;
        }
        closeOutput();
    }

    static boolean isRecording() {
        return running;
    }

    /** 客户端 tick 调用：在声音线程上刷新活动音效快照。 */
    static void tick() {
        if (!running || !reflectionReady) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getSoundManager() == null) {
            return;
        }
        try {
            Object engine = fSoundEngine.get(minecraft.getSoundManager());
            if (engine == null) {
                return;
            }
            Executor executor = (Executor) fExecutor.get(engine);
            if (executor == null) {
                snapshot = buildSnapshot(engine);
                snapshotNanos = System.nanoTime();
            } else {
                executor.execute(() -> {
                    snapshot = buildSnapshot(engine);
                    snapshotNanos = System.nanoTime();
                });
            }
        } catch (Throwable t) {
            if (!snapshotErrorLogged) {
                snapshotErrorLogged = true;
                System.err.println("[VideoRecorder] game audio snapshot failed: " + t);
            }
        }
    }

    private static void initReflection() throws Exception {
        if (reflectionReady) {
            return;
        }
        fSoundEngine = ObfuscationReflectionHelper.findField(
                net.minecraft.client.sounds.SoundManager.class, "f_120349_");
        fInstanceToChannel = ObfuscationReflectionHelper.findField(
                SoundEngine.class, "f_120226_");
        fSoundBuffers = ObfuscationReflectionHelper.findField(SoundEngine.class, "f_120222_");
        fExecutor = ObfuscationReflectionHelper.findField(SoundEngine.class, "f_120223_");
        fCache = ObfuscationReflectionHelper.findField(SoundBufferLibrary.class, "f_120190_");
        fHandleChannel = ObfuscationReflectionHelper.findField(
                ChannelAccess.ChannelHandle.class, "f_120146_");
        fChannelSource = ObfuscationReflectionHelper.findField(Channel.class, "f_83642_");
        fBufferData = ObfuscationReflectionHelper.findField(SoundBuffer.class, "f_83793_");
        fBufferFormat = ObfuscationReflectionHelper.findField(SoundBuffer.class, "f_83794_");
        reflectionReady = true;
    }

    @SuppressWarnings("unchecked")
    private static List<Voice> buildSnapshot(Object engine) {
        try {
            Map<SoundInstance, ChannelAccess.ChannelHandle> instances =
                    (Map<SoundInstance, ChannelAccess.ChannelHandle>) fInstanceToChannel.get(engine);
            Object buffersObject = fSoundBuffers.get(engine);
            if (instances == null || buffersObject == null) {
                return Collections.emptyList();
            }
            Map<ResourceLocation, CompletableFuture<SoundBuffer>> cache =
                    (Map<ResourceLocation, CompletableFuture<SoundBuffer>>) fCache.get(buffersObject);
            if (cache == null) {
                return Collections.emptyList();
            }
            Minecraft minecraft = Minecraft.getInstance();
            double listenerX = 0.0D;
            double listenerY = 0.0D;
            double listenerZ = 0.0D;
            if (minecraft.player != null) {
                listenerX = minecraft.player.getX();
                listenerY = minecraft.player.getY();
                listenerZ = minecraft.player.getZ();
            }
            float master = minecraft.options.getSoundSourceVolume(SoundSource.MASTER);
            long now = System.nanoTime();
            List<Voice> voices = new ArrayList<>();
            for (Map.Entry<SoundInstance, ChannelAccess.ChannelHandle> entry : instances.entrySet()) {
                SoundInstance instance = entry.getKey();
                ChannelAccess.ChannelHandle handle = entry.getValue();
                if (handle == null) {
                    continue;
                }
                Channel channel = (Channel) fHandleChannel.get(handle);
                if (channel == null) {
                    continue;
                }
                int source = fChannelSource.getInt(channel);
                if (source == 0) {
                    continue;
                }
                int state = AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE);
                if (state != AL10.AL_PLAYING && state != AL10.AL_PAUSED) {
                    continue;
                }
                Sound sound = instance.getSound();
                if (sound == null || sound.shouldStream()) {
                    continue;
                }
                ResourceLocation path = sound.getPath();
                CompletableFuture<SoundBuffer> future = cache.get(path);
                SoundBuffer buffer = future == null ? null : future.getNow(null);
                if (buffer == null) {
                    continue;
                }
                ByteBuffer data = ((ByteBuffer) fBufferData.get(buffer)).duplicate();
                data.position(0);
                AudioFormat format = (AudioFormat) fBufferFormat.get(buffer);
                if (format == null || (format.getSampleSizeInBits() != 8 && format.getSampleSizeInBits() != 16)) {
                    continue;
                }
                int offset = AL10.alGetSourcei(source, AL11.AL_SAMPLE_OFFSET);
                float category = minecraft.options.getSoundSourceVolume(instance.getSource());
                float gain = instance.getVolume() * (instance.getSource() == SoundSource.MASTER ? 1.0F : category) * master;
                if (!instance.isRelative() && instance.getAttenuation() != SoundInstance.Attenuation.NONE) {
                    double dx = instance.getX() - listenerX;
                    double dy = instance.getY() - listenerY;
                    double dz = instance.getZ() - listenerZ;
                    double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
                    double range = Math.max(1.0D, sound.getAttenuationDistance());
                    gain *= (float) Math.max(0.0D, 1.0D - distance / range);
                }
                if (gain <= 0.001F) {
                    continue;
                }
                voices.add(new Voice(data, format, offset, gain,
                        Math.max(0.01F, instance.getPitch()), instance.isLooping(), now));
            }
            return voices;
        } catch (Throwable t) {
            if (!snapshotErrorLogged) {
                snapshotErrorLogged = true;
                System.err.println("[VideoRecorder] game audio build snapshot failed: " + t);
            }
            return Collections.emptyList();
        }
    }

    private static void run() {
        long nextBlock = System.nanoTime();
        byte[] block = new byte[BLOCK_FRAMES * OUTPUT_CHANNELS * 2];
        while (running) {
            long now = System.nanoTime();
            if (now < nextBlock) {
                LockSupport.parkNanos(nextBlock - now);
                continue;
            }
            mixBlock(nextBlock, block);
            try {
                output.write(block);
                dataBytes += block.length;
            } catch (Exception e) {
                System.err.println("[VideoRecorder] game audio write failed: " + e);
                running = false;
                break;
            }
            nextBlock += BLOCK_NANOS;
            if (now - nextBlock > 1_000_000_000L) {
                nextBlock = now;
            }
        }
    }

    private static void mixBlock(long blockStartNanos, byte[] block) {
        List<Voice> voices = snapshot;
        long snapAt = snapshotNanos;
        float[] mixLeft = new float[BLOCK_FRAMES];
        float[] mixRight = new float[BLOCK_FRAMES];
        if (snapAt != 0L && blockStartNanos - snapAt < 1_000_000_000L) {
            for (Voice voice : voices) {
                mixVoice(voice, blockStartNanos, mixLeft, mixRight);
            }
        }
        int out = 0;
        for (int i = 0; i < BLOCK_FRAMES; i++) {
            int left = clamp16(mixLeft[i]);
            int right = clamp16(mixRight[i]);
            block[out++] = (byte) left;
            block[out++] = (byte) (left >> 8);
            block[out++] = (byte) right;
            block[out++] = (byte) (right >> 8);
        }
    }

    private static void mixVoice(Voice voice, long blockStartNanos, float[] left, float[] right) {
        double elapsed = Math.max(0.0D, (blockStartNanos - voice.snapshotNanos) / 1_000_000_000.0D);
        double sourcePos = voice.sourceOffset + elapsed * voice.sampleRate * voice.pitch;
        double step = voice.sampleRate / (double) OUTPUT_RATE * voice.pitch;
        int channels = voice.channels;
        int bytes = voice.bits / 8;
        int frameSize = channels * bytes;
        if (frameSize <= 0 || voice.frames <= 0) {
            return;
        }
        for (int i = 0; i < BLOCK_FRAMES; i++) {
            long frame = (long) (sourcePos + i * step);
            if (voice.looping) {
                frame = Math.floorMod(frame, voice.frames);
            } else if (frame < 0L || frame >= voice.frames) {
                break;
            }
            int base = (int) (frame * frameSize);
            int l = readSample(voice, base, 0);
            int r = channels >= 2 ? readSample(voice, base, 1) : l;
            left[i] += l * voice.gain;
            right[i] += r * voice.gain;
        }
    }

    private static int readSample(Voice voice, int base, int channel) {
        int bytes = voice.bits / 8;
        int index = base + channel * bytes;
        if (index < 0 || index + bytes > voice.data.limit()) {
            return 0;
        }
        if (voice.bits == 16) {
            int a = voice.data.get(index) & 0xFF;
            int b = voice.data.get(index + 1) & 0xFF;
            return voice.bigEndian ? (short) ((a << 8) | b) : (short) ((b << 8) | a);
        }
        return ((voice.data.get(index) & 0xFF) - 128) << 8;
    }

    private static int clamp16(float value) {
        int v = (int) value;
        return Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, v));
    }

    private static void finishWav() {
        if (output == null) {
            return;
        }
        try {
            output.seek(0L);
            output.write(new byte[]{'R', 'I', 'F', 'F'});
            writeInt((int) (36L + dataBytes));
            output.write(new byte[]{'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(16);
            writeShort(1);
            writeShort(OUTPUT_CHANNELS);
            writeInt(OUTPUT_RATE);
            writeInt(OUTPUT_RATE * OUTPUT_CHANNELS * 2);
            writeShort(OUTPUT_CHANNELS * 2);
            writeShort(16);
            output.write(new byte[]{'d', 'a', 't', 'a'});
            writeInt((int) dataBytes);
            output.seek(output.length());
            output.getFD().sync();
        } catch (Exception e) {
            System.err.println("[VideoRecorder] game audio finalize failed: " + e);
        }
    }

    private static void writeInt(int value) throws Exception {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
        output.write((value >> 16) & 0xFF);
        output.write((value >> 24) & 0xFF);
    }

    private static void writeShort(int value) throws Exception {
        output.write(value & 0xFF);
        output.write((value >> 8) & 0xFF);
    }

    private static void closeOutput() {
        try {
            if (output != null) {
                output.close();
            }
        } catch (Exception ignored) {
        }
        output = null;
    }

    private static final class Voice {
        final ByteBuffer data;
        final int sampleRate;
        final int channels;
        final int bits;
        final boolean bigEndian;
        final int frames;
        final int sourceOffset;
        final float gain;
        final float pitch;
        final boolean looping;
        final long snapshotNanos;

        Voice(ByteBuffer data, AudioFormat format, int sourceOffset, float gain, float pitch,
              boolean looping, long snapshotNanos) {
            this.data = data;
            this.sampleRate = Math.round(format.getSampleRate());
            this.channels = format.getChannels();
            this.bits = format.getSampleSizeInBits();
            this.bigEndian = format.isBigEndian();
            int frameSize = this.channels * this.bits / 8;
            this.frames = frameSize <= 0 ? 0 : data.remaining() / frameSize;
            this.sourceOffset = Math.max(0, sourceOffset);
            this.gain = gain;
            this.pitch = pitch;
            this.looping = looping;
            this.snapshotNanos = snapshotNanos;
        }
    }
}
