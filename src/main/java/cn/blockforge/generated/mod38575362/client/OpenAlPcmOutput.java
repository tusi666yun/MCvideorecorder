package cn.blockforge.generated.mod38575362.client;

import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * 走 Minecraft 已经初始化好的 OpenAL 设备播放 PCM。
 * <p>
 * 安卓（FCL/Pojav）上 android.media.AudioTrack 不一定能通过 Class.forName 访问，
 * 但 OpenAL 已经由游戏启动；这里用相对音源 + 自己算距离音量，避免依赖监听者坐标。
 */
final class OpenAlPcmOutput implements PcmOutput {
    private static final int BUFFER_COUNT = 4;

    private final int source;
    private final int[] buffers;
    private final int format;
    private final int sampleRate;
    private int nextBuffer;
    private boolean closed;

    private OpenAlPcmOutput(int source, int[] buffers, int format, int sampleRate) {
        this.source = source;
        this.buffers = buffers;
        this.format = format;
        this.sampleRate = sampleRate;
    }

    static boolean isAvailable() {
        try {
            return AL.getCapabilities() != null;
        } catch (Throwable ignored) {
            return false;
        }
    }

    static PcmOutput open(int sampleRate, int channels) {
        if (!isAvailable() || sampleRate <= 0 || channels <= 0) {
            return null;
        }
        try {
            int source = AL10.alGenSources();
            int error = AL10.alGetError();
            if (source == 0 || error != AL10.AL_NO_ERROR) {
                if (source != 0) {
                    AL10.alDeleteSources(source);
                }
                System.err.println("[VideoRecorder] OpenAL alGenSources failed, error=" + error);
                return null;
            }
            int[] buffers = new int[BUFFER_COUNT];
            for (int i = 0; i < buffers.length; i++) {
                buffers[i] = AL10.alGenBuffers();
            }
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(source);
                for (int buffer : buffers) {
                    if (buffer != 0) {
                        AL10.alDeleteBuffers(buffer);
                    }
                }
                return null;
            }
            int format = channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
            AL10.alSourcei(source, AL10.AL_SOURCE_RELATIVE, AL10.AL_TRUE);
            AL10.alSource3f(source, AL10.AL_POSITION, 0.0F, 0.0F, 0.0F);
            AL10.alSourcef(source, AL10.AL_GAIN, 1.0F);
            AL10.alSourcei(source, AL10.AL_LOOPING, AL10.AL_FALSE);
            if (AL10.alGetError() != AL10.AL_NO_ERROR) {
                AL10.alDeleteSources(source);
                for (int buffer : buffers) {
                    AL10.alDeleteBuffers(buffer);
                }
                return null;
            }
            System.out.println("[VideoRecorder] OpenAL audio output ready (" + sampleRate + " Hz, "
                    + channels + " ch)");
            return new OpenAlPcmOutput(source, buffers, format, sampleRate);
        } catch (Throwable t) {
            System.out.println("[VideoRecorder] OpenAL audio output unavailable: " + t);
            return null;
        }
    }

    @Override
    public void play() {
        if (closed) {
            return;
        }
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source);
        }
    }

    @Override
    public void pause() {
        if (!closed) {
            AL10.alSourcePause(source);
        }
    }

    @Override
    public void flush() {
        if (closed) {
            return;
        }
        AL10.alSourceStop(source);
        AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        nextBuffer = 0;
    }

    @Override
    public void setVolume(float volume) {
        if (!closed) {
            AL10.alSourcef(source, AL10.AL_GAIN, Math.max(0.0F, Math.min(1.0F, volume)));
        }
    }

    @Override
    public int write(byte[] data, int offset, int length) {
        if (closed || data == null || length <= 0) {
            return -1;
        }
        int buffer = acquireBuffer();
        if (buffer < 0) {
            return -1;
        }
        ByteBuffer pcm = MemoryUtil.memAlloc(length);
        try {
            pcm.put(data, offset, length);
            pcm.flip();
            AL10.alBufferData(buffer, format, pcm, sampleRate);
        } finally {
            MemoryUtil.memFree(pcm);
        }
        if (AL10.alGetError() != AL10.AL_NO_ERROR) {
            return -1;
        }
        AL10.alSourceQueueBuffers(source, buffer);
        if (AL10.alGetSourcei(source, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
            AL10.alSourcePlay(source);
        }
        return length;
    }

    private int acquireBuffer() {
        for (int attempt = 0; attempt < 200 && !closed; attempt++) {
            int processed = AL10.alGetSourcei(source, AL10.AL_BUFFERS_PROCESSED);
            if (processed > 0) {
                return AL10.alSourceUnqueueBuffers(source);
            }
            if (nextBuffer < buffers.length) {
                return buffers[nextBuffer++];
            }
            try {
                Thread.sleep(5L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
        }
        return -1;
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            AL10.alSourceStop(source);
        } catch (Throwable ignored) {
        }
        try {
            AL10.alSourcei(source, AL10.AL_BUFFER, 0);
        } catch (Throwable ignored) {
        }
        try {
            AL10.alDeleteSources(source);
        } catch (Throwable ignored) {
        }
        for (int buffer : buffers) {
            try {
                AL10.alDeleteBuffers(buffer);
            } catch (Throwable ignored) {
            }
        }
    }
}
