package com.module06.backend.handover.infrastructure.persistence;

import com.module06.backend.handover.application.port.out.HandoverInsightPort;
import com.module06.backend.handover.domain.model.HandoverInsight;
import java.util.List;
import org.springframework.stereotype.Repository;

@Repository
public class HandoverInsightPersistenceAdapter implements HandoverInsightPort {

    private final HandoverInsightJpaRepository repository;

    public HandoverInsightPersistenceAdapter(HandoverInsightJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void replaceAllForHandover(Long handoverId, List<HandoverInsight> insights) {
        repository.deleteByHandoverId(handoverId);
        repository.saveAll(insights.stream()
            .map(HandoverInsightJpaEntity::from)
            .toList());
    }

    @Override
    public List<HandoverInsight> findByHandoverId(Long handoverId) {
        return repository.findByHandoverIdOrderByKindAscSortOrderAscIdAsc(handoverId).stream()
            .map(HandoverInsightJpaEntity::toDomain)
            .toList();
    }
}
