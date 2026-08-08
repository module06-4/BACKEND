package com.module06.backend.capture.presentation.api.response;

import java.util.List;

import com.module06.backend.capture.application.usecase.GetQualityMetricsUseCase.QualityMetrics;

/*
 * QLTY-02 응답이다.
 *
 * <h2>지표의 분류를 함께 내려준다</h2>
 * 넷을 값만 나란히 주면 화면이 전부 품질 지표로 읽는다. **needsReviewRate 는 비용 지표**이고
 * 품질 목표로 삼으면 안 된다 — 줄어드는 이유가 둘(정말 정확해졌거나, 모델이 과신하거나)이라
 * 목표로 걸면 임계값을 낮춰 숫자를 맞추려는 유인이 생긴다(명세 QLTY-02 처리 정책).
 *
 * <h2>비율이 null 일 수 있다</h2>
 * 0.0 은 "다 틀렸다"이고 null 은 **"표본이 없어 못 잰다"**이다. 뭉치면 정답지를 안 만든 상태가
 * 모델이 완전히 실패한 것으로 보이는데, 그 둘은 해야 할 일이 정반대다.
 */
public record QualityMetricsResponse(
        GoldSetResponse goldSet,
        Double precision,
        Double recall,
        Double autoConfirmErrorRate,
        Double needsReviewRate,
        String promptVersion,
        String model,
        MetricKindResponse metricKind
) {

    public static QualityMetricsResponse from(QualityMetrics metrics) {
        return new QualityMetricsResponse(
                new GoldSetResponse(metrics.goldSet().meetingCount(), metrics.goldSet().actionCount()),
                metrics.precision(),
                metrics.recall(),
                metrics.autoConfirmErrorRate(),
                metrics.needsReviewRate(),
                metrics.promptVersion(),
                metrics.model(),
                MetricKindResponse.fixed());
    }

    /* actionCount 는 지표의 신뢰 구간이다 — 5건으로 잰 0.8 과 100건으로 잰 0.8 은 다르다. */
    public record GoldSetResponse(int meetingCount, int actionCount) {
    }

    /*
     * 어느 값이 무엇인지. 고정 문자열이지만 응답에 싣는다 — 화면이 넷을 같은 종류로 그리면
     * 비용 지표가 품질 목표로 둔갑한다.
     */
    public record MetricKindResponse(List<String> quality, List<String> gate, List<String> cost) {

        static MetricKindResponse fixed() {
            return new MetricKindResponse(
                    List.of("precision", "recall"),
                    List.of("autoConfirmErrorRate"),
                    List.of("needsReviewRate"));
        }
    }
}
