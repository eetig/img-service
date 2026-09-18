package org.example.service;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.http.Method;
import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.example.config.MinioProperties;
import org.example.dto.UploadResult;
import org.example.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class ImgService {

    private static final long MAX_SIZE = 5L * 1024 * 1024;
    private static final int URL_EXPIRE_MINUTES = 60;

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

        String ext = getExtension(file.getOriginalFilename());
        if (!"jpg".equalsIgnoreCase(ext) && !"png".equalsIgnoreCase(ext)) {
            throw new BusinessException("仅支持 jpg/png 格式的图片");
        }

        String fileName = UUID.randomUUID().toString().replace("-", "") + "." + ext.toLowerCase();
        String contentType = "jpg".equalsIgnoreCase(ext) ? "image/jpeg" : "image/png";

        try (InputStream in = file.getInputStream()) {
            ensureBucket();
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .stream(in, file.getSize(), -1)
                    .contentType(contentType)
                    .build());

            String url = minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .expiry(URL_EXPIRE_MINUTES, TimeUnit.MINUTES)
                    .build());

            return new UploadResult(url, fileName, file.getSize());
        } catch (Exception e) {
            throw new BusinessException("图片上传失败：" + e.getMessage());
        }
    }

    public void delete(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .build());
        } catch (Exception e) {
            throw new BusinessException("图片删除失败：" + e.getMessage());
        }
    }

    public String getPreviewUrl(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new BusinessException("文件名不能为空");
        }
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(properties.getBucket())
                    .object(fileName)
                    .expiry(URL_EXPIRE_MINUTES, TimeUnit.MINUTES)
                    .build());
        } catch (Exception e) {
            throw new BusinessException("生成预览地址失败：" + e.getMessage());
        }
    }

    private void ensureBucket() throws Exception {
        boolean exists = minioClient.bucketExists(BucketExistsArgs.builder()
                .bucket(properties.getBucket())
                .build());
        if (!exists) {
            minioClient.makeBucket(MakeBucketArgs.builder()
                    .bucket(properties.getBucket())
                    .build());
        }
    }

    private String getExtension(String filename) {
        if (filename == null || !filename.contains(".")) {
            return "";
        }
        return filename.substring(filename.lastIndexOf(".") + 1);
    }
}
