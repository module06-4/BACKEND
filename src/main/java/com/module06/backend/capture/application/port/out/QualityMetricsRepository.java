package com.module06.backend.capture.application.port.out;

import java.util.List;

/*
 * QLTY-02 의 **게이트 지표**만 읽는다.
 *
 * <h2>왜 precision·recall 은 여기 없나</h2>
 * 그 둘은 **동결된 gold set 라벨**로 채점한다. 현재 review_log 로 세면 동결 뒤에 판정을 바꾸거나
 * 액션을 직접 추가하는 순간 과거 표본의 수치가 달라져, "지난주 0.82"를 재현할 수 없게 된다 —
 * gold set 을 만든 이유가 통째로 무너진다(CodeRabbit PR #244).
 *
 * <h2>게이트 지표는 반대로 현재 값을 본다 — 일부러다</h2>
 * autoConfirmErrorRate·needsReviewRate 는 **AI 출력의 지금 상태**를 재는 값이다. 게이트를 조인
 * 뒤 나아졌는지 보려면 현재 tuple 을 봐야 하고, 동결하면 그 변화가 영원히 안 보인다.
 * 축이 다르므로 출처도 다르다.
 */
public interface QualityMetricsRepository {

    /*
     * 표본 회의들의 게이트 성적. 표본이 비어 있으면 전부 0 이다.
     *
     * @param meetingIds gold set 이 정한 표본. 비어 있으면 조회하지 않는다
     */
    GateTally gateTally(long companyId, List<Long> meetingIds);

    /*
     * @param tupleCount          표본 안의 전체 tuple 수. needsReviewRate 의 분모다
     * @param autoConfirmedCount  게이트가 자동 확정한 수
     * @param autoConfirmedWrong  그중 사람이 고치거나 반려한 수. 게이트가 틀린 것이다
     * @param model               채점 대상이 어느 모델의 출력인가. 없으면 null
     * @param promptVersion       같은 이유. 버전이 섞이면 지표를 비교할 수 없다
     */
    record GateTally(
            int tupleCount,
            int autoConfirmedCount,
            int autoConfirmedWrong,
            String model,
            String promptVersion
    ) {

        public static GateTally empty() {
            return new GateTally(0, 0, 0, null, null);
        }
    }
}
