package com.module06.backend.handover.application.service;

import com.module06.backend.handover.application.port.out.HandoverInsightPort;
import com.module06.backend.handover.application.usecase.GetHandoverInsightsUseCase;
import com.module06.backend.handover.domain.model.HandoverInsight;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@Transactional(readOnly = true)
public class HandoverInsightQueryService implements GetHandoverInsightsUseCase {

    private final HandoverInsightPort handoverInsightPort;

    public HandoverInsightQueryService(HandoverInsightPort handoverInsightPort) {
        this.handoverInsightPort = handoverInsightPort;
    }

    @Override
    public List<HandoverInsight> getInsights(Long handoverId) {
        return handoverInsightPort.findByHandoverId(handoverId);
    }
}
