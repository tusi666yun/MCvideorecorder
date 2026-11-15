package cn.blockforge.generated.mod38575362;

public final class ModConstants {
    public static final String MODID = "mod_38575362";
    /** 录像帧的存放格式：JPG 比 PNG 小一个数量级，高清档位全靠它。 */
    public static final String FRAME_EXTENSION = "jpg";
    /** 换格式之前录的老胶卷仍然是 PNG，读取时会自动回退去找它。 */
    public static final String LEGACY_FRAME_EXTENSION = "png";
    /** 仅用于拦截损坏或恶意片段的极端帧数，不再作为正常录像的时长限制。 */
    public static final int MAX_VIDEO_WIDTH = 1280;
    public static final int MAX_VIDEO_HEIGHT = 720;
    /** 仅拦截明显异常的数据；正常录像时长不再受此限制。 */
    public static final int MAX_STORED_FRAMES = 10_000_000;
    public static final int MIN_FRAMES = 3;
    public static final String TAG_CLIP = "vr_clip";
    public static final String TAG_FRAMES = "vr_frames";
    public static final String TAG_FPS = "vr_fps";
    public static final String TAG_DURATION = "vr_duration_ms";
    public static final String TAG_PROFILE = "vr_profile";

    private ModConstants() {
    }
}
