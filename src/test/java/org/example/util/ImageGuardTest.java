package org.example.util;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ImageGuard} 的行为测试（2026-10-15 code-review 的 Critical 2 回归防线）。
 *
 * <p>为什么值得单独测：这个类存在的唯一理由是"**文件大小上限挡不住像素炸弹**"。
 * 真造一张 20000×20000 的图来验它会先把测试进程自己打爆（4×4×10⁸ = 1.6GB），
 * 所以超限那一侧直接测独立的 {@link ImageGuard#checkDimensions}，正常那一侧
 * 用真实的小图走完整 {@link ImageGuard#probe}。两半合起来覆盖了决策点。
 */
class ImageGuardTest {

    /** 造一张真实的小图（PNG 或 JPEG），用来走完整 probe 路径 */
    private static byte[] realImage(String format, int width, int height) throws IOException {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    @Test
    @DisplayName("像素炸弹形状被拦下：8000×8000 单边合法但 6400 万像素 → 解码要 256MB")
    void rejectsPixelBomb() {
        // 单边合法（<12000）但总量超限，所以命中的是"像素数"那道 —— 这才是真正的炸弹形状：
        // PNG 对平坦区域压缩率极高，8000×8000 的纯色图压缩后可能只有几百 KB
        IOException e = assertThrows(IOException.class,
                () -> ImageGuard.checkDimensions(8000, 8000));
        assertTrue(e.getMessage().contains("像素数"), "文案要指出是像素数超限：" + e.getMessage());

        // 两边都超的载荷由"边长"那道先拦下，同样到不了解码
        IOException side = assertThrows(IOException.class,
                () -> ImageGuard.checkDimensions(20000, 20000));
        assertTrue(side.getMessage().contains("边长"), "文案要指出是边长超限：" + side.getMessage());
    }

    @Test
    @DisplayName("单边超限也被拦下（12000px 是上限，A3@600dpi 约 14000px 属越界）")
    void rejectsOversizedSide() {
        IOException e = assertThrows(IOException.class,
                () -> ImageGuard.checkDimensions(13000, 100));
        assertTrue(e.getMessage().contains("边长"), "文案要指出是边长超限：" + e.getMessage());
    }

    @Test
    @DisplayName("合法尺寸通过：A3@300dpi（4960×3508）与 0 像素的退化尺寸分别通过/拒绝")
    void acceptsRealScanAndRejectsDegenerate() {
        assertDoesNotThrow(() -> ImageGuard.checkDimensions(4960, 3508));
        assertDoesNotThrow(() -> ImageGuard.checkDimensions(1, 1));
        assertThrows(IOException.class, () -> ImageGuard.checkDimensions(0, 100));
        assertThrows(IOException.class, () -> ImageGuard.checkDimensions(100, -1));
    }

    @Test
    @DisplayName("probe 报出真实格式与尺寸（jpg/png 各自认得出来）")
    void probesRealFormats() throws IOException {
        ImageGuard.Probe png = ImageGuard.probe(realImage("png", 12, 34));
        assertEquals("png", png.format());
        assertEquals(12, png.width());
        assertEquals(34, png.height());
        assertEquals("image/png", png.contentType());

        ImageGuard.Probe jpg = ImageGuard.probe(realImage("jpg", 56, 78));
        assertEquals("jpg", jpg.format());
        assertEquals("image/jpeg", jpg.contentType());
    }

    @Test
    @DisplayName("非图片内容被拒绝：把文本改名成 .png 也骗不过（原来只看扩展名）")
    void rejectsNonImageContent() {
        byte[] text = "这不是图片，只是改了名的文本".getBytes(StandardCharsets.UTF_8);
        IOException e = assertThrows(IOException.class, () -> ImageGuard.assertDecodable(text));
        assertTrue(e.getMessage().contains("无法识别"), "文案要说明无法识别：" + e.getMessage());
        assertThrows(IOException.class, () -> ImageGuard.assertDecodable(new byte[0]));
        assertThrows(IOException.class, () -> ImageGuard.assertDecodable(null));
    }
}
