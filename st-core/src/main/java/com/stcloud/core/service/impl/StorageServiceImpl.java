package com.stcloud.core.service.impl;

import com.stcloud.common.config.S3StorageConfig;
import com.stcloud.common.exception.BusinessException;
import com.stcloud.common.response.ResultCode;
import com.stcloud.core.service.StorageService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.ResponseInputStream;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.*;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.*;

import java.io.InputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class StorageServiceImpl implements StorageService {

    @Resource
    private S3Client s3Client;

    @Resource
    private S3Presigner s3Presigner;

    @Resource
    private S3StorageConfig s3StorageConfig;

    private String bucket() {
        return s3StorageConfig.getBucket();
    }

    @Override
    public void uploadObject(String key, InputStream inputStream, long size, String contentType) {
        try {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .contentType(contentType)
                    .build();
            s3Client.putObject(request, RequestBody.fromInputStream(inputStream, size));
            log.info("S3简单上传成功: bucket={}, key={}", bucket(), key);
        } catch (S3Exception e) {
            log.error("S3简单上传失败: key={}, error={}", key, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public String generateDownloadUrl(String storagePath) {
        try {
            GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                    .signatureDuration(Duration.ofHours(1))
                    .getObjectRequest(g -> g.bucket(bucket()).key(storagePath))
                    .build();
            PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
            return presigned.url().toString();
        } catch (S3Exception e) {
            log.error("生成预签名下载URL失败: key={}, error={}", storagePath, e.getMessage());
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "生成下载链接失败");
        }
    }

    @Override
    public void deleteObject(String storagePath) {
        try {
            DeleteObjectRequest request = DeleteObjectRequest.builder()
                    .bucket(bucket())
                    .key(storagePath)
                    .build();
            s3Client.deleteObject(request);
            log.info("S3删除对象成功: bucket={}, key={}", bucket(), storagePath);
        } catch (S3Exception e) {
            log.error("S3删除对象失败: key={}, error={}", storagePath, e.getMessage());
        }
    }

    @Override
    public java.io.InputStream downloadObject(String storagePath) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(storagePath)
                    .build();
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            return response;
        } catch (S3Exception e) {
            log.error("S3下载对象失败: key={}, error={}", storagePath, e.getMessage());
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "文件下载失败");
        }
    }

    @Override
    public java.io.InputStream downloadObjectRange(String storagePath, long start, long end) {
        try {
            GetObjectRequest request = GetObjectRequest.builder()
                    .bucket(bucket())
                    .key(storagePath)
                    .range("bytes=" + start + "-" + end)
                    .build();
            ResponseInputStream<GetObjectResponse> response = s3Client.getObject(request);
            return response;
        } catch (S3Exception e) {
            log.error("S3范围下载对象失败: key={}, error={}", storagePath, e.getMessage());
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "文件下载失败");
        }
    }

    @Override
    public String initMultipartUpload(String key) {
        try {
            CreateMultipartUploadRequest request = CreateMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .build();
            CreateMultipartUploadResponse response = s3Client.createMultipartUpload(request);
            log.info("S3分片上传初始化成功: bucket={}, key={}, uploadId={}", bucket(), key, response.uploadId());
            return response.uploadId();
        } catch (S3Exception e) {
            log.error("S3分片上传初始化失败: key={}, error={}", key, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public String presignUploadPart(String key, String s3UploadId, int partNumber, Duration expiry) {
        try {
            UploadPartPresignRequest presignRequest = UploadPartPresignRequest.builder()
                    .signatureDuration(expiry != null ? expiry : Duration.ofHours(2))
                    .uploadPartRequest(u -> u
                            .bucket(bucket())
                            .key(key)
                            .uploadId(s3UploadId)
                            .partNumber(partNumber))
                    .build();
            PresignedUploadPartRequest presigned = s3Presigner.presignUploadPart(presignRequest);
            return presigned.url().toString();
        } catch (S3Exception e) {
            log.error("生成分片预签名URL失败: key={}, partNumber={}, error={}", key, partNumber, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

        @Override
    public void uploadPart(String key, String s3UploadId, int partNumber, InputStream inputStream, long size) {
        try {
            UploadPartRequest request = UploadPartRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .uploadId(s3UploadId)
                    .partNumber(partNumber)
                    .build();
            s3Client.uploadPart(request, RequestBody.fromInputStream(inputStream, size));
            log.debug("S3服务端分片写入成功: key={}, part={}, size={}", key, partNumber, size);
        } catch (S3Exception e) {
            log.error("S3服务端分片写入失败: key={}, part={}, error={}", key, partNumber, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public void completeMultipartUpload(String key, String s3UploadId) {
        try {
            // 通过ListParts获取所有已上传分片的ETag
            ListPartsRequest listRequest = ListPartsRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .uploadId(s3UploadId)
                    .build();
            ListPartsResponse listResponse = s3Client.listParts(listRequest);

            List<CompletedPart> completedParts = new ArrayList<>();
            for (Part part : listResponse.parts()) {
                completedParts.add(CompletedPart.builder()
                        .partNumber(part.partNumber())
                        .eTag(part.eTag())
                        .build());
            }

            CompletedMultipartUpload multipartUpload = CompletedMultipartUpload.builder()
                    .parts(completedParts)
                    .build();

            CompleteMultipartUploadRequest request = CompleteMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .uploadId(s3UploadId)
                    .multipartUpload(multipartUpload)
                    .build();

            s3Client.completeMultipartUpload(request);
            log.info("S3分片合并成功: bucket={}, key={}, parts={}", bucket(), key, completedParts.size());
        } catch (S3Exception e) {
            log.error("S3分片合并失败: key={}, uploadId={}, error={}", key, s3UploadId, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public void abortMultipartUpload(String key, String s3UploadId) {
        try {
            AbortMultipartUploadRequest request = AbortMultipartUploadRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .uploadId(s3UploadId)
                    .build();
            s3Client.abortMultipartUpload(request);
            log.info("S3分片上传已中止: bucket={}, key={}, uploadId={}", bucket(), key, s3UploadId);
        } catch (S3Exception e) {
            log.error("S3分片上传中止失败: key={}, uploadId={}, error={}", key, s3UploadId, e.getMessage());
        }
    }

    @Override
    public List<Integer> listUploadedParts(String key, String s3UploadId) {
        try {
            ListPartsRequest listRequest = ListPartsRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .uploadId(s3UploadId)
                    .build();
            ListPartsResponse listResponse = s3Client.listParts(listRequest);
            List<Integer> partNumbers = new ArrayList<>();
            for (Part part : listResponse.parts()) {
                partNumbers.add(part.partNumber());
            }
            log.info("S3查询已上传分片: key={}, uploadId={}, parts={}", key, s3UploadId, partNumbers.size());
            return partNumbers;
        } catch (S3Exception e) {
            log.error("S3查询已上传分片失败: key={}, uploadId={}, error={}", key, s3UploadId, e.getMessage());
            throw new BusinessException(ResultCode.STORAGE_SERVICE_ERROR, "查询上传状态失败");
        }
    }

    @Override
    public void uploadPartCopy(String sourceKey, long rangeStart, long rangeEnd,
                               String destKey, String s3UploadId, int partNumber) {
        try {
            UploadPartCopyRequest request = UploadPartCopyRequest.builder()
                    .sourceBucket(bucket())
                    .sourceKey(sourceKey)
                    .destinationBucket(bucket())
                    .destinationKey(destKey)
                    .uploadId(s3UploadId)
                    .partNumber(partNumber)
                    .copySourceRange("bytes=" + rangeStart + "-" + rangeEnd)
                    .build();
            s3Client.uploadPartCopy(request);
            log.debug("S3块复制成功: source={}, dest={}, part={}, range={}-{}",
                    sourceKey, destKey, partNumber, rangeStart, rangeEnd);
        } catch (S3Exception e) {
            log.error("S3块复制失败: source={}, dest={}, part={}, error={}",
                    sourceKey, destKey, partNumber, e.getMessage());
            throw new BusinessException(ResultCode.FILE_UPLOAD_FAILED);
        }
    }

    /**
     * 检查对象是否存在
     */
    public boolean doesObjectExist(String key) {
        try {
            HeadObjectRequest request = HeadObjectRequest.builder()
                    .bucket(bucket())
                    .key(key)
                    .build();
            s3Client.headObject(request);
            return true;
        } catch (S3Exception e) {
            return e.statusCode() != 404;
        }
    }
}
