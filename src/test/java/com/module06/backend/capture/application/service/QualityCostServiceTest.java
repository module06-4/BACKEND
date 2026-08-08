package com.module06.backend.capture.application.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.List;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.module06.backend.capture.application.port.out.LayerCostRepository;
import com.module06.backend.capture.application.port.out.LayerCostRepository.LayerCost;
import com.module06.backend.capture.application.port.out.TokenPricingPort;
import com.module06.backend.capture.application.usecase.GetQualityCostUseCase.QualityCost;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * QLTY-03 · 비용 조회.
 *
 * <p>검증의 축은 <b>없는 값을 0 으로 답하지 않는가</b>다. 이 API 는 특화 모델 전환의 손익분기점을
 * 계산하는 근거라, 요금제가 없는데 0 원이라고 답하면 <b>전환이 언제나 이득으로 나온다</b> —
 * 판단의 방향이 통째로 뒤집힌다.
 */
class QualityCostServiceTest {

    private static final long COMPANY = 7L;
    private static final YearMonth PERIOD = YearMonth.of(2026, 8);

    @Test
    @DisplayName("계층별 토큰을 원화로 바꿔 준다")
    void 계층별_비용을_낸다() {
        // 1k 토큰당 10원이라고 가정한 가짜 요금제.
        QualityCost cost = service(costs(new LayerCost("L4", 214, 812_400, 91_200)), 3, tokens -> tokens / 100)
                .getCost(COMPANY, PERIOD);

        assertThat(cost.byLayer()).hasSize(1);
        assertThat(cost.byLayer().get(0).layer()).isEqualTo("L4");
        assertThat(cost.byLayer().get(0).costKrw()).isEqualTo((812_400 + 91_200) / 100);
    }

    @Test
    @DisplayName("요금제가 없으면 금액이 null 이다 — 0 이면 공짜로 읽혀 전환이 언제나 이득이 된다")
    void 요금제가_없으면_null이다() {
        QualityCost cost = service(costs(new LayerCost("L4", 10, 1000, 500)), 2, null)
                .getCost(COMPANY, PERIOD);

        assertThat(cost.byLayer().get(0).costKrw()).isNull();
        assertThat(cost.avgPerMeetingKrw()).isNull();
        // 토큰 자체는 낼 수 있다 — 못 내는 것은 환산뿐이다.
        assertThat(cost.byLayer().get(0).tokensIn()).isEqualTo(1000);
    }

    @Test
    @DisplayName("회의당 비용은 총 토큰으로 한 번에 환산한다 — 계층마다 반올림하면 오차가 쌓인다")
    void 회의당_비용은_총_토큰으로_환산한다() {
        QualityCost cost = service(
                costs(new LayerCost("L2", 5, 100, 50), new LayerCost("L4", 5, 200, 50)),
                2, tokens -> tokens)
                .getCost(COMPANY, PERIOD);

        // (100+50+200+50) = 400 토큰 → 400원 → 회의 2건 → 200원
        assertThat(cost.avgPerMeetingKrw()).isEqualTo(200L);
    }

    @Test
    @DisplayName("분석이 한 건도 안 돈 달은 회의당 비용이 null 이다 — 0 원이라고 답할 수 없다")
    void 회의가_없으면_null이다() {
        QualityCost cost = service(costs(), 0, tokens -> tokens).getCost(COMPANY, PERIOD);

        assertThat(cost.meetingCount()).isZero();
        assertThat(cost.avgPerMeetingKrw()).isNull();
        assertThat(cost.byLayer()).isEmpty();
    }

    @Test
    @DisplayName("STT 비용은 항상 null 이다 — 0 이면 'STT 는 공짜'로 읽힌다")
    void STT_비용은_null이다() {
        QualityCost cost = service(costs(new LayerCost("L4", 1, 10, 10)), 1, tokens -> tokens)
                .getCost(COMPANY, PERIOD);

        assertThat(cost.sttCostKrw()).isNull();
    }

    @Test
    @DisplayName("기간을 안 주면 이번 달이다")
    void 기간을_안_주면_이번_달이다() {
        RecordingCostRepository repository = costs();

        QualityCost cost = service(repository, 0, tokens -> tokens).getCost(COMPANY, null);

        assertThat(cost.period()).isEqualTo(YearMonth.of(2026, 8));
        // 월 경계는 [1일 00:00, 다음달 1일 00:00) 다 — 미터링과 같은 규칙이어야 한다.
        assertThat(repository.start).isEqualTo(LocalDateTime.of(2026, 8, 1, 0, 0));
        assertThat(repository.end).isEqualTo(LocalDateTime.of(2026, 9, 1, 0, 0));
    }

    // ── 조립 ────────────────────────────────────────────────────────────────────

    private QualityCostService service(RecordingCostRepository repository, int meetingCount,
                                       java.util.function.LongUnaryOperator pricing) {
        repository.meetingCount = meetingCount;
        TokenPricingPort port = pricing == null
                ? (companyId, tokens) -> OptionalLong.empty()
                : (companyId, tokens) -> OptionalLong.of(pricing.applyAsLong(tokens));

        return new QualityCostService(repository, port,
                Clock.fixed(LocalDateTime.of(2026, 8, 9, 3, 0).atZone(ZoneId.of("Asia/Seoul")).toInstant(),
                        ZoneId.of("Asia/Seoul")));
    }

    private static RecordingCostRepository costs(LayerCost... layers) {
        return new RecordingCostRepository(List.of(layers));
    }

    private static final class RecordingCostRepository implements LayerCostRepository {

        private final List<LayerCost> layers;
        private int meetingCount;
        private LocalDateTime start;
        private LocalDateTime end;

        private RecordingCostRepository(List<LayerCost> layers) {
            this.layers = layers;
        }

        @Override
        public List<LayerCost> costsOf(long companyId, LocalDateTime startInclusive, LocalDateTime endExclusive) {
            this.start = startInclusive;
            this.end = endExclusive;
            return layers;
        }

        @Override
        public int analyzedMeetingCount(long companyId, LocalDateTime startInclusive,
                                        LocalDateTime endExclusive) {
            return meetingCount;
        }
    }
}
