package com.module06.backend.cap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectResponse;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import com.module06.backend.cap.application.port.out.CapObjectStoragePort;

/*
 * S3 프로덕션 어댑터(#155)의 presigned PUT/GET URL 서명 파라미터·만료·삭제 요청 조립을 검증한다.
 * S3Presigner의 presign*은 순수 로컬 SigV4 서명 계산이라 실제 AWS 네트워크 호출이 없다 —
 * 가짜 정적 자격증명(StaticCredentialsProvider)으로도 URL이 정상 생성된다. deleteRecording만
 * 실제 API 호출(S3Client.deleteObject)이라 Mockito로 검증한다.
 */
@DisplayName("CAP S3 오브젝트 스토리지 어댑터")
class CapS3ObjectStorageAdapterTest {

    private static final String BUCKET = "test-bucket";
    private static final String KEY = "stt-temp/org-1/meeting-500/segments/0/parts/0001.webm";

    private final S3Presigner presigner = S3Presigner.builder()
            .region(Region.AP_NORTHEAST_2)
            .credentialsProvider(StaticCredentialsProvider.create(
                    AwsBasicCredentials.create("test-access-key", "test-secret-key")))
            .build();

    /* PUT 서명 URL에 버킷·키·Content-Type이 반영되고, 만료가 900초(15분)로 응답되는지 검증한다.
       Content-Type이 실제로 서명 대상(X-Amz-SignedHeaders)에 포함되는지까지 봐야 한다(CodeRabbit
       지적) — 안 그러면 .contentType(...) 호출을 지워도 이 테스트가 그대로 통과해서, 클라이언트가
       PUT 때 다른 Content-Type을 보내도 서명이 안 막아주는 회귀를 못 잡는다. */
    @Test
    @DisplayName("presign PUT: 버킷·키·Content-Type 서명이 담긴 URL과 900초 만료를 반환한다")
    void issuePartUploadUrl_returnsSignedPutUrl() {
        CapS3ObjectStorageAdapter adapter = adapter();

        CapObjectStoragePort.IssuedPartUploadUrl issued = adapter.issuePartUploadUrl(KEY, "audio/webm");

        assertThat(issued.presignedUrl()).contains(BUCKET).contains(KEY).contains("X-Amz-Expires=900");
        assertThat(issued.presignedUrl()).containsPattern("X-Amz-SignedHeaders=[^&]*content-type");
        assertThat(issued.expiresInSeconds()).isEqualTo(900);
    }

    /* GET 서명 URL에 버킷·키가 반영되고, 만료가 10800초(3시간)로 응답되는지 검증한다. */
    @Test
    @DisplayName("presign GET: 버킷·키가 담긴 URL과 10800초 만료를 반환한다")
    void issuePlaybackUrl_returnsSignedGetUrl() {
        CapS3ObjectStorageAdapter adapter = adapter();

        CapObjectStoragePort.IssuedPlaybackUrl issued = adapter.issuePlaybackUrl(KEY);

        assertThat(issued.url()).contains(BUCKET).contains(KEY).contains("X-Amz-Expires=10800");
        assertThat(issued.expiresInSeconds()).isEqualTo(10_800);
    }

    /* 삭제는 presign이 아니라 실제 DeleteObject 호출이므로, 요청에 버킷·키가 정확히 실리는지 검증한다. */
    @Test
    @DisplayName("delete: 지정한 버킷·키로 DeleteObject를 호출한다")
    void deleteRecording_callsDeleteObjectWithBucketAndKey() {
        S3Client s3Client = mock(S3Client.class);
        CapS3ObjectStorageAdapter adapter = new CapS3ObjectStorageAdapter(s3Client, presigner, properties());

        adapter.deleteRecording(KEY);

        verify(s3Client).deleteObject(any(DeleteObjectRequest.class));
    }

    /* 삭제 요청 자체가 지정한 버킷·키를 정확히 담고 있는지(다른 파일을 잘못 지우지 않는지) 검증한다. */
    @Test
    @DisplayName("delete: DeleteObject 요청에 버킷·키가 정확히 담긴다")
    void deleteRecording_requestHasCorrectBucketAndKey() {
        S3Client s3Client = mock(S3Client.class);
        CapS3ObjectStorageAdapter adapter = new CapS3ObjectStorageAdapter(s3Client, presigner, properties());

        adapter.deleteRecording(KEY);

        org.mockito.ArgumentCaptor<DeleteObjectRequest> captor =
                org.mockito.ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(s3Client).deleteObject(captor.capture());
        assertThat(captor.getValue().bucket()).isEqualTo(BUCKET);
        assertThat(captor.getValue().key()).isEqualTo(KEY);
    }

    /* 객체가 있고 크기가 요청과 같으면 true인지 검증한다(#155 — 완료 통보 전 실제 업로드 확인). */
    @Test
    @DisplayName("objectMatches: 객체가 있고 크기가 같으면 true다")
    void objectMatches_trueWhenSizeMatches() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(1_000L).build());
        CapS3ObjectStorageAdapter adapter = new CapS3ObjectStorageAdapter(s3Client, presigner, properties());

        assertThat(adapter.objectMatches(KEY, 1_000L)).isTrue();
    }

    /* 객체는 있지만 크기가 다르면(위조 통보) false인지 검증한다. */
    @Test
    @DisplayName("objectMatches: 크기가 다르면 false다")
    void objectMatches_falseWhenSizeDiffers() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenReturn(HeadObjectResponse.builder().contentLength(999L).build());
        CapS3ObjectStorageAdapter adapter = new CapS3ObjectStorageAdapter(s3Client, presigner, properties());

        assertThat(adapter.objectMatches(KEY, 1_000L)).isFalse();
    }

    /* 객체 자체가 없으면(업로드 안 함) false인지 검증한다. */
    @Test
    @DisplayName("objectMatches: 객체가 없으면 false다")
    void objectMatches_falseWhenMissing() {
        S3Client s3Client = mock(S3Client.class);
        when(s3Client.headObject(any(HeadObjectRequest.class)))
                .thenThrow(NoSuchKeyException.builder().build());
        CapS3ObjectStorageAdapter adapter = new CapS3ObjectStorageAdapter(s3Client, presigner, properties());

        assertThat(adapter.objectMatches(KEY, 1_000L)).isFalse();
    }

    private CapS3ObjectStorageAdapter adapter() {
        return new CapS3ObjectStorageAdapter(mock(S3Client.class), presigner, properties());
    }

    private CapS3Properties properties() {
        return new CapS3Properties(BUCKET);
    }
}
