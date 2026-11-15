package cn.blockforge.generated.mod38575362;

import java.util.Locale;

/**
 * 录像在网络与硬盘上的公共约定：客户端和服务端都走这一套规则，
 * 免得一端按名字过滤、另一端按内容识别，拼起来反而对不上。
 *
 * <p>这个类只碰字符串和数字，不引用任何渲染相关的东西，专用服务端可以安全加载。
 */
public final class ClipWire {
    /** 一帧 JPG 的上限：正常高清一帧几十 KB，留足余量只为拦住坏文件。 */
    public static final int MAX_FRAME_BYTES = 512 * 1024;
    /** 单段录像容量上限已取消：只受服务器磁盘空间限制。 */
    public static final long MAX_CLIP_BYTES = Long.MAX_VALUE;
    /** 整台服务器的录像库容量上限已取消：只受服务器磁盘空间限制。 */
    public static final long MAX_SERVER_BYTES = Long.MAX_VALUE;
    /** meta.json 最多读这么多字符，防止有人塞一个巨型文件进来。 */
    public static final int MAX_META_CHARS = 8192;
    /** 片段文件夹名的长度上限（文件系统本身的限制）。 */
    public static final int MAX_CLIP_NAME_CHARS = 255;

    private ClipWire() {
    }

    /**
     * 文件夹名可以做显示名，也可以直接当网络上的片段编号，但必须挡住路径穿越：
     * 除 {@code / \ :} 与控制字符以外一律放行，中文、空格都当正常名字。
     */
    public static boolean isSafeClipName(String clip) {
        if (clip == null || clip.isEmpty() || clip.length() > MAX_CLIP_NAME_CHARS) {
            return false;
        }
        if (clip.equals(".") || clip.equals("..")) {
            return false;
        }
        for (int i = 0; i < clip.length(); i++) {
            char c = clip.charAt(i);
            if (c == '/' || c == '\\' || c == ':' || c == '\0' || c < 32) {
                return false;
            }
        }
        return true;
    }

    /** 文件名是不是我们认识的图片后缀（只看后缀，不解码）。 */
    public static boolean looksLikeImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }

    /**
     * 帧文件名自然排序：数字段按数值比大小，{@code frame_2} 排在 {@code frame_10} 前面。
     * 服务端按内容数帧、客户端按内容取帧用的是同一份逻辑，序号才对得上。
     */
    public static int compareFrameNames(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char cx = a.charAt(i);
            char cy = b.charAt(j);
            if (Character.isDigit(cx) && Character.isDigit(cy)) {
                int sx = i;
                int sy = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String dx = stripZeros(a.substring(sx, i));
                String dy = stripZeros(b.substring(sy, j));
                if (dx.length() != dy.length()) {
                    return dx.length() - dy.length();
                }
                int cmp = dx.compareTo(dy);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (cx != cy) {
                    return cx - cy;
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    private static String stripZeros(String digits) {
        int k = 0;
        while (k < digits.length() - 1 && digits.charAt(k) == '0') {
            k++;
        }
        return digits.substring(k);
    }

    /** 服务器存放帧的规范文件名，和客户端摄像机写出的一致。 */
    public static String canonicalFrameName(int index) {
        return String.format(Locale.ROOT, "frame_%04d." + ModConstants.FRAME_EXTENSION, index);
    }

    /** 换 JPG 格式之前那批 PNG 帧的文件名。 */
    public static String legacyFrameName(int index) {
        return String.format(Locale.ROOT, "frame_%04d." + ModConstants.LEGACY_FRAME_EXTENSION, index);
    }

    /** 从服务器下载来的帧缓存副本标记，只有带这个文件的目录才允许被服务端删掉时一并清理。 */
    public static String netMarker() {
        return ".vr_from_server";
    }
}
