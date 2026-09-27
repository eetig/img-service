package org.example.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageOutputStream;

/**
 * 缩略图生成：等比缩放到指定宽度并输出 JPEG。
 *
 * 只依赖 JDK 自带的 ImageIO / AWT，不引入第三方图形库。
 * Spring Boot 默认设置 java.awt.headless=true，容器内可正常工作。
 */
public final class ImageThumbnailUtil {

    private ImageThumbnailUtil() {
    }

    /** JPEG 输出质量：0.8 时 200px 宽约 8~20KB，与原图（数百 KB）相比足以达成带宽目标 */
    private static final float JPEG_QUALITY = 0.8f;

    /**
     * 等比缩放到目标宽度，输出 JPEG 字节。
     *
     * @param source      原图字节
     * @param targetWidth 目标宽度（px）
     * @return JPEG 字节
     * @throws IOException 图片无法解码，或 JPEG 编码失败
     */
    public static byte[] scaleToWidth(byte[] source, int targetWidth) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(source));
        if (src == null) {
            throw new IOException("无法解码图片（格式不支持或文件已损坏）");
        }

        int srcWidth = src.getWidth();
        int srcHeight = src.getHeight();
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IOException("图片尺寸非法：" + srcWidth + "x" + srcHeight);
        }

        // 等比缩放，且不放大：原图比目标窄时保持原尺寸
        int dstWidth = Math.min(srcWidth, targetWidth);
        int dstHeight = Math.max(1, (int) Math.round(srcHeight * (dstWidth / (double) srcWidth)));

        // JPEG 无 alpha 通道，PNG 透明区域直接转 JPEG 会变黑底，先铺白底
        BufferedImage rgb = new BufferedImage(dstWidth, dstHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, dstWidth, dstHeight);
            g.drawImage(src, 0, 0, dstWidth, dstHeight, null);
        } finally {
            g.dispose();
        }

        return writeJpeg(rgb);
    }

    private static byte[] writeJpeg(BufferedImage image) throws IOException {
        Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpeg");
        if (!writers.hasNext()) {
            throw new IOException("运行环境缺少 JPEG 编码器");
        }
        ImageWriter writer = writers.next();
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
             ImageOutputStream ios = ImageIO.createImageOutputStream(bos)) {
            writer.setOutput(ios);
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(JPEG_QUALITY);
            }
            writer.write(null, new IIOImage(image, null, null), param);
            ios.flush();
            return bos.toByteArray();
        } finally {
            writer.dispose();
        }
    }
}
