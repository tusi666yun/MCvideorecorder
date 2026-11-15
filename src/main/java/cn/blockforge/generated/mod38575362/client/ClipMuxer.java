package cn.blockforge.generated.mod38575362.client;

import org.jcodec.common.AudioFormat;
import org.jcodec.common.Codec;
import org.jcodec.common.DemuxerTrack;
import org.jcodec.common.DemuxerTrackMeta;
import org.jcodec.common.MuxerTrack;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;
import org.jcodec.common.model.Packet;
import org.jcodec.containers.mp4.demuxer.MP4Demuxer;
import org.jcodec.containers.mp4.muxer.MP4Muxer;
import org.jcodec.containers.mp4.muxer.PCMMP4MuxerTrack;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 停止录像后把 video.mp4 和 audio.wav 合成一个带音轨的 MP4。
 * <p>
 * 有 FFmpeg 时输出标准 AAC 音轨；没有时用 jcodec 复制 H.264 视频包并封装
 * PCM 音轨，保证本模组能播放，避免安卓上强依赖 FFmpeg。
 */
final class ClipMuxer {
    private ClipMuxer() {
    }

    static boolean mux(File clipDir, String clipId) {
        File video = new File(clipDir, "video.mp4");
        File audio = new File(clipDir, "audio.wav");
        if (!video.isFile() || video.length() < 100L || !audio.isFile() || audio.length() <= 44L) {
            return false;
        }
        ClipStore.dropInfo(clipId);
        ClipStore.dropCached(clipId);
        File temp = new File(clipDir, "video.muxing.mp4");
        try {
            String ffmpeg = findFfmpeg();
            if (ffmpeg != null && muxWithFfmpeg(ffmpeg, video, audio, temp)) {
                replace(temp, video);
                System.out.println("[VideoRecorder] muxed MP4 with AAC audio");
                return true;
            }
            if (muxWithJCodec(video, audio, temp)) {
                replace(temp, video);
                System.out.println("[VideoRecorder] muxed MP4 with PCM audio");
                return true;
            }
        } catch (Exception e) {
            System.err.println("[VideoRecorder] mux failed: " + e);
        }
        if (temp.exists()) {
            temp.delete();
        }
        return false;
    }

    private static boolean muxWithFfmpeg(String ffmpeg, File video, File audio, File output) {
        try {
            if (output.exists()) {
                output.delete();
            }
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpeg);
            cmd.add("-y");
            cmd.add("-hide_banner");
            cmd.add("-loglevel");
            cmd.add("error");
            cmd.add("-i");
            cmd.add(video.getAbsolutePath());
            cmd.add("-i");
            cmd.add(audio.getAbsolutePath());
            cmd.add("-map");
            cmd.add("0:v:0");
            cmd.add("-map");
            cmd.add("1:a:0");
            cmd.add("-c:v");
            cmd.add("copy");
            cmd.add("-c:a");
            cmd.add("aac");
            cmd.add("-b:a");
            cmd.add("128k");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add(output.getAbsolutePath());
            Process process = new ProcessBuilder(cmd).redirectErrorStream(true).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                while (reader.readLine() != null) {
                }
            }
            return process.waitFor() == 0 && output.isFile() && output.length() > 100L;
        } catch (Exception e) {
            System.err.println("[VideoRecorder] ffmpeg mux failed: " + e.getMessage());
            return false;
        }
    }

    private static boolean muxWithJCodec(File video, File audio, File output) {
        SeekableByteChannel in = null;
        SeekableByteChannel out = null;
        MP4Demuxer demuxer = null;
        WavAudioSource wav = null;
        try {
            if (output.exists()) {
                output.delete();
            }
            in = NIOUtils.readableChannel(video);
            demuxer = MP4Demuxer.createMP4Demuxer(in);
            if (demuxer.getVideoTracks().isEmpty()) {
                return false;
            }
            DemuxerTrack videoTrack = demuxer.getVideoTracks().get(0);
            DemuxerTrackMeta videoMeta = videoTrack.getMeta();
            if (videoMeta.getVideoCodecMeta() == null) {
                return false;
            }
            out = NIOUtils.writableChannel(output);
            MP4Muxer muxer = MP4Muxer.createMP4MuxerToChannel(out);
            MuxerTrack muxVideo = muxer.addVideoTrack(
                    videoMeta.getCodec() == null ? Codec.H264 : videoMeta.getCodec(),
                    videoMeta.getVideoCodecMeta());
            Packet packet;
            while ((packet = videoTrack.nextFrame()) != null) {
                muxVideo.addFrame(packet);
            }

            wav = WavAudioSource.open(audio);
            if (wav != null) {
                AudioFormat format = wav.outputChannels() == 1
                        ? AudioFormat.MONO_S16_LE(wav.sampleRate())
                        : AudioFormat.STEREO_S16_LE(wav.sampleRate());
                PCMMP4MuxerTrack muxAudio = muxer.addPCMAudioTrack(format);
                byte[] pcm;
                while ((pcm = wav.nextPcm()) != null) {
                    muxAudio.addSamples(ByteBuffer.wrap(pcm));
                }
            }
            muxer.finish();
            out.close();
            out = null;
            return output.isFile() && output.length() > 100L;
        } catch (Exception e) {
            System.err.println("[VideoRecorder] jcodec mux failed: " + e.getMessage());
            return false;
        } finally {
            try {
                if (wav != null) {
                    wav.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (out != null) {
                    out.close();
                }
            } catch (Exception ignored) {
            }
            try {
                if (demuxer != null) {
                    demuxer.close();
                } else if (in != null) {
                    in.close();
                }
            } catch (Exception ignored) {
            }
        }
    }

    private static void replace(File source, File target) throws Exception {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String findFfmpeg() {
        String[] candidates = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                ? new String[]{"ffmpeg.exe", "ffmpeg"} : new String[]{"ffmpeg"};
        for (String cmd : candidates) {
            try {
                Process process = new ProcessBuilder(cmd, "-version")
                        .redirectErrorStream(true).start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                    }
                }
                if (process.waitFor() == 0) {
                    return cmd;
                }
            } catch (Exception ignored) {
            }
        }
        return null;
    }
}
