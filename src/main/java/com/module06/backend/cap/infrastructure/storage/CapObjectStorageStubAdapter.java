package com.module06.backend.cap.infrastructure.storage;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import com.module06.backend.cap.application.port.out.CapObjectStoragePort;

/* comment.
    CapObjectStoragePort 개발용 구현체(project의 ProjectAttachmentStorageStubAdapter와 동일 패턴).
    운영은 CapS3ObjectStorageAdapter(#155)가 대신하므로 @Profile("!prod")로 운영에서만 확실히
    안 뜨게 막는다. 실제 파일 업로드 없음: 가짜 URL 문자열만 돌려준다.
*/
@Component
@Profile("!prod")
public class CapObjectStorageStubAdapter implements CapObjectStoragePort {

    private static final Logger log = LoggerFactory.getLogger(CapObjectStorageStubAdapter.class);
    private static final int EXPIRES_IN_SECONDS = 900; // 15분 — 문서(CAP-04) 예시값과 동일
    private static final int PLAYBACK_EXPIRES_IN_SECONDS = 10800; // 3시간 — 문서(재생 URL) 확정값

    // 진짜 S3를 안 부르고 가짜 URL 문자열만 만들어서 돌려줌
    @Override
    public IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType) {
        return new IssuedPartUploadUrl("https://stub-storage.local/upload/" + s3Key, EXPIRES_IN_SECONDS);
    }

    // 재생용 presigned GET도 가짜 URL만 돌려줌(운영은 CapS3ObjectStorageAdapter가 실제 GET 서명 발급).
    @Override
    public IssuedPlaybackUrl issuePlaybackUrl(String s3Key) {
        return new IssuedPlaybackUrl("https://stub-storage.local/playback/" + s3Key, PLAYBACK_EXPIRES_IN_SECONDS);
    }

    // 실제 S3 삭제 없음 — 로그만(실 어댑터에서 S3 DeleteObject로 대체).
    @Override
    public void deleteRecording(String s3Key) {
        log.info("녹음 삭제(stub) — s3Key={}. 실제 S3 DeleteObject는 후속 어댑터에서 수행.", s3Key);
    }

    // 실제 업로드가 없으니 검증할 것도 없다 — 항상 통과시켜 로컬 개발 흐름을 막지 않는다.
    @Override
    public boolean objectMatches(String s3Key, long expectedSizeBytes) {
        return true;
    }
}
