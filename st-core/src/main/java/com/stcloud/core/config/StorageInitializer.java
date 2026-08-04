package com.stcloud.core.config;

import com.stcloud.common.config.S3StorageConfig;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.DeletePublicAccessBlockRequest;
import software.amazon.awssdk.services.s3.model.GetBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.PutBucketCorsRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.CORSConfiguration;
import software.amazon.awssdk.services.s3.model.CORSRule;

import java.util.List;

@Slf4j
@Configuration
public class StorageInitializer implements ApplicationRunner {

    @Resource
    private S3Client s3Client;

    @Resource
    private S3StorageConfig s3StorageConfig;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) {
        initBucket(s3StorageConfig.getBucket());
        initBucket(s3StorageConfig.getChunkBucket());
        initBucket(s3StorageConfig.getPreviewBucket());
    }

    private void initBucket(String bucketName) {
        try {
            s3Client.headBucket(HeadBucketRequest.builder().bucket(bucketName).build());
            log.info("S3 Bucket已存在: {}", bucketName);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                try {
                    s3Client.createBucket(CreateBucketRequest.builder().bucket(bucketName).build());
                    log.info("S3 Bucket创建成功: {}", bucketName);
                } catch (Exception ex) {
                    log.error("S3 Bucket创建失败: {}, error: {}", bucketName, ex.getMessage());
                }
            } else {
                log.error("S3 Bucket检查失败: {}, error: {}", bucketName, e.getMessage());
            }
        }
        // 确保 CORS 配置存在（Web 前端直接 PUT 预签名 URL 到 RustFS/S3 需要 CORS 支持）
        ensureCorsConfig(bucketName);
    }

    private void ensureCorsConfig(String bucketName) {
        try {
            s3Client.getBucketCors(GetBucketCorsRequest.builder().bucket(bucketName).build());
            log.info("S3 Bucket CORS已配置: {}", bucketName);
        } catch (S3Exception e) {
            // 没有配置 CORS（NoSuchCORSConfiguration），添加允许前端 PUT/GET 的规则
            if (!"NoSuchCORSConfiguration".equals(e.awsErrorDetails() != null ? e.awsErrorDetails().errorCode() : null) && e.statusCode() != 404) {
                log.warn("S3 Bucket CORS检查失败: {}, error: {}", bucketName, e.getMessage());
                return;
            }
            CORSConfiguration corsConfig = CORSConfiguration.builder()
                .corsRules(CORSRule.builder()
                    .allowedOrigins("*")
                    .allowedMethods("GET", "PUT", "POST", "DELETE", "HEAD")
                    .allowedHeaders("*")
                    .exposeHeaders("ETag", "x-amz-request-id")
                    .maxAgeSeconds(3600)
                    .build())
                .build();
            try {
                s3Client.putBucketCors(PutBucketCorsRequest.builder()
                    .bucket(bucketName)
                    .corsConfiguration(corsConfig)
                    .build());
                log.info("S3 Bucket CORS配置成功: {}", bucketName);
            } catch (Exception ex) {
                log.warn("S3 Bucket CORS配置失败: {}, error: {}（RustFS 可能不支持 CORS API，请在 RustFS 管理界面手动配置）", bucketName, ex.getMessage());
            }
        }
    }
}
