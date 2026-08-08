package com.module06.backend.handover.application.port.out;

import com.module06.backend.handover.domain.model.HandoverInsight;

import java.util.List;

/**
 * 인수인계 파생 인텔리전스("레거시 컴파일러") 스냅샷의 영속 포트.
 * finalize(OFFBOARDING) 트랜잭션 내에서 handover 단위로 전량 교체(replace-all)한다.
 * 어댑터는 handover 모듈 자체가 소유(HandoverInsightPersistenceAdapter).
 */
public interface HandoverInsightPort {

    void replaceAllForHandover(Long handoverId, List<HandoverInsight> insights);

    List<HandoverInsight> findByHandoverId(Long handoverId);
}
