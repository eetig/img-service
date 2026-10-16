package org.example.config;

import io.minio.MinioClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(MinioProperties.class)
public class MinioConfig {

    @Bean
    public MinioClient minioClient(MinioProperties properties) {
        MinioClient client = MinioClient.builder()
                .endpoint(properties.getEndpoint())
                .credentials(properties.getAccessKey(), properties.getSecretKey())
                .build();
        // ⚠️ 必须显式设超时（2026-10-16 性能审查，变更-074）：
        //   MinIO SDK 默认 connect / write / read 各 5 分钟（S3Base.DEFAULT_CONNECTION_TIMEOUT），
        //   而本服务只有一个共享 client、Tomcat 默认 200 个工作线程 —— MinIO 一旦抖动、磁盘满或重启，
        //   线程会被逐个挂满 5 分钟，表现从「某几张图慢」放大成「整站图片不可用」。
        //   三个值都要设：只设 connect 挡不住「连上了但写/读挂住」。
        //   参数单位是毫秒：(connect, write, read)。
        client.setTimeout(3000, 30000, 30000);
        return client;
    }
}
