package com.module06.backend.capture.application.usecase;

/*
 * QLTY-02 · 품질 지표 조회.
 *
 * <h2>지표 셋의 역할이 다르다</h2>
 * <pre>
 *   precision / recall        주 품질 지표 (gold set 표본 대비)
 *   autoConfirmErrorRate      게이트 검증
 *   needsReviewRate           비용 지표 — 품질 아님
 * </pre>
 *
 * ⚠ **needsReviewRate 를 품질 목표로 삼으면 안 된다.** 줄어드는 이유가 둘이다 — 정말 정확해졌거나,
 * 모델이 과신하거나. 목표로 걸면 임계값을 낮춰 숫자를 맞추려는 유인이 생기고 "확신 없으면 비워
 * 둘 것" 원칙과 정면으로 충돌한다. 그래서 응답에 **분류를 함께 실어 보낸다** — 값만 내려주면
 * 화면이 넷을 나란히 놓고 전부 품질 지표로 읽는다.
 */
public interface GetQualityMetricsUseCase {

    QualityMetrics getMetrics(long companyId);

    /*
     * @param precision            잴 수 없으면 **null 이다.** 0.0 은 "다 틀렸다"이고 null 은
     *                             "표본이 없어 못 잰다"라서, 뭉치면 정답지를 안 만든 상태가
     *                             모델이 완전히 실패한 것으로 보인다
     * @param needsReviewRate      **비용 지표다.** 품질 목표로 삼으면 안 된다
     */
    record QualityMetrics(
            GoldSetSummary goldSet,
            Double precision,
            Double recall,
            Double autoConfirmErrorRate,
            Double needsReviewRate,
            String promptVersion,
            String model
    ) {
    }

    /*
     * @param actionCount 표본 안의 정답 액션 수. **지표의 신뢰 구간이다** — 5건으로 잰
     *                    precision 0.8 과 100건으로 잰 0.8 은 같은 값이 아니다
     */
    record GoldSetSummary(int meetingCount, int actionCount) {
    }
}
