package com.module06.backend.handover.infrastructure.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface HandoverInsightJpaRepository extends JpaRepository<HandoverInsightJpaEntity, Long> {

    void deleteByHandoverId(Long handoverId);

    List<HandoverInsightJpaEntity> findByHandoverIdOrderByKindAscSortOrderAscIdAsc(Long handoverId);
}
