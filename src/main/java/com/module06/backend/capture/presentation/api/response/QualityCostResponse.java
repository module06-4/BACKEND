package com.module06.backend.capture.presentation.api.response;

import java.util.List;

import com.module06.backend.capture.application.usecase.GetQualityCostUseCase.QualityCost;

/*
 * QLTY-03 응답이다.
 *
 * <h2>금액이 null 일 수 있다</h2>
 * 요금제(company_token_plan)가 없으면 토큰을 원화로 바꿀 수 없다. **0 으로 채우지 않는다** —
 * "공짜"로 읽히고, 그 값으로 특화 모델 전환의 손익분기점을 계산하면 전환이 언제나 이득으로
 * 나온다. 이 API 를 만든 목적이 정확히 그 판단이다.
 *
 * <h2>sttCostKrw 는 지금 언제나 null 이다</h2>
 * stt_block 에 요금도 단가도 없어 낼 수 있는 값이 아니다. 0 으로 채우면 "STT 는 공짜"로 읽히는데,
 * 받아쓰기는 이 파이프라인에서 작은 비용이 아니다.
 */
public record QualityCostResponse(
        String period,
        int meetingCount,
        List<LayerCostResponse> byLayer,
        Long sttCostKrw,
        Long avgPerMeetingKrw
) {

    public static QualityCostResponse from(QualityCost cost) {
        return new QualityCostResponse(
                cost.period().toString(),
                cost.meetingCount(),
                cost.byLayer().stream()
                        .map(layer -> new LayerCostResponse(
                                layer.layer(), layer.calls(),
                                layer.tokensIn(), layer.tokensOut(), layer.costKrw()))
                        .toList(),
                cost.sttCostKrw(),
                cost.avgPerMeetingKrw());
    }

    /*
     * @param calls **모델 호출 수가 아니라 계층 실행 수다.** L3 는 주제마다 부르지만
     *              analysis_layer 는 회의·계층당 한 행에 토큰을 누적해서 주제별 호출 수가
     *              남지 않는다. 토큰은 정확하고 이 값만 그 한계를 갖는다
     */
    public record LayerCostResponse(String layer, int calls, long tokensIn, long tokensOut, Long costKrw) {
    }
}
