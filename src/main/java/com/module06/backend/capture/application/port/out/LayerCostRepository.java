package com.module06.backend.capture.application.port.out;

import java.time.LocalDateTime;
import java.util.List;

/*
 * QLTY-03 비용의 원재료를 읽는다 — 계층별 토큰이다.
 *
 * <h2>회사 스코프를 회의로 되짚는다</h2>
 * analysis_layer 에는 company_id 가 없다(V5.6). 그래서 meeting 을 조인해 회사를 가른다 —
 * 이 표에 컬럼을 더하는 대신 조인을 택한 이유는 회의당 행이 최대 10개라 조인이 작고,
 * 마이그레이션과 백필 없이 끝나기 때문이다.
 *
 * <h2>미터링 원장으로는 대체할 수 없다</h2>
 * token_usage_record 에는 company_id 가 있지만 **실행 단위 합계라 계층별 분해가 없다.**
 * QLTY-03 의 본체가 byLayer 이므로 그쪽으로는 이 화면을 만들 수 없다.
 */
public interface LayerCostRepository {

    /* 기간 안의 계층별 토큰. 기간은 [start, end) 다. */
    List<LayerCost> costsOf(long companyId, LocalDateTime startInclusive, LocalDateTime endExclusive);

    /* 기간 안에 분석이 돈 회의 수. avgPerMeetingKrw 의 분모다. */
    int analyzedMeetingCount(long companyId, LocalDateTime startInclusive, LocalDateTime endExclusive);

    /*
     * @param calls **모델 호출 수가 아니라 계층 실행 수다**(attempt_count 합). L3 는 주제마다
     *              부르지만 analysis_layer 는 회의·계층당 한 행에 토큰을 누적하므로, 주제별
     *              호출 수는 이 표에 남지 않는다. 그 차이를 모르고 읽으면 호출당 단가가
     *              실제보다 비싸 보인다
     */
    record LayerCost(String layer, int calls, long tokensIn, long tokensOut) {
    }
}
