package com.module06.backend.handover.application.service;

import com.module06.backend.handover.application.port.out.HandoverInsightPort;
import com.module06.backend.handover.domain.model.HandoverInsight;
import com.module06.backend.handover.domain.model.HandoverInsightKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HandoverInsightQueryServiceTest {

    private static final Long HANDOVER_ID = 1000L;

    @Mock
    private HandoverInsightPort handoverInsightPort;

    private HandoverInsightQueryService handoverInsightQueryService;

    @BeforeEach
    void setUp() {
        handoverInsightQueryService = new HandoverInsightQueryService(handoverInsightPort);
    }

    @Test
    @DisplayName("인수인계 인사이트를 포트 조회 순서 그대로 반환한다")
    void returnsInsightsByHandoverId() {
        List<HandoverInsight> insights = List.of(
                insight(HandoverInsightKind.OWNERSHIP, 1),
                insight(HandoverInsightKind.ORPHAN_ALERT, 2),
                insight(HandoverInsightKind.ASK_WHOM, 3),
                insight(HandoverInsightKind.CONTEXT_TIMELINE, 4)
        );
        when(handoverInsightPort.findByHandoverId(HANDOVER_ID)).thenReturn(insights);

        List<HandoverInsight> result = handoverInsightQueryService.getInsights(HANDOVER_ID);

        assertThat(result).containsExactlyElementsOf(insights);
        verify(handoverInsightPort).findByHandoverId(HANDOVER_ID);
    }

    @Test
    @DisplayName("저장된 인사이트가 없으면 빈 목록을 반환한다")
    void returnsEmptyInsights() {
        when(handoverInsightPort.findByHandoverId(HANDOVER_ID)).thenReturn(List.of());

        List<HandoverInsight> result = handoverInsightQueryService.getInsights(HANDOVER_ID);

        assertThat(result).isEmpty();
        verify(handoverInsightPort).findByHandoverId(HANDOVER_ID);
    }

    private static HandoverInsight insight(HandoverInsightKind kind, int sortOrder) {
        return HandoverInsight.newSnapshot(HANDOVER_ID, 100L + sortOrder, kind, "{\"value\":" + sortOrder + "}", sortOrder);
    }
}
