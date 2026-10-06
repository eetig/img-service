package org.example.controller;

import java.time.Duration;
import org.example.dto.Result;
import org.example.dto.UploadResult;
import org.example.dto.VesselDrawingResult;
import org.example.service.ImgService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/img")
public class ImgController {

    private static final Logger log = LoggerFactory.getLogger(ImgController.class);

    /**
     * 缓存 7 天 + immutable。
     *
     * 文件名是 UUID，内容与文件名一一对应、永不复用，因此可以放心长期缓存；
     * 这是「刷新页面图片请求数为 0」的关键，缺失该头整改即失效（契约 2.1）。
     */
    private static final CacheControl CACHE_CONTROL =
            CacheControl.maxAge(Duration.ofDays(7)).cachePublic().immutable();

    private static final MediaType IMAGE_WEBP = MediaType.parseMediaType("image/webp");
    private static final MediaType IMAGE_BMP = MediaType.parseMediaType("image/bmp");

    private final ImgService imgService;

    public ImgController(ImgService imgService) {
        this.imgService = imgService;
    }

    @PostMapping("/upload")
    public Result<UploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(imgService.upload(file));
    }

    /**
     * 容器底图上传（「设备数据维护」页用，2026-10-06）。
     *
     * <p>与上面的 {@code /upload} 分开：那个是单据图片（回一个 URL 直接显示），
     * 这个是容器底图 —— **一张进、两张出**（白纸版 + 亮线版，深浅两个主题各要一版），
     * 回的是两个文件名，由调用方存进 {@code equipment_ledger.image_file}。
     *
     * <p>与 {@code /upload} 一样不带鉴权注解：本服务在网关/域名侧不对外暴露
     * （Nginx 只放开 /files、/thumbs、/api/ocr 几条），写入口由业务侧
     * {@code /api/equipment/ledger/**} 统一把关。
     */
    @PostMapping("/vessel-upload")
    public Result<VesselDrawingResult> vesselUpload(@RequestParam("file") MultipartFile file) {
        return Result.success(imgService.uploadVesselDrawing(file));
    }

    @DeleteMapping("/delete")
    public Result<Void> delete(@RequestParam("fileName") String fileName) {
        imgService.delete(fileName);
        return Result.success(null);
    }

    /** 保留旧接口，供 hnd_factory 回退使用（契约 2.3 第 5 条） */
    @GetMapping("/getPreviewUrl")
    public Result<String> getPreviewUrl(@RequestParam("fileName") String fileName) {
        return Result.success(imgService.getPreviewUrl(fileName));
    }

    // ================= 新增：同源图片访问（契约 2.1 / 2.2） =================

    /** 原图：返回图片二进制流，由 Nginx 以 /files/{fileName} 暴露 */
    @GetMapping("/file/{fileName}")
    public ResponseEntity<byte[]> file(@PathVariable("fileName") String fileName) {
        byte[] data;
        try {
            data = imgService.readObject(fileName);
        } catch (Exception e) {
            log.error("读取原图失败, fileName={}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return imageResponse(data, mediaTypeOf(fileName));
    }

    /** 缩略图：返回 JPEG 流，由 Nginx 以 /thumbs/{fileName} 暴露 */
    @GetMapping("/thumb/{fileName}")
    public ResponseEntity<byte[]> thumb(@PathVariable("fileName") String fileName) {
        byte[] data;
        try {
            data = imgService.readThumbnail(fileName);
        } catch (Exception e) {
            log.error("读取缩略图失败, fileName={}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        // 原图不存在或生成失败均为 404，不做原图兜底（契约 2.2）
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        return imageResponse(data, MediaType.IMAGE_JPEG);
    }

    private ResponseEntity<byte[]> imageResponse(byte[] data, MediaType contentType) {
        return ResponseEntity.ok()
                .contentType(contentType)
                .cacheControl(CACHE_CONTROL)
                .body(data);
    }

    /** 按扩展名推断 Content-Type（契约 2.1） */
    private MediaType mediaTypeOf(String fileName) {
        String name = fileName == null ? "" : fileName.toLowerCase();
        int dot = name.lastIndexOf('.');
        String ext = dot < 0 ? "" : name.substring(dot + 1);
        return switch (ext) {
            case "png" -> MediaType.IMAGE_PNG;
            case "gif" -> MediaType.IMAGE_GIF;
            case "webp" -> IMAGE_WEBP;
            case "bmp" -> IMAGE_BMP;
            case "jpg", "jpeg" -> MediaType.IMAGE_JPEG;
            default -> MediaType.APPLICATION_OCTET_STREAM;
        };
    }
}
