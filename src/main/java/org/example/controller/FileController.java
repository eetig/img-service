package org.example.controller;

import org.example.dto.FileUploadResult;
import org.example.dto.Result;
import org.example.service.FileService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

/**
 * 资料归档的**原样存取**通路（变更-126，2026-10-20）。
 *
 * <p>与 {@code /api/img/**} 并列、但语义完全不同：
 * <ul>
 *   <li>{@code /api/img/upload}：图片专用，会嗅探内容并**重编码成 PNG**，回可直接显示的 URL；</li>
 *   <li>{@code /api/file/upload}：资料专用（PDF/Word/Excel…），<b>字节原样</b>存，回元数据。</li>
 * </ul>
 *
 * <p>与 {@code /api/img/**} 一样不挂鉴权注解：本服务在网关/域名侧不对外暴露，
 * 写入口由业务侧（{@code hnd_factory} 的 {@code /api/archive/**}）统一把关。
 * 但读/删的安全边界收在 {@link FileService} 里：只认 {@code archive/<32hex>.<ext>}，
 * 碰不到共享桶里别人的对象。
 *
 * <p>下载一律 {@code Content-Disposition: attachment} + {@code nosniff}：
 * 资料里可能混进 HTML/SVG 之类能执行的东西，绝不能让浏览器按页面打开。
 */
@RestController
@RequestMapping("/api/file")
public class FileController {

    private static final Logger log = LoggerFactory.getLogger(FileController.class);

    /**
     * 资料不做公共缓存。
     *
     * <p>图片是 UUID 命名、内容永不复用，所以那边 7 天 immutable 是对的；
     * 但资料是**内部文件**，不必要地落在代理/浏览器缓存里只会增加失控面。
     */
    private static final String CACHE_CONTROL = "private, max-age=0, must-revalidate";

    private final FileService fileService;

    public FileController(FileService fileService) {
        this.fileService = fileService;
    }

    @PostMapping("/upload")
    public Result<FileUploadResult> upload(@RequestParam("file") MultipartFile file) {
        return Result.success(fileService.upload(file));
    }

    /** 下载：原样字节 + 附件语义 */
    @GetMapping("/{fileName}")
    public ResponseEntity<byte[]> download(@PathVariable("fileName") String fileName) {
        byte[] data;
        String contentType;
        try {
            data = fileService.read(fileName);
            contentType = fileService.contentTypeOfStored(fileName);
        } catch (Exception e) {
            log.error("读取资料失败, fileName={}", fileName, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
        if (data == null) {
            return ResponseEntity.notFound().build();
        }
        // 文件名只做展示用；真正的下载名（原文件名）由 hnd_factory 那层按 DB 里的 orig_name 覆盖。
        // 这里用对象名是安全的：它由本服务生成，形如 <32hex>.pdf，不含引号与换行。
        String safeName = fileName.replace("\"", "").replace("\r", "").replace("\n", "");
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(contentType))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + safeName + "\"")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CACHE_CONTROL, CACHE_CONTROL)
                .body(data);
    }

    @DeleteMapping("/{fileName}")
    public Result<Void> delete(@PathVariable("fileName") String fileName) {
        fileService.delete(fileName);
        return Result.success(null);
    }
}
