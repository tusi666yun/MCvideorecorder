package cn.blockforge.generated.mod38575362.client;

import java.io.File;

/** 播放侧的统一 PCM 音频源：MP4/M4A 解码轨或录制生成的 WAV。 */
interface PcmAudioSource extends AutoCloseable {
    static PcmAudioSource open(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        String name = file.getName().toLowerCase(java.util.Locale.ROOT);
        if (name.endsWith(".wav")) {
            return WavAudioSource.open(file);
        }
        return Mp4AudioSource.open(file);
    }

    int sampleRate();

    int outputChannels();

    long durationMs();

    long positionMs();

    void seekToMs(long ms) throws Exception;

    byte[] nextPcm() throws Exception;

    @Override
    void close();
}
