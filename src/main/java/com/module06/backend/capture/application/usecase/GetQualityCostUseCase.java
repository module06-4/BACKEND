package com.module06.backend.capture.application.usecase;

import java.time.YearMonth;
import java.util.List;

/*
 * QLTY-03 · 비용 조회.
 *
 * **기준선이 없으면 특화 모델 전환의 절감 효과를 증명할 수 없다.** 상시 서빙 인스턴스 값이
 * 회의당 호출 비용보다 비싼 구간이 넓어서, 재봤더니 전환이 무의미한 결과도 가능하다 —
 * 그 판단을 하려면 지금 얼마인지가 먼저 있어야 한다.
 *
 * 발표에서 "회의 1건에 얼마"를 말할 수 있는 유일한 근거이기도 하다.
 */
public interface GetQualityCostUseCase {

    QualityCost getCost(long companyId, YearMonth period);

    /*
     * @param sttCostKrw        **지금은 언제나 null 이다.** stt_block 에 요금도 단가도 없어
     *                          낼 수 있는 값이 아니다 — 0 으로 채우면 "STT 는 공짜"로 읽힌다
     * @param avgPerMeetingKrw  회의 1건당 비용. 요금제가 없거나 회의가 0건이면 null 이다
     */
    record QualityCost(
            YearMonth period,
            int meetingCount,
            List<LayerCostView> byLayer,
            Long sttCostKrw,
            Long avgPerMeetingKrw
    ) {
    }

    /* @param costKrw 요금제가 없으면 null 이다 — 0 을 주면 공짜로 읽힌다 */
    record LayerCostView(String layer, int calls, long tokensIn, long tokensOut, Long costKrw) {
    }
}
