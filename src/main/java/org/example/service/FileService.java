package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.StatObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.example.config.MinioProperties;
import org.example.dto.FileUploadResult;
import org.example.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 资料归档的「原样存取」服务（变更-126，2026-10-20）。
 *
 * <p><b>为什么不复用 {@link ImgService}</b>：那条通路是**图片专用** —— 它用 {@code ImageGuard}
 * 嗅探内容、并把每张图**重编码成 PNG**（为了统一主题、压体积）。资料归档要存的是
 * <b>PDF / Word / Excel</b>，重编码会直接毁掉文件；也不该为了存档去嗅探图片格式。
 * 所以另起一个服务，只做「字节进、字节出」，并且**只碰 {@code archive/} 前缀**。
 *
 * <p><b>三条安全约束</b>（都刻意收得比 ImgService 更严）：
 * <ol>
 *   <li>对象名<b>由本服务生成</b>：{@code archive/<32位hex>.<白名单扩展名>}。调用方给的原始文件名
 *       只当元数据保存，<b>永不参与对象名拼接</b>（原文件名里可能带中文、空格、{@code ../}）。</li>
 *   <li>读/删都只认 {@link #ARCHIVE_NAME} 这个形状 —— 桶是**业务共享桶**，这条保证资料归档的
 *       接口<b>碰不到</b>别的业务（尤其是图片）的对象。注意它比 ImgService 的
 *       {@code OWN_GENERATED_NAME} 还多了前缀要求。</li>
 *   <li>扩展名白名单 + 大小上限，在<b>写之前</b>拒绝。</li>
 * </ol>
 *
 * <p>⚠️ 本类**不做任何内容嗅探**：PDF/Office 没有轻量的"内容说话"手段，硬做只能做半套，
 * 反而给人"已校验"的错觉。真正的把关在设计上：对象名由服务生成、只存白名单扩展名、
 * 下载一律带 {@code Content-Disposition: attachment} + {@code X-Content-Type-Options: nosniff}
 * （见 {@code FileController}），浏览器不会把它当页面/脚本执行。
 */
@Service
public class FileService {

    private static final Logger log = LoggerFactory.getLogger(FileService.class);

    /**
     * 资料大小上限 30MB。
     *
     * <p>比单据图片（5MB）和底图（10MB）都放宽一档：扫描的 PDF 讲稿、带图的施工资料动辄十几 MB。
     * <b>必须与 {@code application.yml} 的 {@code spring.servlet.multipart.max-file-size} 同步</b> ——
     * 那条 2026-10-15 的 Critical 1 就是两者不一致：Spring 在进 handler 之前按 multipart 上限截断，
     * 代码里这条判断永远进不去，用户看到的是"上传失败"而日志里什么都没有。
     */
    private static final long MAX_FILE_SIZE = 30L * 1024 * 1024;

    /** 对象键前缀：与图片对象（裸 <32hex>.png）在同一个桶里靠前缀区分 */
    private static final String ARCHIVE_PREFIX = "archive/";

    /**
     * 只认本服务生成的形状：{@code archive/<32位hex>.<1-8位扩展名>}。
     *
     * <p>读和删都走这条。它同时挡住了路径穿越（不许出现 `/` 除了固定的那个前缀）、
     * 别的业务的对象、以及裸的图片名。
     */
    private static final Pattern ARCHIVE_NAME =
            Pattern.compile("^archive/[0-9a-f]{32}\\.[a-z0-9]{1,8}$");

    /**
     * 允许的扩展名白名单。
     *
     * <p>刻意**不含** {@code exe/bat/js/html/svg} 之类：SVG 能带脚本、HTML 能当页面执行，
     * 存档场景不需要它们。{@code zip} 保留 —— 扫描件常常打包上传。
     */
    private static final Set<String> ALLOWED_EXT = Set.of(
            "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
            "png", "jpg", "jpeg", "txt", "csv", "zip");

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public FileService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    /**
     * 原样存入（不重编码、不改字节）。
     *
     * @return 对象名（不含 {@code archive/} 前缀）+ 原文件名 + 大小 + Content-Type
     */
    public FileUploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new BusinessException("文件大小不能超过 30MB");
        }

        String origName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extensionOf(origName);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("不支持的文件类型：" + (ext.isEmpty() ? "（无扩展名）" : ext)
                    + "；允许 " + String.join("/", ALLOWED_EXT.stream().sorted().toList()));
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传内容失败, origName={}", origName, e);
            throw new BusinessException("读取上传内容失败，请重试");
        }
        if (bytes.length == 0) {
            throw new BusinessException("上传文件不能为空");
        }
        if (bytes.length > MAX_FILE_SIZE) {
            // getSize() 可能来自分区头，以实际字节为准再判一次
            throw new BusinessException("文件大小不能超过 30MB");
        }

        String bare = UUID.randomUUID().toString().replace("-", "") + "." + ext;
        String objectName = ARCHIVE_PREFIX + bare;
        String contentType = contentTypeOf(ext);

        try {
            ensureBucket();
            try (InputStream in = new ByteArrayInputStream(bytes)) {
                minioClient.putObject(PutObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(objectName)
                        .stream(in, bytes.length, -1)
                        .contentType(contentType)
                        .build());
            }
        } catch (Exception e) {
            log.error("资料上传失败, object={}, size={}", objectName, bytes.length, e);
            throw new BusinessException("文件上传失败，请稍后重试");
        }
        log.info("资料已上传, object={}, size={}, origName={}", objectName, bytes.length, origName);
        return new FileUploadResult(bare, origName, bytes.length, contentType);
    }

    /**
     * 读取原样字节。
     *
     * @return 对象不存在返回 null（由 controller 翻成 404）；MinIO 不可用抛 BusinessException
     */
    public byte[] read(String fileName) {
        String objectName = requireArchiveName(fileName);
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectName)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return null;
            }
            log.error("读取资料失败, object={}, code={}", objectName, code, e);
            throw new BusinessException("读取文件失败，请稍后重试");
        } catch (Exception e) {
            log.error("读取资料失败, object={}", objectName, e);
            throw new BusinessException("读取文件失败，请稍后重试");
        }
    }

    /**
     * 读对象上存的 Content-Type（下载时用）。
     *
     * <p>对象不存在或读不到时返回按扩展名推断的值 —— 下载这种只读动作不该因为
     * 取不到元数据就整个失败。
     */
    public String contentTypeOfStored(String fileName) {
        String objectName = requireArchiveName(fileName);
        try {
            String ct = minioClient.statObject(StatObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build()).contentType();
            if (ct != null && !ct.isBlank()) {
                return ct;
            }
        } catch (Exception e) {
            log.debug("读取对象 Content-Type 失败，回落到扩展名推断：object={}, err={}", objectName, e.getMessage());
        }
        return contentTypeOf(extensionOf(objectName));
    }

    /** 删除。只认本服务生成的形状，且**不做物理兜底**（对象不存在也算成功） */
    public void delete(String fileName) {
        String objectName = requireArchiveName(fileName);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(objectName)
                    .build());
        } catch (Exception e) {
            log.error("资料删除失败, object={}", objectName, e);
            throw new BusinessException("文件删除失败，请稍后重试");
        }
    }

    // ================= 内部 =================

    /**
     * 只认 {@code archive/<32hex>.<ext>}，且扩展名必须是白名单里的。
     *
     * <p>调用方传进来的可能是裸名字（不带前缀），补上前缀再判 —— DB 里存裸名、API 也收裸名，
     * 前缀纯粹是实现细节。
     *
     * <p>⚠️ 扩展名这一档是<b>纵深防御</b>：白名单在 upload 时已经卡过，理论上不可能存在
     * {@code archive/<hex>.exe}。但如果哪天有人手工往桶里塞了一个，读/删也不该认它。
     */
    private static String requireArchiveName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        String name = fileName.trim();
        if (name.contains("..")) {
            throw new BusinessException("文件名非法");
        }
        String objectName = name.startsWith(ARCHIVE_PREFIX) ? name : ARCHIVE_PREFIX + name;
        if (!ARCHIVE_NAME.matcher(objectName).matches()) {
            throw new BusinessException("文件名非法：只允许操作资料归档生成的对象");
        }
        int dot = objectName.lastIndexOf('.');
        String ext = dot < 0 ? "" : objectName.substring(dot + 1);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new BusinessException("文件名非法：扩展名不在允许范围内");
        }
        return objectName;
    }

    private static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        String name = fileName.toLowerCase(Locale.ROOT);
        int dot = name.lastIndexOf('.');
        if (dot < 0 || dot == name.length() - 1) {
            return "";
        }
        return name.substring(dot + 1);
    }

    private static String contentTypeOf(String ext) {
        return switch (ext) {
            case "pdf" -> "application/pdf";
            case "doc" -> "application/msword";
            case "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            case "xls" -> "application/vnd.ms-excel";
            case "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
            case "ppt" -> "application/vnd.ms-powerpoint";
            case "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation";
            case "png" -> "image/png";
            case "jpg", "jpeg" -> "image/jpeg";
            case "txt" -> "text/plain";
            case "csv" -> "text/csv";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
    }

    /** 与 ImgService#ensureBucket 同一个理由与同一份并发处理（桶已存在不是失败） */
    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucket())
                .build());
        if (exists) {
            return;
        }
        try {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            if (message.contains("BucketAlreadyOwnedByYou") || message.contains("BucketAlreadyExists")) {
                log.debug("桶已由并发请求创建，忽略：{}", message);
                return;
            }
            throw e;
        }
    }
}
