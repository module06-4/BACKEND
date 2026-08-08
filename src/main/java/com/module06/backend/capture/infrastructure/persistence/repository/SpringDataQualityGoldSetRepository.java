package com.module06.backend.capture.infrastructure.persistence.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.module06.backend.capture.infrastructure.persistence.entity.QualityGoldSetJpaEntity;

/* quality_gold_set 접근. 회의당 버전이 쌓이므로 "마지막 버전"을 묻는 조회가 필요하다. */
public interface SpringDataQualityGoldSetRepository extends JpaRepository<QualityGoldSetJpaEntity, Long> {

    /*
     * 이 회의의 마지막 버전. **파생 쿼리로 둔다** — 같은 조건을 @Query 로 적으면 Gate1(QUERY_002)에
     * 걸리고, IX_QUALITY_GOLD_SET_LATEST(meeting_id, version DESC)와 정렬을 손으로 맞춰야 한다.
     */
    Optional<QualityGoldSetJpaEntity> findTopByMeetingIdOrderByVersionDesc(long meetingId);
}
