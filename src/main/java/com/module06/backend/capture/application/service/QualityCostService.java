package com.module06.backend.capture.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.List;
import java.util.OptionalLong;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.port.out.LayerCostRepository;
import com.module06.backend.capture.application.port.out.LayerCostRepository.LayerCost;
import com.module06.backend.capture.application.port.out.TokenPricingPort;
import com.module06.backend.capture.application.usecase.GetQualityCostUseCase;

/*
 * QLTY-03 · 비용 조회.
 *
 * <h2>0 으로 채우지 않는다</h2>
 * 요금제가 없으면 costKrw 가 **null 이다.** 0 을 주면 "공짜"로 읽히고, 그 값으로 특화 모델
 * 전환의 손익분기점을 계산하면 전환이 언제나 이득으로 나온다 — 이 API 를 만든 목적이 정확히
 * 그 판단이라 방향이 통째로 뒤집힌다.
 *
 * <h2>월 경계는 KST 다</h2>
 * 미터링(TokenMeteringService·MeteringDashboardService)이 같은 Clock 으로 같은 경계를 쓴다.
 * 둘이 갈리면 같은 달의 비용을 두 화면이 다르게 말하고, 어느 쪽이 맞는지 확인할 방법이 없다.
 */
@Service
@RequiredArgsConstructor
public class QualityCostService implements GetQualityCostUseCase {

    private final LayerCostRepository layerCostRepository;
    private final TokenPricingPort tokenPricingPort;

    /*
     * ⚠ 프로젝트 전체에 Clock 빈이 하나뿐이라(MeetingTimeConfiguration#meetingClock, KST)
     * 타입으로 주입된다. 캡처 전용 Clock 빈을 새로 만들면 안 된다.
     */
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public QualityCost getCost(long companyId, YearMonth period) {
        YearMonth target = period != null ? period : YearMonth.now(clock);
        LocalDateTime start = target.atDay(1).atStartOfDay();
        LocalDateTime end = target.plusMonths(1).atDay(1).atStartOfDay();

        List<LayerCost> costs = layerCostRepository.costsOf(companyId, start, end);
        int meetingCount = layerCostRepository.analyzedMeetingCount(companyId, start, end);

        List<LayerCostView> byLayer = costs.stream()
                .map(cost -> new LayerCostView(
                        cost.layer(), cost.calls(), cost.tokensIn(), cost.tokensOut(),
                        krwOrNull(companyId, cost.tokensIn() + cost.tokensOut())))
                .toList();

        long totalTokens = costs.stream()
                .mapToLong(cost -> cost.tokensIn() + cost.tokensOut())
                .sum();

        return new QualityCost(
                target,
                meetingCount,
                byLayer,
                /*
                 * STT 비용은 낼 수 없다. stt_block 에 요금도 단가도 없고, 여기서 지어내면
                 * **회의당 비용이 통째로 틀린 값이 된다** — 받아쓰기가 이 파이프라인에서
                 * 결코 작은 비용이 아니기 때문이다. 0 으로 채우면 "STT 는 공짜"로 읽힌다.
                 */
                null,
                avgPerMeetingKrw(companyId, totalTokens, meetingCount));
    }

    /*
     * 회의 1건당 비용. **계층별 금액을 더하지 않고 총 토큰으로 한 번에 환산한다** —
     * 1k 단위 올림이 계층마다 붙으면 계층 수만큼 반올림 오차가 쌓여, 합계가 실제 청구액과
     * 어긋난다.
     */
    private Long avgPerMeetingKrw(long companyId, long totalTokens, int meetingCount) {
        if (meetingCount <= 0) {
            // 분석이 한 건도 안 돈 달이다. 0 으로 나눌 수 없고, 0 원이라고 답할 수도 없다.
            return null;
        }
        Long total = krwOrNull(companyId, totalTokens);
        return total != null ? total / meetingCount : null;
    }

    private Long krwOrNull(long companyId, long tokens) {
        OptionalLong krw = tokenPricingPort.krwOf(companyId, tokens);
        return krw.isPresent() ? krw.getAsLong() : null;
    }
}
