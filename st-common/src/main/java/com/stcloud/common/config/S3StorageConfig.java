package com.stcloud.common.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Data
@Configuration
@ConfigurationProperties(prefix = "stcloud.storage")
public class S3StorageConfig {

    private String endpoint = "http://127.0.0.1:9000";
    /**
     * 对外预签名地址：浏览器/客户端能访问的地址。
     * 留空时回退 endpoint；容器内网部署时应设为内网地址（如 http://rustfs:9000），
     * 而 endpoint 设为对外地址，避免服务端读 S3 走宿主机 NAT 成为瓶颈。
     */
    private String publicEndpoint;
    private String accessKey = "stcloud";
    private String secretKey = "stcloud123";
    private String region = "us-east-1";
    private String bucket = "stcloud";
    private String chunkBucket = "stcloud-chunks";
    private String previewBucket = "stcloud-preview";

    @Bean
    public S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner s3Presigner() {
        String presignEndpoint = (publicEndpoint != null && !publicEndpoint.isBlank())
                ? publicEndpoint : endpoint;
        return S3Presigner.builder()
                .endpointOverride(URI.create(presignEndpoint))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(accessKey, secretKey)))
                .region(Region.of(region))
                .serviceConfiguration(S3Configuration.builder()
                        .pathStyleAccessEnabled(true)
                        .build())
                .build();
    }
}
