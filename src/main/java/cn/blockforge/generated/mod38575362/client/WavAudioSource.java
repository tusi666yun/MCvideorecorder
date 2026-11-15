package cn.blockforge.generated.mod38575362.client;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;

/**
 * 录制生成的 WAV（PCM）音源。只支持 8/16-bit 单/双声道，正好覆盖录制器输出。
 */
final class WavAudioSource implements PcmAudioSource {
    private final RandomAccessFile file;
    private final long dataOffset;
    private final long dataLength;
    private final int sampleRate;
    private final int channels;
    private final int bits;
    private final int blockAlign;
    private long position;
    private long positionMs;
    private final int outputChannels;

    private WavAudioSource(RandomAccessFile file, long dataOffset, long dataLength,
                           int sampleRate, int channels, int bits, int blockAlign) {
        this.file = file;
        this.dataOffset = dataOffset;
        this.dataLength = dataLength;
        this.sampleRate = sampleRate;
        this.channels = channels;
        this.bits = bits;
        this.blockAlign = blockAlign;
        this.outputChannels = channels == 1 ? 1 : 2;
    }

    static WavAudioSource open(File file) {
        RandomAccessFile raf = null;
        try {
            raf = new RandomAccessFile(file, "r");
            byte[] riff = new byte[12];
            raf.readFully(riff);
            if (!new String(riff, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("RIFF")
                    || !new String(riff, 8, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("WAVE")) {
                raf.close();
                return null;
            }
            int sampleRate = 0;
            int channels = 0;
            int bits = 0;
            int blockAlign = 0;
            long dataOffset = -1L;
            long dataLength = 0L;
            while (true) {
                byte[] header = new byte[8];
                try {
                    raf.readFully(header);
                } catch (IOException e) {
                    break;
                }
                String id = new String(header, 0, 4, java.nio.charset.StandardCharsets.US_ASCII);
                long size = Integer.toUnsignedLong(readIntLE(header, 4));
                long next = raf.getFilePointer() + size + (size & 1L);
                if (id.equals("fmt ")) {
                    byte[] fmt = new byte[(int) Math.min(size, 64L)];
                    raf.readFully(fmt);
                    int format = readShortLE(fmt, 0) & 0xFFFF;
                    channels = readShortLE(fmt, 2) & 0xFFFF;
                    sampleRate = readIntLE(fmt, 4);
                    blockAlign = readShortLE(fmt, 12) & 0xFFFF;
                    bits = readShortLE(fmt, 14) & 0xFFFF;
                    if (format != 1) {
                        raf.close();
                        return null;
                    }
                } else if (id.equals("data")) {
                    dataOffset = raf.getFilePointer();
                    dataLength = size;
                    if (sampleRate > 0 && channels > 0 && bits > 0) {
                        break;
                    }
                }
                raf.seek(next);
            }
            if (dataOffset < 0L || sampleRate <= 0 || channels <= 0 || (bits != 8 && bits != 16)) {
                raf.close();
                return null;
            }
            if (blockAlign <= 0) {
                blockAlign = channels * bits / 8;
            }
            return new WavAudioSource(raf, dataOffset, dataLength, sampleRate, channels, bits, blockAlign);
        } catch (Exception e) {
            try {
                if (raf != null) {
                    raf.close();
                }
            } catch (Exception ignored) {
            }
            return null;
        }
    }

    private static int readIntLE(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16) | ((b[off + 3] & 0xFF) << 24);
    }

    private static short readShortLE(byte[] b, int off) {
        return (short) ((b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8));
    }

    @Override
    public int sampleRate() {
        return sampleRate;
    }

    @Override
    public int outputChannels() {
        return outputChannels;
    }

    @Override
    public long durationMs() {
        if (blockAlign <= 0) {
            return 0L;
        }
        return (dataLength / blockAlign) * 1000L / Math.max(1, sampleRate);
    }

    @Override
    public long positionMs() {
        return positionMs;
    }

    @Override
    public void seekToMs(long ms) throws Exception {
        long frame = Math.max(0L, ms) * sampleRate / 1000L;
        long offset = Math.min(dataLength, frame * blockAlign);
        position = offset;
        positionMs = Math.max(0L, ms);
        file.seek(dataOffset + offset);
    }

    @Override
    public byte[] nextPcm() throws Exception {
        if (position >= dataLength) {
            return null;
        }
        int max = 8192;
        long remaining = dataLength - position;
        int frames = (int) Math.min(max / blockAlign, remaining / blockAlign);
        if (frames <= 0) {
            return null;
        }
        byte[] raw = new byte[frames * blockAlign];
        file.readFully(raw);
        position += raw.length;
        positionMs = (position / blockAlign) * 1000L / Math.max(1, sampleRate);
        if (bits == 16 && channels <= 2) {
            return raw;
        }
        byte[] out = new byte[frames * outputChannels * 2];
        int dst = 0;
        for (int frame = 0; frame < frames; frame++) {
            int base = frame * blockAlign;
            int left;
            int right;
            if (bits == 8) {
                left = ((raw[base] & 0xFF) - 128) << 8;
                right = channels >= 2 ? ((raw[base + 1] & 0xFF) - 128) << 8 : left;
            } else {
                left = (short) ((raw[base] & 0xFF) | ((raw[base + 1] & 0xFF) << 8));
                right = channels >= 2
                        ? (short) ((raw[base + 2] & 0xFF) | ((raw[base + 3] & 0xFF) << 8))
                        : left;
            }
            out[dst++] = (byte) left;
            out[dst++] = (byte) (left >> 8);
            if (outputChannels == 2) {
                out[dst++] = (byte) right;
                out[dst++] = (byte) (right >> 8);
            }
        }
        return out;
    }

    @Override
    public void close() {
        try {
            file.close();
        } catch (Exception ignored) {
        }
    }
}
