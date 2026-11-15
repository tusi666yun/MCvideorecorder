package cn.blockforge.generated.mod38575362.client;

import com.mojang.blaze3d.platform.NativeImage;
import org.jcodec.api.awt.AWTFrameGrab;
import org.jcodec.common.io.NIOUtils;
import org.jcodec.common.io.SeekableByteChannel;

import java.awt.image.BufferedImage;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 从 video.mp4 取帧，带：
 * <ul>
 *   <li>顺序播放缓存（最近若干帧）</li>
 *   <li>关键帧锚点（读 seek_index.txt，跳转时先到附近关键帧再前进）</li>
 *   <li>预取下一帧</li>
 * </ul>
 */
public final class Mp4FrameSource implements AutoCloseable {

    private static final int SEQ_CACHE_SIZE = 12;
    private static final int KEY_CACHE_SIZE = 8;

    private final File file;
    private SeekableByteChannel channel;
    private AWTFrameGrab grab;
    private int nextIndex = 0;

    /** 顺序缓存：按插入顺序淘汰。 */
    private final Map<Integer, NativeImage> seqCache = new LinkedHashMap<>(SEQ_CACHE_SIZE + 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, NativeImage> eldest) {
            if (size() > SEQ_CACHE_SIZE) {
                NativeImage img = eldest.getValue();
                if (img != null && !keyCache.containsKey(eldest.getKey())) {
                    img.close();
                }
                return true;
            }
            return false;
        }
    };

    /** 关键帧缓存（不轻易淘汰）。 */
    private final Map<Integer, NativeImage> keyCache = new LinkedHashMap<>(KEY_CACHE_SIZE + 2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Integer, NativeImage> eldest) {
            if (size() > KEY_CACHE_SIZE) {
                NativeImage img = eldest.getValue();
                if (img != null) {
                    img.close();
                }
                return true;
            }
            return false;
        }
    };

    private final List<Integer> keyframes = new ArrayList<>();
    private int keyframeEvery = EncodeSession.KEYFRAME_EVERY;

    public Mp4FrameSource(File mp4) throws Exception {
        this.file = mp4;
        this.channel = NIOUtils.readableChannel(mp4);
        this.grab = AWTFrameGrab.createAWTFrameGrab(channel);
        loadSeekIndex(mp4.getParentFile());
    }

    private void loadSeekIndex(File dir) {
        File idx = new File(dir, "seek_index.txt");
        if (!idx.isFile()) {
            return;
        }
        try (BufferedReader r = new BufferedReader(new FileReader(idx))) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.startsWith("keyframe_every=")) {
                    keyframeEvery = Math.max(1, Integer.parseInt(line.substring("keyframe_every=".length()).trim()));
                } else if (line.matches("\\d+")) {
                    keyframes.add(Integer.parseInt(line));
                }
            }
        } catch (Exception ignored) {
        }
        if (keyframes.isEmpty()) {
            for (int i = 0; i < 100000; i += keyframeEvery) {
                keyframes.add(i);
                if (i > 0 && keyframes.size() > 4000) {
                    break;
                }
            }
        }
    }

    public synchronized NativeImage getFrame(int index) throws Exception {
        if (index < 0) {
            return null;
        }
        NativeImage hit = seqCache.get(index);
        if (hit != null) {
            return copyImage(hit);
        }
        hit = keyCache.get(index);
        if (hit != null) {
            return copyImage(hit);
        }

        // 需要解码：尽量顺序；大跨度跳转则重置到最近关键帧锚点再前进
        if (index != nextIndex) {
            if (index < nextIndex || index > nextIndex + 8) {
                int anchor = nearestKeyframeAtOrBefore(index);
                reopen();
                for (int i = 0; i < anchor; i++) {
                    BufferedImage skip = grab.getFrame();
                    if (skip == null) {
                        nextIndex = i;
                        return null;
                    }
                    // 关键帧写入 keyCache
                    if (i % keyframeEvery == 0) {
                        putKey(i, bufferedToNative(skip));
                    }
                }
                nextIndex = anchor;
            }
            // 从 nextIndex 前进到 index
            while (nextIndex < index) {
                BufferedImage skip = grab.getFrame();
                if (skip == null) {
                    return null;
                }
                NativeImage n = bufferedToNative(skip);
                putSeq(nextIndex, n);
                if (nextIndex % keyframeEvery == 0) {
                    putKey(nextIndex, copyImage(n));
                }
                nextIndex++;
            }
        }

        BufferedImage img = grab.getFrame();
        if (img == null) {
            return null;
        }
        NativeImage nativeImage = bufferedToNative(img);
        putSeq(index, nativeImage);
        if (index % keyframeEvery == 0) {
            putKey(index, copyImage(nativeImage));
        }
        nextIndex = index + 1;

        // 预取：尝试再解 1～2 帧进缓存（仍持有锁，短片可接受）
        prefetchLocked(2);

        return copyImage(nativeImage);
    }

    /** 外部可调用：预取从 from 起的若干帧。 */
    public synchronized void prefetch(int from, int count) {
        if (count <= 0) {
            return;
        }
        try {
            if (from != nextIndex && from > nextIndex && from <= nextIndex + 4) {
                // 小步前进
            } else if (from != nextIndex) {
                return; // 避免预取打乱顺序位置
            }
            for (int i = 0; i < count; i++) {
                int idx = nextIndex;
                if (seqCache.containsKey(idx) || keyCache.containsKey(idx)) {
                    // 已有则假装消费？保持 nextIndex 与文件指针一致较难，仅当 nextIndex 匹配时解
                }
                BufferedImage img = grab.getFrame();
                if (img == null) {
                    break;
                }
                NativeImage n = bufferedToNative(img);
                putSeq(idx, n);
                if (idx % keyframeEvery == 0) {
                    putKey(idx, copyImage(n));
                }
                nextIndex = idx + 1;
            }
        } catch (Exception ignored) {
        }
    }

    private void prefetchLocked(int count) {
        try {
            for (int i = 0; i < count; i++) {
                int idx = nextIndex;
                BufferedImage img = grab.getFrame();
                if (img == null) {
                    break;
                }
                NativeImage n = bufferedToNative(img);
                putSeq(idx, n);
                if (idx % keyframeEvery == 0) {
                    putKey(idx, copyImage(n));
                }
                nextIndex = idx + 1;
            }
        } catch (Exception ignored) {
        }
    }

    private int nearestKeyframeAtOrBefore(int index) {
        int best = 0;
        for (int k : keyframes) {
            if (k <= index) {
                best = k;
            } else {
                break;
            }
        }
        // 无索引时按固定间隔
        if (keyframes.isEmpty()) {
            best = (index / keyframeEvery) * keyframeEvery;
        }
        return Math.max(0, best);
    }

    private void putSeq(int index, NativeImage img) {
        NativeImage old = seqCache.put(index, img);
        if (old != null && old != img && !keyCache.containsValue(old)) {
            old.close();
        }
    }

    private void putKey(int index, NativeImage img) {
        NativeImage old = keyCache.put(index, img);
        if (old != null && old != img) {
            old.close();
        }
    }

    private void reopen() throws Exception {
        closeQuietly();
        seqCache.values().forEach(NativeImage::close);
        seqCache.clear();
        // keyCache 保留
        channel = NIOUtils.readableChannel(file);
        grab = AWTFrameGrab.createAWTFrameGrab(channel);
        nextIndex = 0;
    }

    private void closeQuietly() {
        try {
            if (channel != null) {
                channel.close();
            }
        } catch (Exception ignored) {
        }
        channel = null;
        grab = null;
    }

    @Override
    public void close() {
        closeQuietly();
        seqCache.values().forEach(NativeImage::close);
        seqCache.clear();
        keyCache.values().forEach(NativeImage::close);
        keyCache.clear();
    }

    public static NativeImage bufferedToNative(BufferedImage img) {
        int w = img.getWidth();
        int h = img.getHeight();
        NativeImage nativeImage = new NativeImage(w, h, false);
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xFF;
                int g = (rgb >> 8) & 0xFF;
                int b = rgb & 0xFF;
                int a = (rgb >> 24) & 0xFF;
                if (a == 0) {
                    a = 255;
                }
                int abgr = (a << 24) | (b << 16) | (g << 8) | r;
                nativeImage.setPixelRGBA(x, y, abgr);
            }
        }
        return nativeImage;
    }

    private static NativeImage copyImage(NativeImage src) {
        NativeImage dst = new NativeImage(src.getWidth(), src.getHeight(), false);
        dst.copyFrom(src);
        return dst;
    }

    public static File videoFile(File clipDir) {
        File mp4 = new File(clipDir, "video.mp4");
        if (mp4.isFile()) {
            return mp4;
        }
        File[] files = clipDir.listFiles((d, n) -> {
            String lower = n.toLowerCase(Locale.ROOT);
            return lower.endsWith(".mp4") && !lower.startsWith("audio_source")
                    && !lower.contains(".part.") && !lower.endsWith(".tmp");
        });
        if (files != null && files.length > 0) {
            return files[0];
        }
        return null;
    }
}
