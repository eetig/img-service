package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import io.minio.http.Method;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.example.config.MinioProperties;
import org.example.dto.UploadResult;
import org.example.dto.VesselDrawingResult;
import org.example.exception.BusinessException;
import org.example.util.ImageGuard;
import org.example.util.ImageThumbnailUtil;
import org.example.util.VesselDrawingUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImgService {

    private static final Logger log = LoggerFactory.getLogger(ImgService.class);

    private static final long MAX_SIZE = 5L * 1024 * 1024;

    /** 容器底图的上限：图纸扫描件常有几 MB，比单据照片放宽一档 */
    private static final long VESSEL_DRAWING_MAX_SIZE = 10L * 1024 * 1024;
    private static final int URL_EXPIRE_MINUTES = 60;

    /** 缩略图对象名前缀（★ 契约冻结，见《前后端改动统筹》2.2） */
    private static final String THUMB_PREFIX = "thumb_";

    /** 缩略图宽度（px） */
    private static final int THUMB_WIDTH = 200;

    /**
     * 对象名安全校验（2026-10-15 code-review 的 Critical 3）。
     *
     * <p>桶是**业务共享桶**：除了本服务写的单据图/底图，别的业务也往里放对象。而
     * `delete` / `file/{fileName}` / `getPreviewUrl` 收到的 `fileName` 完全由调用方给定，
     * 原先一个字符都不校验 —— 等于把「删除/读取桶内任意对象」挂在免鉴权端点上。
     * 这里收口成：单段（无 `/`）、安全字符、不含 `..`、长度受限。
     */
    private static final Pattern SAFE_OBJECT_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$");

    /**
     * 更严的一档：本服务自己生成的规范形状 `<32位hex>(-dark)?.<jpg|jpeg|png>`（可带 `thumb_` 前缀）。
     * 删除是破坏性动作，所以只认自己生成的形状 —— 别家的对象、嵌套 key 一律拒绝。
     */
    private static final Pattern OWN_GENERATED_NAME =
            Pattern.compile("^(thumb_)?[0-9a-f]{32}(-dark)?\\.(jpg|jpeg|png)$");

    private final MinioClient minioClient;
    private final MinioProperties properties;

    public ImgService(MinioClient minioClient, MinioProperties properties) {
        this.minioClient = minioClient;
        this.properties = properties;
    }

    public UploadResult upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        if (file.getSize() > MAX_SIZE) {
            throw new BusinessException("文件大小不能超过 5MB");
        }

        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            log.error("读取上传内容失败", e);
            throw new BusinessException("读取上传内容失败，请重试");
        }

        // 内容说话，不看扩展名：改名的假图片（JPEG 叫 .png）、像素炸弹都在这里被拦下
        ImageGuard.Probe probe;
        try {
            probe = ImageGuard.probe(bytes);
        } catch (IOException e) {
            // ImageGuard 的文案是本服务自己写的，可以直接给用户看
            throw new BusinessException(e.getMessage());
        }
        String ext = probe.format();
        String contentType = probe.contentType();
        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext;

        try {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                    .contentType(contentType)
                    .build());

            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .expiry(URL_EXPIRE_MINUTES, TimeUnit.MINUTES)
                    .build());

            return new UploadResult(url, fileName, (long) bytes.length);
        } catch (Exception e) {
            // 对外只回固定文案：e.getMessage() 里会带 MinIO 错误码、内网 endpoint、桶名对象名
            log.error("图片上传失败, fileName={}", fileName, e);
            throw new BusinessException("图片上传失败，请稍后重试");
        }
    }

    public void delete(String fileName) {
        String name = requireOwnGeneratedName(fileName);
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(name)
                    .build());
        } catch (Exception e) {
            log.error("图片删除失败, object={}", name, e);
            throw new BusinessException("图片删除失败，请稍后重试");
        }
    }

    public String getPreviewUrl(String fileName) {
        String name = requireSafeObjectName(fileName);
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(name)
                    .expiry(URL_EXPIRE_MINUTES, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            log.error("生成预览地址失败, object={}", name, e);
            throw new BusinessException("生成预览地址失败，请稍后重试");
        }
    }

    /** 所有以对象名为入参的入口都先过这道（读 / 预览） */
    private static String requireSafeObjectName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        String name = fileName.trim();
        if (name.contains("..") || !SAFE_OBJECT_NAME.matcher(name).matches()) {
            throw new BusinessException("文件名非法");
        }
        return name;
    }

    /** 删除额外收一档：只认本服务自己生成的形状 */
    private static String requireOwnGeneratedName(String fileName) {
        String name = requireSafeObjectName(fileName);
        if (!OWN_GENERATED_NAME.matcher(name).matches()) {
            throw new BusinessException("文件名非法：只允许操作本服务生成的图片对象");
        }
        return name;
    }

    // ================= 原图 / 缩略图读取 =================

    /**
     * 读取对象原始字节。
     *
     * @return 对象不存在返回 null；MinIO 不可用等异常抛 BusinessException
     */
    public byte[] readObject(String fileName) {
        String name = requireSafeObjectName(fileName);
        try (InputStream in = minioClient.getObject(GetObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(name)
                .build())) {
            return in.readAllBytes();
        } catch (ErrorResponseException e) {
            String code = e.errorResponse() == null ? "" : e.errorResponse().code();
            if ("NoSuchKey".equals(code) || "NoSuchBucket".equals(code)) {
                return null;
            }
            log.error("读取图片失败, object={}, code={}", name, code, e);
            throw new BusinessException("读取图片失败，请稍后重试");
        } catch (Exception e) {
            log.error("读取图片失败, object={}", name, e);
            throw new BusinessException("读取图片失败，请稍后重试");
        }
    }

    /**
     * 读取缩略图：thumb_{fileName} 已存在则直接返回，否则生成并回存。
     *
     * 原图不存在、或生成失败，一律返回 null（由调用方转 404），
     * <b>不返回原图兜底</b>——否则原图会被按缩略图 URL 缓存，后续无法纠正（契约 2.2）。
     */
    public byte[] readThumbnail(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return null;
        }
        String thumbName = THUMB_PREFIX + fileName;

        byte[] cached = readObject(thumbName);
        if (cached != null) {
            return cached;
        }

        byte[] original = readObject(fileName);
        if (original == null) {
            return null;
        }

        byte[] thumb;
        try {
            thumb = ImageThumbnailUtil.scaleToWidth(original, THUMB_WIDTH);
        } catch (Exception e) {
            log.warn("缩略图生成失败, fileName={}, 原因={}", fileName, e.getMessage());
            return null;
        }

        storeThumbnail(thumbName, thumb);
        return thumb;
    }

    /** 回存缩略图；失败只记日志，不影响本次返回（下次请求会重新生成） */
    private void storeThumbnail(String thumbName, byte[] data) {
        try {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(thumbName)
                    .stream(new ByteArrayInputStream(data), data.length, -1)
                    .contentType("image/jpeg")
                    .build());
        } catch (Exception e) {
            log.warn("缩略图回存失败, object={}, 原因={}", thumbName, e.getMessage());
        }
    }

    // ===== 容器底图（设备数据维护页用，2026-10-06）=====

    /**
     * 容器底图上传：**一张进、两张出**。
     *
     * <p>与 {@link #upload} 分开而不是加个参数：两者的产物形状根本不同
     * （那个回一个 URL 供单据图片直接显示；这个要回两个文件名，且必须落成
     * 「白纸版 + 亮线版」一对，文件名还要满足前端的 `-dark` 后缀约定）。
     * 混在一个方法里，两边的规则会互相牵扯。
     *
     * <p>处理口径与 `resources/compress-vessel-images.py` 完全一致（见
     * {@link VesselDrawingUtil}），这样后台上传的底图与内置那四张长得一样。
     */
    public VesselDrawingResult uploadVesselDrawing(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BusinessException("上传文件不能为空");
        }
        // 底图比单据照片大：图纸扫描件常有几 MB
        if (file.getSize() > VESSEL_DRAWING_MAX_SIZE) {
            throw new BusinessException("底图文件不能超过 10MB");
        }

        String ext = getExtension(file.getOriginalFilename());
        if (!"jpg".equalsIgnoreCase(ext) && !"png".equalsIgnoreCase(ext)) {
            throw new BusinessException("仅支持 jpg/png 格式的图片");
        }

        BufferedImage src;
        try {
            byte[] bytes = file.getBytes();
            // 先量尺寸再解码：文件大小上限挡不住像素炸弹（见 ImageGuard 注释）
            ImageGuard.assertDecodable(bytes);
            src = ImageIO.read(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            // ImageGuard 的尺寸/格式文案是本服务自己写的，可以直接给用户看
            throw new BusinessException(e.getMessage());
        }
        if (src == null) {
            // ImageIO 读不出内容时返回 null（不是抛异常）—— 不判这一下会 NPE
            throw new BusinessException("无法识别的图片内容，请换一张 jpg/png");
        }

        BufferedImage whitePaper = VesselDrawingUtil.toWhitePaper(src);
        BufferedImage brightLines = VesselDrawingUtil.toBrightLines(whitePaper);

        String base = UUID.randomUUID().toString().replace("-", "");
        String fileName = base + ".png";
        String darkFileName = base + "-dark.png";
        try {
            ensureBucket();
            putPng(fileName, whitePaper);
            putPng(darkFileName, brightLines);
        } catch (Exception e) {
            log.error("容器底图上传失败, fileName={}", fileName, e);
            // 「一张进、两张出」是契约：第二张写失败会把第一张留成孤儿（重试一次再留一对），
            // 所以这里回滚已写入的那张。回滚失败只记日志，不改变对外结果。
            try {
                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(properties.getBucket())
                        .object(fileName)
                        .build());
            } catch (Exception rollbackError) {
                log.warn("底图回滚失败，已留下孤儿对象 fileName={}", fileName, rollbackError);
            }
            throw new BusinessException("底图保存失败，请稍后重试");
        }
        return new VesselDrawingResult(fileName, darkFileName);
    }

    /** 把内存里的图编码成 PNG 存进 MinIO（统一 png：脚本那边也是 png，两端命名要对上） */
    private void putPng(String objectName, BufferedImage image) throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        ImageIO.write(image, "png", buffer);
        byte[] bytes = buffer.toByteArray();
        minioClient.putObject(PutObjectArgs.builder()
                .bucket(properties.getBucket())
                .object(objectName)
                .stream(new ByteArrayInputStream(bytes), bytes.length, -1)
                .contentType("image/png")
                .build());
    }

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
            // 两个并发首传会一起 makeBucket：后来者拿到 BucketAlreadyOwnedByYou / BucketAlreadyExists。
            // 这不是失败（桶已经在了），否则会被包成「图片上传失败」误导排查方向。
            String message = String.valueOf(e.getMessage());
            if (message.contains("BucketAlreadyOwnedByYou") || message.contains("BucketAlreadyExists")) {
                log.debug("桶已由并发请求创建，忽略：{}", message);
                return;
            }
            throw e;
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
