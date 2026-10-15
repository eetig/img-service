package org.example.util;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

/**
 * 解码前的「进门检查」：**先读图片头量尺寸、验格式，再决定要不要解码**。
 *
 * <p>为什么必须有这一层（2026-10-15 code-review 的 Critical）：{@code ImageIO.read()}
 * 会按 width×height×4 字节一次性分配 {@code TYPE_INT_RGB} 缓冲。PNG 对平坦区域压缩率极高，
 * 一张 20000×20000 的纯色图压缩后可能只有几百 KB —— **能轻松通过任何"文件大小"校验**，
 * 却要求 4×4×10⁸ ≈ 1.6GB 堆，在 -Xmx512m 下直接 OutOfMemoryError。而 OOM 是 {@link Error}：
 * 本服务里的 {@code catch (IOException)} 与 {@code @ExceptionHandler(Exception.class)}
 * 都抓不到，请求 500、线程栈冲掉，`-XX:+HeapDumpOnOutOfMemoryError` 还会往挂载卷写一份堆转储。
 *
 * <p>结论：**文件大小上限挡不住像素炸弹，两者必须分别设限**。本类只调
 * {@code reader.getWidth(0)/getHeight(0)}（读头，不解像素），代价是几十字节的读取。
 *
 * <p>顺带把「只看扩展名不看内容」这条也收掉：{@link #probe} 返回**实际**格式，
 * 调用方据此决定落库的扩展名与 Content-Type，改名的假图片（JPEG 叫 .png）不会再骗过去。
 */
public final class ImageGuard {

    private ImageGuard() {
    }

    /** 单边上限（px）：A3@600dpi 约 14000px 已是极端扫描件，超过它更可能是攻击载荷而非图纸 */
    public static final int MAX_SIDE = 12000;

    /** 总像素上限：4000 万 × 4 字节/像素 ≈ 单张解码最多 160MB（-Xmx512m 下仍有余量） */
    public static final long MAX_PIXELS = 40_000_000L;

    /** 探测结果：实际格式 + 像素尺寸。 */
    public static final class Probe {
        private final String format;
        private final int width;
        private final int height;

        Probe(String format, int width, int height) {
            this.format = format;
            this.width = width;
            this.height = height;
        }

        /** 小写格式名：{@code jpg} 或 {@code png} */
        public String format() {
            return format;
        }

        public int width() {
            return width;
        }

        public int height() {
            return height;
        }

        /** 落库用的 Content-Type */
        public String contentType() {
            return "jpg".equals(format) ? "image/jpeg" : "image/png";
        }
    }

    /**
     * 读取图片头，校验格式与像素规模。
     *
     * @throws IOException 内容为空、非图片、格式不受支持、或尺寸/像素超过上限
     */
    public static Probe probe(byte[] bytes) throws IOException {
        if (bytes == null || bytes.length == 0) {
            throw new IOException("图片内容为空");
        }
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            if (in == null) {
                throw new IOException("无法读取图片内容");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) {
                throw new IOException("无法识别的图片内容，仅支持 jpg/png");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                checkDimensions(width, height);
                String format = normalizeFormat(reader.getFormatName());
                if (format == null) {
                    throw new IOException("无法识别的图片内容，仅支持 jpg/png");
                }
                return new Probe(format, width, height);
            } finally {
                reader.dispose();
            }
        }
    }

    /** 只要"能解且不炸"时的轻量入口（缩略图那条路径用它） */
    public static void assertDecodable(byte[] bytes) throws IOException {
        probe(bytes);
    }

    /** 尺寸/像素上限校验单独暴露：便于测试直接构造超限值，不必真造一张 1.6GB 的图 */
    public static void checkDimensions(int width, int height) throws IOException {
        if (width <= 0 || height <= 0) {
            throw new IOException("图片尺寸非法：" + width + "x" + height);
        }
        if (width > MAX_SIDE || height > MAX_SIDE) {
            throw new IOException("图片边长超过 " + MAX_SIDE + "px（当前 " + width + "x" + height + "），请先压缩");
        }
        if ((long) width * height > MAX_PIXELS) {
            throw new IOException("图片像素数超过 " + (MAX_PIXELS / 1_000_000) + " 百万（当前 "
                    + width + "x" + height + "），请先压缩");
        }
    }

    /** 把 ImageIO 的格式名收敛成 jpg/png；其它格式返回 null（调用方拒绝） */
    private static String normalizeFormat(String formatName) {
        if (formatName == null) {
            return null;
        }
        String name = formatName.toLowerCase(Locale.ROOT);
        if (name.contains("jpeg") || name.contains("jpg")) {
            return "jpg";
        }
        if (name.contains("png")) {
            return "png";
        }
        return null;
    }
}
