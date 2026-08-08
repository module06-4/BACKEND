package com.module06.backend.cap.infrastructure.storage;

import java.time.Duration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.core.client.config.ClientOverrideConfiguration;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/*
 * CAP S3 클라이언트/프리사이너 빈. @Profile("prod")만 — 로컬/테스트는 CapObjectStorageStubAdapter가
 * 대신하므로 AWS 자격증명이 전혀 필요 없다.
 *
 * 자격증명은 DefaultCredentialsProvider(체인)로 받는다 — 운영 EC2 인스턴스에 붙은 IAM 역할을
 * 자동으로 줍는다. 액세스키/시크릿키를 SSM에 따로 안 둔다: 정적 키보다 인스턴스 역할이
 * 안전하고(키 유출 표면 자체가 없음), 로테이션도 AWS가 알아서 한다.
 *
 * 리전은 하드코딩(ap-northeast-2) — CapS3Properties 주석 참고.
 *
 * ⚠️ API 호출 시간 제한을 명시한다(CodeRabbit 지적) — CaptureUploadService.completePartUpload가
 * 트랜잭션(DB 커넥션 점유) 안에서 동기 headObject를 부른다. 시간 제한이 없으면 S3 장애/지연 시
 * SDK 기본 재시도까지 겹쳐 커넥션을 오래 붙들어 풀 고갈로 번질 수 있다. attempt(개별 시도)는
 * call(전체, 재시도 포함) 이하여야 한다.
 */
@Configuration
@Profile("prod")
@EnableConfigurationProperties(CapS3Properties.class)
public class CapS3ClientConfig {

    private static final Region REGION = Region.AP_NORTHEAST_2;
    private static final Duration API_CALL_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration API_CALL_ATTEMPT_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public S3Client capS3Client() {
        return S3Client.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .overrideConfiguration(ClientOverrideConfiguration.builder()
                        .apiCallTimeout(API_CALL_TIMEOUT)
                        .apiCallAttemptTimeout(API_CALL_ATTEMPT_TIMEOUT)
                        .build())
                .build();
    }

    @Bean
    public S3Presigner capS3Presigner() {
        return S3Presigner.builder()
                .region(REGION)
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }
}
