package org.example.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.nio.charset.StandardCharsets;
import org.example.config.MinioProperties;
import org.example.dto.FileUploadResult;
import org.example.exception.BusinessException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

/**
 * {@link FileService} 的行为测试（变更-126）。
 *
 * <p>断言方式跟 {@code ImgServiceTest} 保持一致：**"抛异常 + 没有和 MinioClient 发生任何交互"**。
 * 理由也一样 —— 桶是业务共享桶、`/api/file/**` 免鉴权，校验若发生在写/删之后就已经晚了，
 * 所以钉的是"进 MinIO 之前就该拒绝"这个位置，而不是某句文案。
 *
 * <p>正向用例只钉**对象名的形状**与 Content-Type：真正的"下载回来字节一致"
 * 需要真的 MinIO，属于部署后验证（见开工方案第 5 步），单测里不假装能验。
 */
class FileServiceTest {

    private MinioClient minioClient;
    private FileService service;

    @BeforeEach
    void setUp() {
        minioClient = mock(MinioClient.class);
        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        service = new FileService(minioClient, properties);
    }

    @Test
    @DisplayName("空文件：拒绝，且不碰 MinIO")
    void rejectsEmpty() {
        MultipartFile file = new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0]);
        assertThrows(BusinessException.class, () -> service.upload(file));
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("超过 30MB：按 getSize 就拒绝（不读内容、不碰 MinIO）")
    void rejectsTooBig() throws Exception {
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getSize()).thenReturn(31L * 1024 * 1024);
        when(file.getOriginalFilename()).thenReturn("big.pdf");

        assertThrows(BusinessException.class, () -> service.upload(file));
        verify(file, never()).getBytes();
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("扩展名不在白名单（exe/js/html）：拒绝")
    void rejectsBadExtension() {
        for (String name : new String[] {"a.exe", "b.js", "c.html", "d.svg", "e"}) {
            MultipartFile file = new MockMultipartFile("file", name, "application/octet-stream",
                    "x".getBytes(StandardCharsets.UTF_8));
            assertThrows(BusinessException.class, () -> service.upload(file), name);
        }
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("正向：对象名是 archive/<32hex>.<ext>，Content-Type 按扩展名给（不是 octet-stream）")
    void storesGeneratedArchiveName() throws Exception {
        minioClient = mock(MinioClient.class);
        when(minioClient.bucketExists(any())).thenReturn(true);
        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        service = new FileService(minioClient, properties);

        MultipartFile file = new MockMultipartFile("file", "2026年 安全许可证.pdf",
                "application/pdf", "%PDF-1.4 test".getBytes(StandardCharsets.UTF_8));
        FileUploadResult result = service.upload(file);

        ArgumentCaptor<PutObjectArgs> captor = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(minioClient).putObject(captor.capture());
        String objectName = captor.getValue().object();

        assertTrue(objectName.matches("^archive/[0-9a-f]{32}\\.pdf$"),
                "对象名必须是自己生成的 archive/<32hex>.<ext>，实际=" + objectName);
        assertEquals("application/pdf", captor.getValue().contentType());
        assertEquals(objectName.substring("archive/".length()), result.getFileName(),
                "回给业务的是不带前缀的裸名字（前缀是实现细节）");
        assertEquals("2026年 安全许可证.pdf", result.getOrigName(), "原文件名只作元数据");
        assertEquals(13, result.getSize());
    }

    @Test
    @DisplayName("读/删：只认 archive/<32hex>.<白名单扩展名> —— 图片名、路径穿越、非白名单扩展名一律拒绝且不碰 MinIO")
    void rejectsForeignNames() {
        for (String bad : new String[] {
                "thumb_0123456789abcdef0123456789abcdef.png", // 图片缩略图
                "../../etc/passwd",
                "archive/../secret.pdf",
                "archive/nothex.pdf",
                "archive/0123456789abcdef0123456789abcdef.exe", // 扩展名不在白名单
                "archive/0123456789abcdef0123456789abcdef",     // 没有扩展名
                ""}) {
            assertThrows(BusinessException.class, () -> service.read(bad), bad);
            assertThrows(BusinessException.class, () -> service.delete(bad), bad);
        }
        verifyNoInteractions(minioClient);
    }

    @Test
    @DisplayName("裸图片名不会碰到图片对象：它被补成 archive/<hex>.png（两个不同的 key）")
    void bareImageNameMapsUnderArchivePrefix() throws Exception {
        MinioClient client = mock(MinioClient.class);
        io.minio.messages.ErrorResponse er = mock(io.minio.messages.ErrorResponse.class);
        when(er.code()).thenReturn("NoSuchKey");
        io.minio.errors.ErrorResponseException ex = mock(io.minio.errors.ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(er);
        ArgumentCaptor<io.minio.GetObjectArgs> captor = ArgumentCaptor.forClass(io.minio.GetObjectArgs.class);
        when(client.getObject(captor.capture())).thenThrow(ex);

        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        FileService svc = new FileService(client, properties);

        // 传裸图片名，读到的是 archive/ 下同名对象 —— 绝不可能是桶里那张图片本身
        assertEquals(null, svc.read("0123456789abcdef0123456789abcdef.png"));
        assertEquals("archive/0123456789abcdef0123456789abcdef.png", captor.getValue().object());
    }

    @Test
    @DisplayName("读：对象不存在 → 返回 null（由 controller 翻 404），不抛业务异常")
    void readMissingReturnsNull() throws Exception {
        MinioClient client = mock(MinioClient.class);
        io.minio.messages.ErrorResponse er = mock(io.minio.messages.ErrorResponse.class);
        when(er.code()).thenReturn("NoSuchKey");
        io.minio.errors.ErrorResponseException ex = mock(io.minio.errors.ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(er);
        when(client.getObject(any())).thenThrow(ex);

        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        FileService svc = new FileService(client, properties);

        // 「不存在」不是一个错误：调用方据此回 404，不该被包成 500
        assertEquals(null, svc.read("0123456789abcdef0123456789abcdef.pdf"));
    }

    @Test
    @DisplayName("读：MinIO 真故障（非 NoSuchKey）→ 抛业务异常，不伪装成 404")
    void readFailureThrows() throws Exception {
        MinioClient client = mock(MinioClient.class);
        io.minio.messages.ErrorResponse er = mock(io.minio.messages.ErrorResponse.class);
        when(er.code()).thenReturn("AccessDenied");
        io.minio.errors.ErrorResponseException ex = mock(io.minio.errors.ErrorResponseException.class);
        when(ex.errorResponse()).thenReturn(er);
        when(client.getObject(any())).thenThrow(ex);

        MinioProperties properties = new MinioProperties();
        properties.setBucket("hndfactory");
        FileService svc = new FileService(client, properties);

        assertThrows(BusinessException.class, () -> svc.read("0123456789abcdef0123456789abcdef.pdf"));
    }
}
