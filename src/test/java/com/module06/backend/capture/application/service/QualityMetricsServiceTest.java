package com.module06.backend.capture.application.service;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.module06.backend.capture.application.port.out.QualityGoldSetRepository;
import com.module06.backend.capture.application.port.out.QualityMetricsRepository;
import com.module06.backend.capture.application.port.out.QualityMetricsRepository.GateTally;
import com.module06.backend.capture.application.usecase.GetQualityMetricsUseCase.QualityMetrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * QLTY-02 · 품질 지표.
 *
 * <p>검증의 축이 둘이다.
 *
 * <p><b>① 동결된 라벨로 채점하는가.</b> 표본 회의만 고정하고 채점을 현재 상태로 하면, 동결 뒤에
 * 판정을 바꾸거나 액션을 추가하는 순간 과거 표본의 precision 이 달라진다 — "지난주 0.82"를
 * 재현할 수 없게 되고 그게 정확히 gold set 을 만든 이유다.
 *
 * <p><b>② 잴 수 없는 것을 0 으로 답하지 않는가.</b> 0.0 은 "다 틀렸다"이고 null 은 "표본이 없다"인데,
 * 뭉치면 정답지를 안 만든 상태가 모델이 완전히 실패한 것으로 보인다.
 */
class QualityMetricsServiceTest {

    private static final long COMPANY = 7L;

    @Test
    @DisplayName("동결된 라벨로 채점한다 — 반려되지 않은 것이 성공이다")
    void 동결_라벨로_채점한다() {
        // AI 가 만든 10건 중 2건이 반려됐다.
        QualityMetrics metrics = service(labels(8, 2, 0), gate(0, 0, 0)).getMetrics(COMPANY);

        assertThat(metrics.precision()).isCloseTo(0.8, within(0.001));
    }

    @Test
    @DisplayName("사람이 직접 추가한 액션이 recall 의 놓친 것이다 — 명확한데 AI 가 못 잡은 것")
    void 직접_추가가_놓친_것이다() {
        QualityMetrics metrics = service(labels(8, 0, 2), gate(0, 0, 0)).getMetrics(COMPANY);

        assertThat(metrics.recall()).isCloseTo(0.8, within(0.001));
        // 직접 추가는 AI 가 만든 것이 아니라 precision 의 분모에 들어가면 안 된다.
        assertThat(metrics.precision()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("동결 뒤 현재 상태가 바뀌어도 precision 은 그대로다 — 그게 동결의 목적이다")
    void 현재_상태가_바뀌어도_지표는_그대로다() {
        /*
         * 게이트 쪽(현재 tuple)에 액션이 잔뜩 늘었지만 동결 라벨은 그대로다.
         * precision·recall 이 흔들리면 "지난주 0.82"를 재현할 수 없다.
         */
        QualityMetrics metrics = service(labels(8, 2, 0), gate(999, 500, 250)).getMetrics(COMPANY);

        assertThat(metrics.precision()).isCloseTo(0.8, within(0.001));
        assertThat(metrics.goldSet().actionCount()).isEqualTo(10);
    }

    @Test
    @DisplayName("게이트 지표는 현재 값을 본다 — 조인 뒤 나아졌는지 보려면 동결하면 안 된다")
    void 게이트_지표는_현재_값이다() {
        QualityMetrics metrics = service(labels(1, 0, 0), gate(50, 20, 1)).getMetrics(COMPANY);

        assertThat(metrics.autoConfirmErrorRate()).isCloseTo(0.05, within(0.001));
        // tuple 50건 중 20건 자동 확정 → 30건을 사람이 봐야 한다.
        assertThat(metrics.needsReviewRate()).isCloseTo(0.6, within(0.001));
    }

    @Test
    @DisplayName("표본이 없으면 비율은 null 이다 — 0.0 이면 '다 틀렸다'로 읽힌다")
    void 표본이_없으면_null이다() {
        QualityMetrics metrics = service(List.of(), gate(0, 0, 0)).getMetrics(COMPANY);

        assertThat(metrics.precision()).isNull();
        assertThat(metrics.recall()).isNull();
        assertThat(metrics.autoConfirmErrorRate()).isNull();
        assertThat(metrics.needsReviewRate()).isNull();
        assertThat(metrics.goldSet().meetingCount()).isZero();
    }

    @Test
    @DisplayName("자동 확정이 없으면 게이트 오류율은 null 이다 — 0 은 '게이트가 완벽하다'로 읽힌다")
    void 자동확정이_없으면_null이다() {
        QualityMetrics metrics = service(labels(5, 0, 0), gate(50, 0, 0)).getMetrics(COMPANY);

        assertThat(metrics.autoConfirmErrorRate()).isNull();
        assertThat(metrics.needsReviewRate()).isCloseTo(1.0, within(0.001));
    }

    @Test
    @DisplayName("라벨이 깨진 회의만 표본에서 뺀다 — 지표가 하나도 안 나오는 것보다 낫다")
    void 깨진_라벨은_그_회의만_뺀다() {
        List<QualityGoldSetRepository.FrozenLabels> frozen = List.of(
                new QualityGoldSetRepository.FrozenLabels(500L, 1, labelsJson(4, 1, 0)),
                new QualityGoldSetRepository.FrozenLabels(501L, 1, "{not json"));

        QualityMetrics metrics = service(frozen, gate(0, 0, 0)).getMetrics(COMPANY);

        // 정상 회의 하나로 잰다. meetingCount 는 동결된 표본 수 그대로다.
        assertThat(metrics.precision()).isCloseTo(0.8, within(0.001));
        assertThat(metrics.goldSet().actionCount()).isEqualTo(5);
    }

    // ── 조립 ────────────────────────────────────────────────────────────────────

    private QualityMetricsService service(List<QualityGoldSetRepository.FrozenLabels> frozen,
                                          GateTally gate) {
        return new QualityMetricsService(
                new StubGoldSetRepository(frozen), (companyId, meetingIds) -> gate, new ObjectMapper());
    }

    private static List<QualityGoldSetRepository.FrozenLabels> labels(int aiValid, int aiRejected,
                                                                      int manualAdded) {
        return List.of(new QualityGoldSetRepository.FrozenLabels(
                500L, 1, labelsJson(aiValid, aiRejected, manualAdded)));
    }

    /* 동결 라벨의 실제 모양이다 — QLTY-01 이 만드는 JSON 과 같은 키를 쓴다. */
    private static String labelsJson(int aiValid, int aiRejected, int manualAdded) {
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < aiValid; i++) {
            json.append("{\"actionId\":").append(i).append(",\"rejected\":false,\"manual\":false},");
        }
        for (int i = 0; i < aiRejected; i++) {
            json.append("{\"actionId\":1").append(i).append(",\"rejected\":true,\"manual\":false},");
        }
        for (int i = 0; i < manualAdded; i++) {
            json.append("{\"actionId\":2").append(i).append(",\"rejected\":false,\"manual\":true},");
        }
        if (json.charAt(json.length() - 1) == ',') {
            json.deleteCharAt(json.length() - 1);
        }
        return json.append("]").toString();
    }

    private static GateTally gate(int tupleCount, int autoConfirmed, int autoConfirmedWrong) {
        return new GateTally(tupleCount, autoConfirmed, autoConfirmedWrong, "gemini-3.5-flash", "v3");
    }

    private record StubGoldSetRepository(List<FrozenLabels> frozen) implements QualityGoldSetRepository {

        @Override
        public List<FrozenLabels> latestLabelsOf(long companyId) {
            return frozen;
        }

        @Override
        public GoldSetView freeze(FreezeCommand command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int latestVersionOf(long meetingId) {
            throw new UnsupportedOperationException();
        }
    }
}
