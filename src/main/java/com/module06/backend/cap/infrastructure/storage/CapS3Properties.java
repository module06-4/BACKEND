package com.module06.backend.cap.infrastructure.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;

/*
 * CAP 녹음 오브젝트 스토리지(S3) 버킷 설정. 리전은 여기 안 둔다 — CapS3ClientConfig가
 * ap-northeast-2로 하드코딩한다(이 프로젝트가 쓰는 유일한 리전, infra/deploy.sh의 AWS CLI
 * 호출도 이미 같은 값을 하드코딩해서 쓴다).
 *
 * <h2>설정이 비면 부팅에서 막는다</h2>
 * CapS3ClientConfig가 @Profile("prod")라 이 레코드도 prod에서만 바인딩된다 — 로컬/테스트는
 * 이 클래스 자체가 생성되지 않으므로 값이 없어도 영향 없다. prod인데 비어 있으면 모든 업로드/
 * 재생/삭제가 즉시 실패하므로, 그건 런타임이 아니라 생성 시점(부팅)에서 끊는다
 * (AiLayerProperties와 동일 관용구).
 */
@ConfigurationProperties(prefix = "cap.s3")
public record CapS3Properties(String bucket) {

    public CapS3Properties {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalStateException("cap.s3.bucket 이 비어 있습니다. "
                    + "녹음 업로드/재생/삭제가 전부 실패하므로 부팅을 중단합니다 (SSM /z/prod/S3_BUCKET).");
        }
    }
}
