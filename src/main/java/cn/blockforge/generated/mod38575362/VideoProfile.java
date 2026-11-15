package cn.blockforge.generated.mod38575362;

import java.util.Locale;

/**
 * 录像画质档位。默认值写在枚举里；实际录制宽高/帧率优先读 {@link VideoRecorderConfig}。
 */
public enum VideoProfile {
    /** 普通摄像机（默认 192×108，8 帧/秒，可在配置里改）。 */
    STANDARD("standard", "标清", 192, 108, 8, 0.85F),
    /** 高清摄像机（默认 640×360，12 帧/秒，可在配置里改）。 */
    HIGH_DEFINITION("hd", "高清", 640, 360, 12, 0.92F);

    private final String id;
    private final String label;
    /** 内置默认值（配置未加载或异常时回退）。 */
    private final int defaultWidth;
    private final int defaultHeight;
    private final int defaultFps;
    private final float defaultJpegQuality;

    VideoProfile(String id, String label, int width, int height, int fps, float jpegQuality) {
        this.id = id;
        this.label = label;
        this.defaultWidth = width;
        this.defaultHeight = height;
        this.defaultFps = fps;
        this.defaultJpegQuality = jpegQuality;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public int width() {
        try {
            return this == HIGH_DEFINITION ? VideoRecorderConfig.hdWidth() : VideoRecorderConfig.standardWidth();
        } catch (Throwable t) {
            return defaultWidth;
        }
    }

    public int height() {
        try {
            return this == HIGH_DEFINITION ? VideoRecorderConfig.hdHeight() : VideoRecorderConfig.standardHeight();
        } catch (Throwable t) {
            return defaultHeight;
        }
    }

    public int fps() {
        try {
            return this == HIGH_DEFINITION ? VideoRecorderConfig.hdFps() : VideoRecorderConfig.standardFps();
        } catch (Throwable t) {
            return Math.max(1, defaultFps);
        }
    }

    public float jpegQuality() {
        try {
            return this == HIGH_DEFINITION ? VideoRecorderConfig.hdJpegQuality() : VideoRecorderConfig.standardJpegQuality();
        } catch (Throwable t) {
            return defaultJpegQuality;
        }
    }

    /** 两帧之间至少要隔多少毫秒。 */
    public long frameIntervalMs() {
        return Math.max(1L, 1000L / Math.max(1, fps()));
    }

    public String resolution() {
        return width() + "×" + height();
    }

    /** 给玩家看的一行说明，例如「高清 640×360 · 12 帧/秒」。 */
    public String describe() {
        return label + " " + width() + "×" + height() + " · " + fps() + " 帧/秒";
    }

    public String frameName(int index) {
        return String.format(Locale.ROOT, "frame_%04d." + ModConstants.FRAME_EXTENSION, index);
    }

    public String legacyFrameName(int index) {
        return String.format(Locale.ROOT, "frame_%04d." + ModConstants.LEGACY_FRAME_EXTENSION, index);
    }

    public static VideoProfile byId(String id) {
        for (VideoProfile profile : values()) {
            if (profile.id.equals(id)) {
                return profile;
            }
        }
        return STANDARD;
    }

    /**
     * 按尺寸认出档位（用于旧片段）。
     * 先比当前配置尺寸，再比内置默认尺寸。
     */
    public static VideoProfile bySize(int width, int height) {
        for (VideoProfile profile : values()) {
            if (profile.width() == width && profile.height() == height) {
                return profile;
            }
        }
        for (VideoProfile profile : values()) {
            if (profile.defaultWidth == width && profile.defaultHeight == height) {
                return profile;
            }
        }
        return null;
    }
}
