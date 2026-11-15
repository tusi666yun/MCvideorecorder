package cn.blockforge.generated.mod38575362.client;

/** 播放 PCM 的输出后端；安卓先用 AudioTrack，Windows 后端后续接入。 */
interface PcmOutput extends AutoCloseable {
    static PcmOutput open(int sampleRate, int channels) {
        PcmOutput openAl = OpenAlPcmOutput.open(sampleRate, channels);
        if (openAl != null) {
            return openAl;
        }
        return AndroidPcmOutput.open(sampleRate, channels);
    }

    static boolean isAvailable() {
        return OpenAlPcmOutput.isAvailable() || AndroidPcmOutput.isAvailable();
    }

    void play();

    void pause();

    void flush();

    void setVolume(float volume);

    int write(byte[] data, int offset, int length);

    @Override
    void close();
}
