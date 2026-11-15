package cn.blockforge.generated.mod38575362.client;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * 安卓端 PCM 输出：直接用 android.media.AudioTrack 反射建流式音轨。
 * <p>
 * 模组本身按普通 Java/Forge 编译，不能直接引用 android.*，所以这里全部走反射；
 * 在 Windows 上类不存在，open() 返回 null，后续再接 Windows 后端。
 */
final class AndroidPcmOutput implements PcmOutput {
    private static Boolean available;

    private final Object track;
    private final Method play;
    private final Method pause;
    private final Method flush;
    private final Method write;
    private final Method setVolume;
    private final Method stop;
    private final Method release;
    private boolean closed;

    private AndroidPcmOutput(Object track, Class<?> trackClass) throws Exception {
        this.track = track;
        this.play = trackClass.getMethod("play");
        this.pause = trackClass.getMethod("pause");
        this.flush = trackClass.getMethod("flush");
        this.write = trackClass.getMethod("write", byte[].class, int.class, int.class);
        this.setVolume = trackClass.getMethod("setVolume", float.class);
        this.stop = trackClass.getMethod("stop");
        this.release = trackClass.getMethod("release");
    }

    static boolean isAvailable() {
        if (available == null) {
            try {
                Class.forName("android.media.AudioTrack");
                available = Boolean.TRUE;
            } catch (Throwable ignored) {
                available = Boolean.FALSE;
            }
        }
        return available;
    }

    static PcmOutput open(int sampleRate, int channels) {
        if (!isAvailable() || sampleRate <= 0 || channels <= 0) {
            return null;
        }
        try {
            Class<?> trackClass = Class.forName("android.media.AudioTrack");
            int channelMask = channels == 1 ? 0x4 : 0xC; // CHANNEL_OUT_MONO / STEREO
            int encoding = 2; // ENCODING_PCM_16BIT
            int minBuffer = ((Number) trackClass.getMethod("getMinBufferSize",
                    int.class, int.class, int.class)
                    .invoke(null, sampleRate, channelMask, encoding)).intValue();
            int bufferSize = Math.max(Math.max(4096, minBuffer),
                    sampleRate * channels * 2 / 5); // 约 200ms
            Object track = buildTrack(trackClass, sampleRate, channelMask, encoding, bufferSize);
            if (track == null) {
                return null;
            }
            return new AndroidPcmOutput(track, trackClass);
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static Object buildTrack(Class<?> trackClass, int sampleRate, int channelMask,
                                     int encoding, int bufferSize) {
        try {
            Class<?> attributesClass = Class.forName("android.media.AudioAttributes");
            Class<?> attributesBuilderClass = Class.forName("android.media.AudioAttributes$Builder");
            Object attributesBuilder = attributesBuilderClass.getConstructor().newInstance();
            attributesBuilderClass.getMethod("setUsage", int.class).invoke(attributesBuilder, 1); // USAGE_MEDIA
            attributesBuilderClass.getMethod("setContentType", int.class).invoke(attributesBuilder, 2); // MUSIC
            Object attributes = attributesBuilderClass.getMethod("build").invoke(attributesBuilder);

            Class<?> formatClass = Class.forName("android.media.AudioFormat");
            Class<?> formatBuilderClass = Class.forName("android.media.AudioFormat$Builder");
            Object formatBuilder = formatBuilderClass.getConstructor().newInstance();
            formatBuilderClass.getMethod("setEncoding", int.class).invoke(formatBuilder, encoding);
            formatBuilderClass.getMethod("setSampleRate", int.class).invoke(formatBuilder, sampleRate);
            formatBuilderClass.getMethod("setChannelMask", int.class).invoke(formatBuilder, channelMask);
            Object format = formatBuilderClass.getMethod("build").invoke(formatBuilder);

            Class<?> trackBuilderClass = Class.forName("android.media.AudioTrack$Builder");
            Object trackBuilder = trackBuilderClass.getConstructor().newInstance();
            trackBuilderClass.getMethod("setAudioAttributes", attributesClass).invoke(trackBuilder, attributes);
            trackBuilderClass.getMethod("setAudioFormat", formatClass).invoke(trackBuilder, format);
            trackBuilderClass.getMethod("setTransferMode", int.class).invoke(trackBuilder, 1); // MODE_STREAM
            trackBuilderClass.getMethod("setBufferSizeInBytes", int.class).invoke(trackBuilder, bufferSize);
            return trackBuilderClass.getMethod("build").invoke(trackBuilder);
        } catch (Throwable ignored) {
            try {
                Constructor<?> constructor = trackClass.getConstructor(
                        int.class, int.class, int.class, int.class, int.class, int.class);
                return constructor.newInstance(3, sampleRate, channelMask, encoding, bufferSize, 1);
            } catch (Throwable ignored2) {
                return null;
            }
        }
    }

    @Override
    public void play() {
        if (!closed) {
            try {
                play.invoke(track);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void pause() {
        if (!closed) {
            try {
                pause.invoke(track);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void flush() {
        if (!closed) {
            try {
                flush.invoke(track);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void setVolume(float volume) {
        if (!closed) {
            try {
                setVolume.invoke(track, Math.max(0.0F, Math.min(1.0F, volume)));
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public int write(byte[] data, int offset, int length) {
        if (closed || data == null || length <= 0) {
            return -1;
        }
        try {
            return ((Number) write.invoke(track, data, offset, length)).intValue();
        } catch (Exception ignored) {
            return -1;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            pause.invoke(track);
        } catch (Exception ignored) {
        }
        try {
            flush.invoke(track);
        } catch (Exception ignored) {
        }
        try {
            stop.invoke(track);
        } catch (Exception ignored) {
        }
        try {
            release.invoke(track);
        } catch (Exception ignored) {
        }
    }
}
