package com.module06.backend.capture.application.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.module06.backend.capture.application.port.out.QualityGoldSetRepository;
import com.module06.backend.capture.application.port.out.QualityGoldSetRepository.FrozenLabels;
import com.module06.backend.capture.application.port.out.QualityMetricsRepository;
import com.module06.backend.capture.application.port.out.QualityMetricsRepository.GateTally;
import com.module06.backend.capture.application.usecase.GetQualityMetricsUseCase;

/*
 * QLTY-02 · 품질 지표 산출.
 *
 * <h2>채점은 동결된 라벨로 한다 — 현재 상태가 아니다</h2>
 * 표본 회의만 고정하고 채점을 현재 review_log 로 하면, **동결 뒤에 판정을 바꾸거나 액션을 직접
 * 추가하는 순간 과거 표본의 precision 이 달라진다.** "지난주 0.82"를 재현할 수 없게 되는데 그게
 * 정확히 gold set 을 만든 이유다(CodeRabbit PR #244 지적).
 *
 * <h2>게이트 지표만 현재 값을 본다 — 일부러다</h2>
 * precision·recall 은 **사람의 정답**이라 동결 대상이지만, autoConfirmErrorRate·needsReviewRate 는
 * **AI 출력의 지금 상태**를 재는 값이다. 게이트를 조인 뒤 나아졌는지 보려면 현재 tuple 을 봐야
 * 하고, 동결하면 그 변화가 영원히 안 보인다. 축이 다르므로 출처도 다르다.
 *
 * <h2>잴 수 없으면 null 이다 — 0 이 아니다</h2>
 * 분모가 0 인데 0.0 을 돌려주면 "다 틀렸다"로 읽힌다. 정답지를 아직 안 만든 상태와 모델이
 * 완전히 실패한 상태가 같은 화면이 되는데, 그 둘은 해야 할 일이 정반대다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityMetricsService implements GetQualityMetricsUseCase {

    private final QualityGoldSetRepository qualityGoldSetRepository;
    private final QualityMetricsRepository qualityMetricsRepository;
    private final ObjectMapper objectMapper;

    @Override
    @Transactional(readOnly = true)
    public QualityMetrics getMetrics(long companyId) {
        List<FrozenLabels> frozen = qualityGoldSetRepository.latestLabelsOf(companyId);
        LabelTally labels = tallyOf(frozen);

        GateTally gate = qualityMetricsRepository.gateTally(
                companyId, frozen.stream().map(FrozenLabels::meetingId).toList());

        return new QualityMetrics(
                new GoldSetSummary(frozen.size(), labels.total()),
                precisionOf(labels),
                recallOf(labels),
                ratio(gate.autoConfirmedWrong(), gate.autoConfirmedCount()),
                ratio(gate.tupleCount() - gate.autoConfirmedCount(), gate.tupleCount()),
                gate.promptVersion(),
                gate.model());
    }

    /*
     * 동결 라벨을 센다.
     *
     * 라벨 하나가 깨져도 **표본 전체를 버리지 않는다** — 그 회의만 빼고 나머지로 잰다. 지표가
     * 하나도 안 나오는 것보다 한 회의가 빠진 지표가 낫고, 빠졌다는 사실은 로그로 남는다.
     */
    private LabelTally tallyOf(List<FrozenLabels> frozen) {
        LabelTally tally = new LabelTally();
        for (FrozenLabels labels : frozen) {
            JsonNode parsed;
            try {
                parsed = objectMapper.readTree(labels.labeledActions());
            } catch (JacksonException e) {
                log.warn("동결 라벨을 읽지 못해 이 회의를 표본에서 뺀다. meetingId={} version={}",
                        labels.meetingId(), labels.version(), e);
                continue;
            }
            if (parsed == null || !parsed.isArray()) {
                log.warn("동결 라벨이 배열이 아니라 이 회의를 표본에서 뺀다. meetingId={} version={}",
                        labels.meetingId(), labels.version());
                continue;
            }

            for (JsonNode entry : parsed) {
                if (entry.path("manual").asBoolean(false)) {
                    // 사람이 직접 넣은 액션이다. AI 가 만든 것이 아니라 **놓친 것**이라 FN 이다.
                    tally.manualAdded++;
                } else if (entry.path("rejected").asBoolean(false)) {
                    tally.aiRejected++;
                } else {
                    tally.aiValid++;
                }
            }
        }
        return tally;
    }

    /*
     * AI 가 만든 것 중 실제로 액션이었던 비율.
     *
     * 반려되지 않은 것을 성공으로 센다 — 담당자를 고쳤어도 **"그 일이 있다"는 판정은 맞았다.**
     * 필드 정확도는 다른 축이고, 섞으면 "액션을 지어냈다(hallucination)"와 "담당자를 잘못
     * 짚었다"가 한 숫자로 뭉쳐 무엇을 고쳐야 할지 가리키지 못한다.
     */
    private Double precisionOf(LabelTally tally) {
        return ratio(tally.aiValid, tally.aiValid + tally.aiRejected);
    }

    /*
     * 실제 액션 중 AI 가 잡아낸 비율.
     *
     * 놓친 것(FN)은 **사람이 직접 추가한 액션**이다(RVW-03). 회의에 분명히 있었는데 AI 가 안
     * 만들어서 사람이 손으로 넣은 것이라, 그게 곧 "명확한데 못 잡은 것"의 정의다.
     *
     * ⚠ 회의에서 애매하게 말해 아무도 액션으로 만들지 않은 일은 여기 안 잡힌다. 그건 측정할
     * 방법이 없고(아무 기록도 남지 않는다) 시스템 실패도 아니다.
     */
    private Double recallOf(LabelTally tally) {
        return ratio(tally.aiValid, tally.aiValid + tally.manualAdded);
    }

    /* 분모가 0 이면 null 이다 — "다 틀렸다"(0.0)와 "못 잰다"를 구분한다. */
    private Double ratio(int numerator, int denominator) {
        if (denominator <= 0) {
            return null;
        }
        return (double) numerator / denominator;
    }

    private static final class LabelTally {
        private int aiValid;
        private int aiRejected;
        private int manualAdded;

        private int total() {
            return aiValid + aiRejected + manualAdded;
        }
    }
}
