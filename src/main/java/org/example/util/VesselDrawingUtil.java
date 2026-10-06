package org.example.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * 容器底图的两版化：把上传的一张图处理成前端需要的**白纸版**与**亮线版**。
 *
 * <p>为什么服务端出两张、而不是上传一张靠前端反色：深浅两个主题各要一版 ——
 * 白纸版（白底深线）给浅色主题，亮线版（透明底 + 亮色线稿）给深色主题。
 * 用 CSS `filter: invert()` 之类就地反色在小程序端的支持面本项目一律先求证
 * （见《UNIAPP迁移说明》5.3 当年为什么放弃），服务端出图则三端行为完全一致。
 *
 * <p><b>本类与 `resources/compress-vessel-images.py` 是同一套数学，改一处要改两处。</b>
 * 两个口径必须一致，否则内置的四张底图与后台上传的会长得不一样：
 * <ul>
 *   <li>白纸版 = 先把原图**合成到白底**（透明底与白底上传统一成同一种结果）</li>
 *   <li>亮线版 = 取白纸版的**灰度**，墨量 `α = 255 − 灰度`（即「比纯白暗了多少」），
 *       颜色统一成 {@link #DARK_INK}、把墨量当 alpha 写出去 —— 与白纸版严格互补：
 *       同一组抗锯齿、同一组线宽，两张图叠在同一位置上连笔画粗细都对得上</li>
 * </ul>
 *
 * <p>灰度用 ITU-R BT.601 的亮度公式（0.299R + 0.587G + 0.114B），与 Python 的
 * `ImageOps.grayscale` 一致。
 */
public final class VesselDrawingUtil {

    /**
     * 亮线版的线色 #cbd5e1。
     *
     * <p>它不是主题令牌，而是**图纸自身的「墨色」**：在深色卡片（#17171c）上约 12:1 对比度。
     * 与 `compress-vessel-images.py` 的 `DARK_INK` 必须一致。
     */
    public static final Color DARK_INK = new Color(0xcb, 0xd5, 0xe1);

    private VesselDrawingUtil() {
    }

    /** 白纸版：把原图合成到白底（丢掉 alpha 通道）。 */
    public static BufferedImage toWhitePaper(BufferedImage src) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage flat = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = flat.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return flat;
    }

    /**
     * 亮线版：由白纸版算墨量当 alpha、线色取 {@link #DARK_INK}，输出透明底。
     *
     * <p>入参必须是**白纸版**（已合成白底）而不是原图 —— 原图带 alpha 时，
     * 透明区域的灰度是未定义的，算出来的墨量会随读图实现而变。
     */
    public static BufferedImage toBrightLines(BufferedImage whitePaper) {
        int w = whitePaper.getWidth();
        int h = whitePaper.getHeight();
        BufferedImage dark = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        int inkRgb = (DARK_INK.getRed() << 16) | (DARK_INK.getGreen() << 8) | DARK_INK.getBlue();
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = whitePaper.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int g = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                // BT.601 亮度，与 Python 的 ImageOps.grayscale 一致
                int gray = (int) Math.round(0.299 * r + 0.587 * g + 0.114 * b);
                // 墨量 = 255 − 灰度：纯白 → 0（全透明），纯黑 → 255（不透明）
                int ink = 255 - Math.max(0, Math.min(255, gray));
                dark.setRGB(x, y, (ink << 24) | inkRgb);
            }
        }
        return dark;
    }
}
