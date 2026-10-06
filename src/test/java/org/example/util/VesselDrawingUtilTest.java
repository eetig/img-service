package org.example.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 容器底图两版化的单测（2026-10-06）。
 *
 * <p>这段逻辑与 `resources/compress-vessel-images.py` 是同一套数学，但**跑在两个语言里** ——
 * 一旦算错，表现是「后台上传的底图在深色主题下糊成一片」或「液位线附近有灰底」，
 * 而且只在上传的那张图上看得出来（内置四张走的是脚本）。所以把口径钉死在这里：
 *
 * <ol>
 *   <li>白纸版：透明区域必须变成**白**（不是黑、不是保留透明）</li>
 *   <li>亮线版：纯黑像素 → 墨量满（alpha 255）；纯白像素 → 全透明（alpha 0）；
 *       线色恒为 {@link VesselDrawingUtil#DARK_INK}</li>
 *   <li>两版尺寸一致（前端按同一组坐标叠液面，尺寸不一致会让液位线跳）</li>
 * </ol>
 */
class VesselDrawingUtilTest {

    private static BufferedImage canvas(int w, int h, int argbType) {
        return new BufferedImage(w, h, argbType);
    }

    @Test
    @DisplayName("白纸版：透明区域合成为白，深色线保持深色")
    void whitePaperFlattensTransparencyToWhite() {
        // 全透明底 + 一条黑线
        BufferedImage src = canvas(4, 2, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = src.createGraphics();
        g.setColor(Color.BLACK);
        g.fillRect(0, 0, 2, 2); // 左半是黑线，右半留透明
        g.dispose();

        BufferedImage flat = VesselDrawingUtil.toWhitePaper(src);

        assertEquals(0x000000, flat.getRGB(0, 0) & 0xFFFFFF, "黑线应当保留为黑");
        assertEquals(0xFFFFFF, flat.getRGB(3, 0) & 0xFFFFFF, "透明区域必须合成为白，不能是别的颜色");
        assertEquals(4, flat.getWidth());
        assertEquals(2, flat.getHeight());
    }

    @Test
    @DisplayName("亮线版：纯黑 → 满墨量不透明，纯白 → 全透明，线色恒为 DARK_INK")
    void brightLinesUsesInkAsAlpha() {
        BufferedImage white = canvas(2, 1, BufferedImage.TYPE_INT_RGB);
        white.setRGB(0, 0, 0x000000); // 黑
        white.setRGB(1, 0, 0xFFFFFF); // 白

        BufferedImage dark = VesselDrawingUtil.toBrightLines(white);

        int black = dark.getRGB(0, 0);
        assertEquals(255, (black >>> 24) & 0xff, "纯黑应当是满墨量（alpha 255）");
        assertEquals(VesselDrawingUtil.DARK_INK.getRGB() & 0xFFFFFF, black & 0xFFFFFF,
                "线色必须是 DARK_INK，否则深色主题下与其它底图不一致");

        int whitePx = dark.getRGB(1, 0);
        assertEquals(0, (whitePx >>> 24) & 0xff, "纯白应当全透明（alpha 0），否则深色卡片上会出现灰底");
    }

    @Test
    @DisplayName("墨量随灰度线性：中灰 50% → alpha 约 127")
    void inkIsComplementOfGray() {
        BufferedImage white = canvas(1, 1, BufferedImage.TYPE_INT_RGB);
        white.setRGB(0, 0, 0x808080); // 128 灰

        int alpha = (VesselDrawingUtil.toBrightLines(white).getRGB(0, 0) >>> 24) & 0xff;

        assertEquals(127, alpha, "墨量 = 255 − 灰度，与脚本口径一致");
    }

    @Test
    @DisplayName("两版尺寸一致（不一致会让液位线在切主题时跳）")
    void bothVersionsShareSize() {
        BufferedImage src = canvas(7, 3, BufferedImage.TYPE_INT_ARGB);
        BufferedImage white = VesselDrawingUtil.toWhitePaper(src);
        BufferedImage dark = VesselDrawingUtil.toBrightLines(white);

        assertEquals(white.getWidth(), dark.getWidth());
        assertEquals(white.getHeight(), dark.getHeight());
        assertEquals(7, dark.getWidth());
        assertEquals(3, dark.getHeight());
    }

    @Test
    @DisplayName("亮线版是透明底：任意像素都不该是不透明的白")
    void brightLinesHasNoOpaqueWhite() {
        // 典型线稿：白底 + 少量黑线。若墨量算反了（把白当墨），整张图会变成一大块浅蓝
        BufferedImage white = canvas(10, 10, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = white.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 10, 10);
        g.setColor(Color.BLACK);
        g.fillRect(4, 0, 2, 10);
        g.dispose();

        BufferedImage dark = VesselDrawingUtil.toBrightLines(white);

        assertEquals(0, (dark.getRGB(0, 0) >>> 24) & 0xff, "白底区域必须透明");
        assertEquals(255, (dark.getRGB(4, 5) >>> 24) & 0xff, "黑线必须不透明");
    }
}
