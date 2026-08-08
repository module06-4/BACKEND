package com.module06.backend.cap.infrastructure.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/* cap.s3.bucket이 비어 있으면 부팅 시점에 막는지 검증한다(AiLayerProperties와 동일 관용구). */
@DisplayName("CAP S3 설정")
class CapS3PropertiesTest {

    @Test
    @DisplayName("버킷이 있으면 정상 생성된다")
    void createsWithBucket() {
        CapS3Properties properties = new CapS3Properties("my-bucket");

        assertThat(properties.bucket()).isEqualTo("my-bucket");
    }

    @Test
    @DisplayName("버킷이 비어 있으면 즉시 예외를 던진다")
    void rejectsBlankBucket() {
        assertThatThrownBy(() -> new CapS3Properties("  ")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new CapS3Properties(null)).isInstanceOf(IllegalStateException.class);
    }
}
