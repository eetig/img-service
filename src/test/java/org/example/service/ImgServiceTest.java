package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import java.nio.charset.StandardCharsets;
import org.example.config.MinioProperties;
import org.example.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/**
 * {@link ImgService} 入口校验的行为测试（2026-10-15 code-review 的 Critical 1/2/3 回归防线）。
 *
 * <p>三条都钉在"**进 MinIO 之前**就该拒绝"这个位置上：桶是业务共享桶，而
 * `/api/img/**` 免鉴权 —— 校验若发生在写/删之后就已经晚了。所以断言方式是
 * "抛异常 + **没有和 MinioClient 发生任何交互**"，而不是断言某句文案。
 */
class ImgServiceTest {

    private MinioClient minioClient;
    private ImgService service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        service = new ImgService(minioClient, properties);
    }

    @Test
    @DisplayName("删除：路径穿越、嵌套 key、别家对象名一律在进 MinIO 前被拒（Critical 3）")
    void deleteRejectsUnsafeObjectNames() {
        for (String bad : new String[] {
                "../../etc/passwd",
                "a/b.png",
                "..",
                "other-service/data.json",
                "not-a-uuid.png",
                "0000000000000000000000000000000.png",
                ""}) {
            BusinessException e = assertThrows(BusinessException.class, () -> service.delete(bad),
                    "应拒绝：" + bad);
            assertTrue(e.getMessage().contains("文件名非法") || e.getMessage().contains("不能为空"),
                    "文案应说明文件名问题：" + e.getMessage());
        }
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("删除：本服务自己生成的形状（含 thumb_ 与 -dark）才放行到 MinIO")
    void deleteAcceptsOwnGeneratedNames() throws Exception {
        String base = "0123456789abcdef0123456789abcdef";
        service.delete(base + ".png");
        service.delete(base + "-dark.png");
        service.delete("thumb_" + base + ".jpg");
        // 放行的判据是"真的去调了 MinIO"，而不是"没抛异常"——
        // 未打桩的 mock 上 removeObject 是 void 空操作，任何实现都能"不抛"
        verify(minioClient, times(3)).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    @DisplayName("读取：非法对象名在进 MinIO 前被拒，不会读共享桶里的任意对象")
    void readRejectsUnsafeObjectNames() {
        assertThrows(BusinessException.class, () -> service.readObject("../../secret"));
        assertThrows(BusinessException.class, () -> service.readObject("other/data.json"));
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("上传：改名成 .png 的非图片内容被拒（原来只看扩展名）")
    void uploadRejectsNonImageContent() {
        MockMultipartFile fake = new MockMultipartFile("file", "photo.png", "image/png",
                "其实是一段文本".getBytes(StandardCharsets.UTF_8));
        BusinessException e = assertThrows(BusinessException.class, () -> service.upload(fake));
        assertTrue(e.getMessage().contains("无法识别"), "文案应说明无法识别：" + e.getMessage());
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("上传：超过 5MB 的\"看起来像图片\"的载荷在解码前就被大小校验拦下")
    void uploadRejectsOversizedPayload() {
        byte[] big = new byte[6 * 1024 * 1024];
        big[0] = (byte) 0x89;
        big[1] = 'P';
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", big);
        BusinessException e = assertThrows(BusinessException.class, () -> service.upload(file));
        assertEquals("文件大小不能超过 5MB", e.getMessage());
        verifyNoInteractions(minioClient);
    }
}
