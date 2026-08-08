package com.module06.backend.handover.application.usecase;

import com.module06.backend.handover.domain.model.HandoverInsight;

import java.util.List;

public interface GetHandoverInsightsUseCase {

    List<HandoverInsight> getInsights(Long handoverId);
}
