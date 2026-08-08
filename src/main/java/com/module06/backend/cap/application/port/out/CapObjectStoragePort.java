package com.module06.backend.cap.application.port.out;

/* comment.
    CAP과 storage 사이의 경계 — 청크 업로드용 presigned PUT URL 발급만 다룬다.
    project의 ProjectAttachmentStoragePort와 동일 패턴. 구현체는 두 개 — 로컬/테스트는
    CapObjectStorageStubAdapter(@Profile("!prod")), 운영은 CapS3ObjectStorageAdapter(@Profile("prod")).
*/
public interface CapObjectStoragePort {

    /** 이 s3Key로 업로드 가능한 presigned PUT URL 하나 발급 */
    IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType);

    /** 이 s3Key의 녹음본을 재생할 presigned GET URL 하나 발급 (HTTP Range 지원 — 탐색바 seek 가능) */
    IssuedPlaybackUrl issuePlaybackUrl(String s3Key);

    /** 이 s3Key의 객체를 삭제한다 (녹음 삭제 CAP-15 — 되돌릴 수 없음) */
    void deleteRecording(String s3Key);

    /**
     * 청크 완료 통보(CAP-07)가 저장하기 전에, 클라이언트가 주장하는 s3Key·크기가 실제 스토리지
     * 상태와 일치하는지 확인한다. 클라이언트가 업로드도 안 하고 임의 크기로 완료를 통보하는 것을
     * 막는다 — 요청 본문 sizeBytes를 대체하지 않고(그러면 파급이 커진다) "검증"만 한다.
     */
    boolean objectMatches(String s3Key, long expectedSizeBytes);

    record IssuedPartUploadUrl(String presignedUrl, int expiresInSeconds) {
    }

    record IssuedPlaybackUrl(String url, int expiresInSeconds) {
    }
}
