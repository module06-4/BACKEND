package com.module06.backend.cap.infrastructure.storage;

import java.time.Duration;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedGetObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PresignedPutObjectRequest;
import software.amazon.awssdk.services.s3.presigner.model.PutObjectPresignRequest;

import com.module06.backend.cap.application.port.out.CapObjectStoragePort;

/*
 * CapObjectStoragePort의 실제 S3 구현체(#155) — CapObjectStorageStubAdapter를 prod에서 대체한다.
 * PUT/GET 둘 다 presigned URL만 서버가 발급하고, 실제 바이트 전송은 클라이언트가 S3와 직접
 * 주고받는다 — 녹음 파일이 이 서버를 거치지 않는다(대역폭·메모리 부담 없음).
 *
 * ⚠️ HTTP Range(재생 탐색바 seek)는 별도 서명이 필요 없다 — Range 헤더는 SigV4 서명 대상이
 * 아니라서, presigned GET URL에 클라이언트가 Range를 얹어 요청하면 S3가 그대로 처리한다.
 *
 * 업로드 크기 상한은 여기서 강제하지 않는다(presigned PUT 자체엔 크기 제한 조건을 걸 수 있지만
 * 이번 범위 밖) — 기존 CAP-07 정책대로 애플리케이션 레이어(RecordingPart.MAX_SIZE_BYTES)가 검증한다.
 */
@Component
@Profile("prod")
public class CapS3ObjectStorageAdapter implements CapObjectStoragePort {

    private static final Duration UPLOAD_URL_TTL = Duration.ofSeconds(900);      // 15분 — CAP-04 문서값
    private static final Duration PLAYBACK_URL_TTL = Duration.ofSeconds(10_800); // 3시간 — 재생 URL 확정값

    private final S3Client s3Client;
    private final S3Presigner s3Presigner;
    private final String bucket;

    public CapS3ObjectStorageAdapter(S3Client s3Client, S3Presigner s3Presigner, CapS3Properties properties) {
        this.s3Client = s3Client;
        this.s3Presigner = s3Presigner;
        this.bucket = properties.bucket();
    }

    @Override
    public IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType) {
        PutObjectRequest putObjectRequest = PutObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .contentType(contentType)
                .build();
        PutObjectPresignRequest presignRequest = PutObjectPresignRequest.builder()
                .signatureDuration(UPLOAD_URL_TTL)
                .putObjectRequest(putObjectRequest)
                .build();

        PresignedPutObjectRequest presigned = s3Presigner.presignPutObject(presignRequest);
        return new IssuedPartUploadUrl(presigned.url().toString(), (int) UPLOAD_URL_TTL.getSeconds());
    }

    @Override
    public IssuedPlaybackUrl issuePlaybackUrl(String s3Key) {
        GetObjectRequest getObjectRequest = GetObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build();
        GetObjectPresignRequest presignRequest = GetObjectPresignRequest.builder()
                .signatureDuration(PLAYBACK_URL_TTL)
                .getObjectRequest(getObjectRequest)
                .build();

        PresignedGetObjectRequest presigned = s3Presigner.presignGetObject(presignRequest);
        return new IssuedPlaybackUrl(presigned.url().toString(), (int) PLAYBACK_URL_TTL.getSeconds());
    }

    @Override
    public void deleteRecording(String s3Key) {
        s3Client.deleteObject(DeleteObjectRequest.builder()
                .bucket(bucket)
                .key(s3Key)
                .build());
    }

    // HEAD로 실제 존재·크기를 확인한다. 없으면 S3가 NoSuchKeyException을 던진다(HeadObject는
    // body가 없어서 404를 다른 예외들과 달리 이 타입으로 명확히 구분해준다).
    @Override
    public boolean objectMatches(String s3Key, long expectedSizeBytes) {
        HeadObjectResponse response;
        try {
            response = s3Client.headObject(HeadObjectRequest.builder()
                    .bucket(bucket)
                    .key(s3Key)
                    .build());
        } catch (NoSuchKeyException e) {
            return false;
        }
        return response.contentLength() != null && response.contentLength() == expectedSizeBytes;
    }
}
