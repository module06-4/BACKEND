package com.module06.backend.handover.presentation.api.dto.response;

import com.module06.backend.handover.domain.model.HandoverInsight;
import com.module06.backend.handover.domain.model.HandoverInsightKind;

import java.time.LocalDateTime;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public record HandoverInsightResponse(
        List<InsightItemResponse> ownership,
        List<InsightItemResponse> orphanAlert,
        List<InsightItemResponse> askWhom,
        List<InsightItemResponse> contextTimeline
) {

    public static HandoverInsightResponse from(List<HandoverInsight> insights) {
        Map<HandoverInsightKind, List<InsightItemResponse>> grouped = insights.stream()
                .collect(Collectors.groupingBy(
                        HandoverInsight::getKind,
                        () -> new EnumMap<>(HandoverInsightKind.class),
                        Collectors.mapping(InsightItemResponse::from, Collectors.toList())
                ));
        return new HandoverInsightResponse(
                grouped.getOrDefault(HandoverInsightKind.OWNERSHIP, List.of()),
                grouped.getOrDefault(HandoverInsightKind.ORPHAN_ALERT, List.of()),
                grouped.getOrDefault(HandoverInsightKind.ASK_WHOM, List.of()),
                grouped.getOrDefault(HandoverInsightKind.CONTEXT_TIMELINE, List.of())
        );
    }

    public record InsightItemResponse(
            Long id,
            Long handoverId,
            Long actionId,
            HandoverInsightKind kind,
            String payload,
            int sortOrder,
            LocalDateTime createdAt,
            LocalDateTime updatedAt
    ) {
        static InsightItemResponse from(HandoverInsight insight) {
            return new InsightItemResponse(
                    insight.getId(),
                    insight.getHandoverId(),
                    insight.getActionId(),
                    insight.getKind(),
                    insight.getPayload(),
                    insight.getSortOrder(),
                    insight.getCreatedAt(),
                    insight.getUpdatedAt()
            );
        }
    }
}
