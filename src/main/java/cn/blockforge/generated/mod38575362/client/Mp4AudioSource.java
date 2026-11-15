package cn.blockforge.generated.mod38575362.client;

import cn.blockforge.generated.mod38575362.ClipWire;
import org.jcodec.codecs.aac.AACDecoder;
import org.jcodec.codecs.aac.AACUtils;
import org.jcodec.codecs.mpa.Mp3Decoder;
import org.jcodec.common.AudioCodecMeta;
import org.jcodec.common.AudioFormat;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.AudioBuffer;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.boxes.AudioSampleEntry;
import org.jcodec.containers.mp4.demuxer.AbstractMP4DemuxerTrack;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;

import java.io.File;
import java.nio.ByteBuffer;

/**
 * 从 MP4/M4A 里读第一条可播放音轨（AAC / MP3 / PCM），解成 16-bit PCM。
 * <p>
 * 视频仍走原来的 JPG/MP4 画面路径，音频只借用同一个文件；这样导入的 MP4
 * 不需要把音轨重新编码，也不会改变现有画面格式。
 */
public final class Mp4AudioSource implements PcmAudioSource {
    private final MP4Demuxer demuxer;
    private final DemuxerTrack track;
    private final FrameDecoder decoder;
    private final int sampleRate;
    private final int outputChannels;
    private final long durationMs;
    private byte[] pendingPcm;
    private long discardUntilPts = -1L;
    private long positionMs;

    private Mp4AudioSource(MP4Demuxer demuxer, DemuxerTrack track, FrameDecoder decoder,
                           AudioFormat format, long durationMs, byte[] pendingPcm) {
        this.demuxer = demuxer;
        this.track = track;
        this.decoder = decoder;
        this.sampleRate = format.getSampleRate();
        this.outputChannels = format.getChannels() == 1 ? 1 : 2;
        this.durationMs = Math.max(0L, durationMs);
        this.pendingPcm = pendingPcm;
    }

    /**
     * 按音轨顺序尝试 AAC、MP3、PCM，返回第一条能解出 PCM 的音轨；都不行时返回 null。
     */
    public static Mp4AudioSource open(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return null;
        }
        SeekableByteChannel channel = null;
        MP4Demuxer demuxer = null;
        try {
            channel = NIOUtils.readableChannel(file);
            demuxer = MP4Demuxer.createMP4Demuxer(channel);
            for (DemuxerTrack candidate : demuxer.getAudioTracks()) {
                try {
                    DemuxerTrackMeta meta = candidate.getMeta();
                    Codec codec = meta.getCodec();
                    FrameDecoder decoder = createDecoder(candidate, meta, codec);
                    if (decoder == null) {
                        continue;
                    }
                    Packet firstPacket = candidate.nextFrame();
                    if (firstPacket == null) {
                        continue;
                    }
                    AudioBuffer first = decoder.decode(firstPacket.getData().duplicate());
                    if (first == null || first.getFormat() == null
                            || first.getFormat().getSampleRate() <= 0
                            || first.getFormat().getChannels() <= 0) {
                        continue;
                    }
                    AudioFormat format = first.getFormat();
                    byte[] pcm = toPcm16(first, format);
                    if (pcm.length == 0) {
                        continue;
                    }
                    long totalMs = (long) (meta.getTotalDuration() * 1000.0);
                    return new Mp4AudioSource(demuxer, candidate, decoder, format, totalMs, pcm);
                } catch (Exception ignored) {
                    // 这条轨解不了，继续试下一条。
                }
            }
        } catch (Throwable ignored) {
        }
        try {
            if (demuxer != null) {
                demuxer.close();
            } else if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static FrameDecoder createDecoder(DemuxerTrack candidate, DemuxerTrackMeta meta, Codec codec) {
        if (codec == Codec.AAC || (codec == null && hasCodecPrivate(candidate, meta))) {
            ByteBuffer codecPrivate = null;
            if (candidate instanceof AbstractMP4DemuxerTrack track
                    && track.getSampleEntries() != null && track.getSampleEntries().length > 0) {
                codecPrivate = AACUtils.getCodecPrivate(track.getSampleEntries()[0]);
            }
            if (codecPrivate == null) {
                codecPrivate = meta.getCodecPrivate();
            }
            if (codecPrivate != null && codecPrivate.hasRemaining()) {
                try {
                    AACDecoder aac = new AACDecoder(codecPrivate.duplicate());
                    return data -> aac.decodeFrame(data, null);
                } catch (Exception ignored) {
                }
            }
        }
        AudioCodecMeta audioMeta = meta.getAudioCodecMeta();
        boolean pcmTrack = codec == Codec.PCM
                || (audioMeta != null && audioMeta.isPCM())
                || (candidate instanceof AbstractMP4DemuxerTrack track
                && AudioSampleEntry.pcms.contains(track.getFourcc()));
        if (pcmTrack) {
            AudioFormat format = audioMeta == null ? null : audioMeta.getFormat();
            if (format != null && (format.getSampleSizeInBits() == 8 || format.getSampleSizeInBits() == 16)) {
                return data -> new AudioBuffer(data, format, format.bytesToFrames(data.remaining()));
            }
            return null;
        }
        if (codec == Codec.MP3 || codec == null) {
            return new Mp3FrameDecoder();
        }
        return null;
    }

    private static boolean hasCodecPrivate(DemuxerTrack candidate, DemuxerTrackMeta meta) {
        if (meta.getCodecPrivate() != null && meta.getCodecPrivate().hasRemaining()) {
            return true;
        }
        if (candidate instanceof AbstractMP4DemuxerTrack track
                && track.getSampleEntries() != null && track.getSampleEntries().length > 0) {
            ByteBuffer codecPrivate = AACUtils.getCodecPrivate(track.getSampleEntries()[0]);
            return codecPrivate != null && codecPrivate.hasRemaining();
        }
        return false;
    }

    public int sampleRate() {
        return sampleRate;
    }

    public int outputChannels() {
        return outputChannels;
    }

    public long durationMs() {
        return durationMs;
    }

    public long positionMs() {
        return positionMs;
    }

    /**
     * 跳到指定毫秒。能 seek 就跳到 AAC 帧，不能则从头解到目标；随后调用
     * nextPcm() 时会丢掉目标之前的包。
     */
    public void seekToMs(long ms) throws Exception {
        long target = Math.max(0L, ms);
        positionMs = target;
        pendingPcm = null;
        discardUntilPts = -1L;
        if (!(track instanceof AbstractMP4DemuxerTrack mp4Track)) {
            return;
        }
        long timescale = Math.max(1L, mp4Track.getTimescale());
        long pts = target * timescale / 1000L;
        if (!mp4Track.seekPts(pts)) {
            mp4Track.gotoFrame(0L);
        }
        discardUntilPts = pts;
    }

    /**
     * 取下一段 PCM。返回 null 表示音轨结束。
     */
    public byte[] nextPcm() throws Exception {
        if (pendingPcm != null) {
            byte[] data = pendingPcm;
            pendingPcm = null;
            return data;
        }
        for (int attempt = 0; attempt < 8; attempt++) {
            Packet packet = track.nextFrame();
            if (packet == null) {
                return null;
            }
            if (packet.getPts() >= 0L && track instanceof AbstractMP4DemuxerTrack mp4Track) {
                positionMs = packet.getPts() * 1000L / Math.max(1L, mp4Track.getTimescale());
            }
            if (discardUntilPts >= 0L && packet.getPts() < discardUntilPts) {
                continue;
            }
            discardUntilPts = -1L;
            AudioBuffer buffer = decoder.decode(packet.getData().duplicate());
            if (buffer == null || buffer.getData() == null || buffer.getFormat() == null) {
                continue;
            }
            byte[] pcm = toPcm16(buffer, buffer.getFormat());
            if (pcm.length > 0) {
                return pcm;
            }
        }
        return null;
    }

    private static byte[] toPcm16(AudioBuffer buffer, AudioFormat format) {
        ByteBuffer src = buffer.getData().duplicate();
        int bits = format.getSampleSizeInBits();
        int channels = Math.max(1, format.getChannels());
        int outChannels = channels == 1 ? 1 : 2;
        if (bits == 16) {
            int frames = src.remaining() / (2 * channels);
            byte[] out = new byte[frames * outChannels * 2];
            int dst = 0;
            boolean bigEndian = format.isBigEndian();
            for (int frame = 0; frame < frames; frame++) {
                int base = src.position() + frame * channels * 2;
                int left = readSample16(src, base, bigEndian);
                if (outChannels == 1) {
                    writeSample16(out, dst, left);
                    dst += 2;
                } else {
                    int right = channels >= 2 ? readSample16(src, base + 2, bigEndian) : left;
                    writeSample16(out, dst, left);
                    writeSample16(out, dst + 2, right);
                    dst += 4;
                }
            }
            return out;
        }
        if (bits == 8) {
            int frames = src.remaining() / channels;
            byte[] out = new byte[frames * outChannels * 2];
            int dst = 0;
            for (int frame = 0; frame < frames; frame++) {
                int base = src.position() + frame * channels;
                int left = (src.get(base) & 0xFF) - 128;
                left <<= 8;
                if (outChannels == 1) {
                    writeSample16(out, dst, left);
                    dst += 2;
                } else {
                    int right = channels >= 2 ? ((src.get(base + 1) & 0xFF) - 128) << 8 : left;
                    writeSample16(out, dst, left);
                    writeSample16(out, dst + 2, right);
                    dst += 4;
                }
            }
            return out;
        }
        return new byte[0];
    }

    private static int readSample16(ByteBuffer src, int index, boolean bigEndian) {
        int a = src.get(index) & 0xFF;
        int b = src.get(index + 1) & 0xFF;
        return bigEndian ? (short) ((a << 8) | b) : (short) ((b << 8) | a);
    }

    private static void writeSample16(byte[] out, int index, int sample) {
        out[index] = (byte) sample;
        out[index + 1] = (byte) (sample >> 8);
    }

    @Override
    public void close() {
        try {
            demuxer.close();
        } catch (Exception ignored) {
        }
    }

    /** 当前片段可用的音频源：优先 video.mp4，其次导入时保留的音频副本。 */
    private interface FrameDecoder {
        AudioBuffer decode(ByteBuffer data) throws Exception;
    }

    /**
     * jcodec 的 Mp3Decoder 需要调用方提供输出缓冲，而且返回的 AudioBuffer 里
     * format 是 null；这里补上从第一帧头解析出的采样格式。
     */
    private static final class Mp3FrameDecoder implements FrameDecoder {
        private final Mp3Decoder decoder = new Mp3Decoder();
        private AudioFormat format;

        @Override
        public AudioBuffer decode(ByteBuffer data) throws Exception {
            if (format == null) {
                AudioCodecMeta meta = decoder.getCodecMeta(data.duplicate());
                if (meta == null || meta.getFormat() == null) {
                    return null;
                }
                format = meta.getFormat();
            }
            ByteBuffer output = ByteBuffer.allocate(32768);
            AudioBuffer decoded = decoder.decodeFrame(data, output);
            if (decoded == null || decoded.getData() == null) {
                return null;
            }
            ByteBuffer pcm = decoded.getData();
            return new AudioBuffer(pcm, format, format.bytesToFrames(pcm.remaining()));
        }
    }

    public static File findAudioFile(String clipId) {
        if (!ClipWire.isSafeClipName(clipId)) {
            return null;
        }
        File dir = new File(new File(net.minecraft.client.Minecraft.getInstance().gameDirectory,
                "videorecorder_clips"), clipId);
        String[] names = {"audio_source.m4a", "audio_source.mp4", "audio.wav", "video.mp4"};
        for (String name : names) {
            File file = new File(dir, name);
            if (file.isFile() && file.length() > 0L) {
                return file;
            }
        }
        return null;
    }
}
