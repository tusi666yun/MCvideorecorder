package cn.blockforge.generated.mod38575362;

import net.minecraftforge.common.ForgeConfigSpec;

/**
 * 客户端配置：标清 / 高清摄像机的分辨率与帧率。
 * <p>
 * 文件位置：{@code config/mod_38575362-client.toml}
 * <p>
 * 修改后一般需重启游戏（或至少重进世界）再录制才完全生效。
 * 宽高会自动收束到偶数，并限制在 {@link ModConstants#MAX_VIDEO_WIDTH}/{@link ModConstants#MAX_VIDEO_HEIGHT} 内。
 */
public final class VideoRecorderConfig {

    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.IntValue STANDARD_WIDTH;
    public static final ForgeConfigSpec.IntValue STANDARD_HEIGHT;
    public static final ForgeConfigSpec.IntValue STANDARD_FPS;

    public static final ForgeConfigSpec.IntValue HD_WIDTH;
    public static final ForgeConfigSpec.IntValue HD_HEIGHT;
    public static final ForgeConfigSpec.IntValue HD_FPS;

    public static final ForgeConfigSpec.DoubleValue STANDARD_JPEG_QUALITY;
    public static final ForgeConfigSpec.DoubleValue HD_JPEG_QUALITY;

    static {
        ForgeConfigSpec.Builder b = new ForgeConfigSpec.Builder();

        b.comment("游戏录像机 — 录制画质（仅影响新录制，已有录像不变）")
                .push("recording");

        b.comment("标清摄像机（普通摄像机）").push("standard");
        STANDARD_WIDTH = b.comment("宽度（像素，建议偶数，最大 " + ModConstants.MAX_VIDEO_WIDTH + "）")
                .defineInRange("width", 192, 16, ModConstants.MAX_VIDEO_WIDTH);
        STANDARD_HEIGHT = b.comment("高度（像素，建议偶数，最大 " + ModConstants.MAX_VIDEO_HEIGHT + "）")
                .defineInRange("height", 108, 16, ModConstants.MAX_VIDEO_HEIGHT);
        STANDARD_FPS = b.comment("帧率（越高越流畅，也越吃 CPU / 文件越大）")
                .defineInRange("fps", 8, 1, 60);
        STANDARD_JPEG_QUALITY = b.comment("内部处理用的 JPEG 质量参考 0.5~1.0（导出/兼容用）")
                .defineInRange("jpegQuality", 0.85D, 0.5D, 1.0D);
        b.pop();

        b.comment("高清摄像机").push("hd");
        HD_WIDTH = b.comment("宽度（像素，建议偶数，最大 " + ModConstants.MAX_VIDEO_WIDTH + "）")
                .defineInRange("width", 640, 16, ModConstants.MAX_VIDEO_WIDTH);
        HD_HEIGHT = b.comment("高度（像素，建议偶数，最大 " + ModConstants.MAX_VIDEO_HEIGHT + "）")
                .defineInRange("height", 360, 16, ModConstants.MAX_VIDEO_HEIGHT);
        HD_FPS = b.comment("帧率（越高越流畅，也越吃 CPU / 文件越大）")
                .defineInRange("fps", 12, 1, 60);
        HD_JPEG_QUALITY = b.comment("内部处理用的 JPEG 质量参考 0.5~1.0")
                .defineInRange("jpegQuality", 0.92D, 0.5D, 1.0D);
        b.pop();

        b.pop();
        SPEC = b.build();
    }

    private VideoRecorderConfig() {
    }

    /** 保证偶数且至少为 2，H.264 / 缩放更稳。 */
    public static int even(int v) {
        int x = Math.max(2, v);
        return x & ~1;
    }

    public static int standardWidth() {
        return even(STANDARD_WIDTH.get());
    }

    public static int standardHeight() {
        return even(STANDARD_HEIGHT.get());
    }

    public static int standardFps() {
        return Math.max(1, STANDARD_FPS.get());
    }

    public static float standardJpegQuality() {
        return STANDARD_JPEG_QUALITY.get().floatValue();
    }

    public static int hdWidth() {
        return even(HD_WIDTH.get());
    }

    public static int hdHeight() {
        return even(HD_HEIGHT.get());
    }

    public static int hdFps() {
        return Math.max(1, HD_FPS.get());
    }

    public static float hdJpegQuality() {
        return HD_JPEG_QUALITY.get().floatValue();
    }
}
