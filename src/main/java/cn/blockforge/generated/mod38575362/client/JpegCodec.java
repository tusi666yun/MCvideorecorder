package cn.blockforge.generated.mod38575362.client;

import com.mojang.blaze3d.platform.NativeImage;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;

/**
 * 录像帧的 JPG 编解码。
 *
 * <p>游戏自带的 {@link NativeImage#writeToFile(File)} 只会写 PNG，一帧 640×360 要 200 KB 以上，
 * 高清胶卷录久了能上几百兆。这里改走 JDK 的图片编码器，用可指定压缩率的 jpg writer 写盘，
 * 同样一帧只有几十 KB，玩家的游戏目录才受得住。
 */
public final class JpegCodec {
    private JpegCodec() {
    }

    /** 把一帧画面按给定压缩率写成 JPG 文件。压缩率一般取 0.80 ~ 0.95。 */
    public static void writeJpeg(NativeImage image, File target, float quality) throws IOException {
        BufferedImage buffered = toBgr(image);
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
        if (!writers.hasNext()) {
            throw new IOException("当前 JDK 没有可用的 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(target)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.min(1.0F, Math.max(0.30F, quality)));
            }
            writer.setOutput(out);
            writer.write(null, new IIOImage(buffered, null, null), param);
            out.flush();
        } finally {
            writer.dispose();
        }
    }

    /**
     * 用游戏内置的 stb 图片库读一帧（PNG、JPG 都认）。
     * 万一这条原生路径失败，再退回 JDK 的解码器。
     */
    public static NativeImage readFrame(File file, byte[] bytes) throws IOException {
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            return NativeImage.read(NativeImage.Format.RGBA, in);
        } catch (IOException | RuntimeException | UnsatisfiedLinkError e) {
            return readWithImageIo(file, bytes);
        }
    }

    private static NativeImage readWithImageIo(File file, byte[] bytes) throws IOException {
        BufferedImage buffered;
        try (ByteArrayInputStream in = new ByteArrayInputStream(bytes)) {
            buffered = ImageIO.read(in);
        }
        if (buffered == null) {
            throw new IOException("无法解码影像文件：" + file.getName());
        }
        int width = buffered.getWidth();
        int height = buffered.getHeight();
        int[] argb = buffered.getRGB(0, 0, width, height, null, 0, width);
        NativeImage image = new NativeImage(width, height, false);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = argb[y * width + x];
                // JPEG 没有透明通道，读回来一律补成不透明，否则播放时会被当成透明像素丢掉。
                // NativeImage 的 int 像素按小端打包（低字节是 R），要把 ARGB 的 R、B 换个位。
                image.setPixelRGBA(x, y, 0xFF000000 | (pixel & 0xFF00FF00)
                        | ((pixel & 0xFF) << 16) | ((pixel >> 16) & 0xFF));
            }
        }
        return image;
    }

    /** NativeImage 的像素是小端 packed（低字节 R），JPG 编码器要 BGR 三字节一组。 */
    private static BufferedImage toBgr(NativeImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR);
        byte[] data = ((DataBufferByte) buffered.getRaster().getDataBuffer()).getData();
        int[] pixels;
        try {
            pixels = image.getPixelsRGBA();
        } catch (IllegalStateException e) {
            pixels = null;
        }
        if (pixels != null && pixels.length == width * height) {
            int offset = 0;
            for (int packed : pixels) {
                data[offset++] = (byte) (packed >> 16);
                data[offset++] = (byte) (packed >> 8);
                data[offset++] = (byte) packed;
            }
        } else {
            int offset = 0;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int packed = image.getPixelRGBA(x, y);
                    data[offset++] = (byte) (packed >> 16);
                    data[offset++] = (byte) (packed >> 8);
                    data[offset++] = (byte) packed;
                }
            }
        }
        return buffered;
    }

    /** 判断一个文件名是不是我们认识的图片后缀。 */
    public static boolean looksLikeImage(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png");
    }
}
