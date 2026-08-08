package com.module06.backend.capture.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

import com.module06.backend.action.application.port.ActionDistributionPort;
import com.module06.backend.action.application.port.ActionDistributionPort.ActionDistributionItem;
import com.module06.backend.action.application.port.ActionDistributionPort.DistributeActionsCommand;
import com.module06.backend.action.application.port.ActionDistributionPort.DistributedAction;
import com.module06.backend.action.domain.model.ActionType;
import com.module06.backend.capture.application.port.out.AiLayerPort;
import com.module06.backend.capture.application.port.out.AnalysisLayerRepository;
import com.module06.backend.capture.application.port.out.AnalysisRunRepository;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.StoredTuple;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleConflicts;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleDistribution;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleGateVerdict;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleVerification;
import com.module06.backend.capture.application.port.out.CaptionRepository;
import com.module06.backend.capture.application.port.out.ExtractTuplesResult;
import com.module06.backend.capture.application.port.out.GateResult;
import com.module06.backend.capture.application.port.out.LayerRun;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository;
import com.module06.backend.capture.application.port.out.ResolveReferenceResult;
import com.module06.backend.capture.application.port.out.SegmentTopicsResult;
import com.module06.backend.capture.application.port.out.SummarizeTopicResult;
import com.module06.backend.capture.application.port.out.TranscriptRepository;
import com.module06.backend.capture.application.port.out.VerifyTupleResult;
import com.module06.backend.capture.application.result.AnalysisOutcome;
import com.module06.backend.capture.domain.model.AssigneeSource;
import com.module06.backend.capture.domain.model.AssignmentTuple;
import com.module06.backend.capture.domain.model.CaptionChunk;
import com.module06.backend.capture.domain.model.ConflictType;
import com.module06.backend.capture.domain.model.GateStatus;
import com.module06.backend.capture.domain.model.GateVerdict;
import com.module06.backend.capture.domain.model.ItemType;
import com.module06.backend.capture.domain.model.LayerName;
import com.module06.backend.capture.domain.model.LayerStatus;
import com.module06.backend.capture.domain.model.ReferenceType;
import com.module06.backend.capture.domain.model.ResolvedReference;
import com.module06.backend.capture.domain.model.TopicSegment;
import com.module06.backend.capture.domain.model.Utterance;
import com.module06.backend.capture.domain.model.VerifyVerdict;
import com.module06.backend.metering.application.command.RecordTokenUsageCommand;
import com.module06.backend.metering.application.port.in.RecordTokenUsagePort;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 파이프라인 오케스트레이션 — 계층 순서 · 게이트 관문 · 산출물 저장.
 *
 * <p>실제 HTTP 없이 돈다. 계층 순서·잠금·상태 전이는 이 저장소의 로직이고, 그걸 검증하는 데
 * Gemini 크레딧이나 네트워크가 필요하면 아무도 테스트를 돌리지 않게 된다(AiLayerPort 주석).
 *
 * <p>가장 중요한 검증은 <b>게이트 관문</b>이다. L3.5 가 CONFIRMED 로 판정하지 않은 항목이
 * L4 로 넘어가면 아직 합의도 안 된 논의가 담당자에게 배정된다.
 */
class AnalysisOrchestratorTest {

    private static final long TENANT = 7L;
    private static final long COMPANY = 7L;
    private static final long MEETING = 500L;
    private static final long PROJECT = 31L;
    private static final long TEAM = 3L;
    private static final LocalDate MEETING_DATE = LocalDate.of(2026, 8, 5);

    private static final List<AiLayerPort.Participant> PARTICIPANTS = List.of(
            new AiLayerPort.Participant(42L, "김서준"),
            new AiLayerPort.Participant(null, "명단 외"));

    @Test
    @DisplayName("CONFIRMED 항목만 L4 로 넘어간다 — DISCUSSED · 미판정은 제외된다")
    void 게이트를_통과한_항목만_tuple_추출로_넘어간다() {
        // 항목 3건: 하나는 CONFIRMED, 하나는 DISCUSSED, 하나는 게이트가 판정을 못 준 것.
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L),
                        item(ItemType.DISCUSSION, "가격 논의", 2L),
                        item(ItemType.BLOCKER, "인력 부족", 3L)),
                decisionIds -> List.of(
                        new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨"),
                        new GateVerdict(decisionIds.get(1), GateStatus.DISCUSSED, "결론 없음")));
        FakeTupleRepository tuples = new FakeTupleRepository();

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);

        // L4 요청에 실린 항목은 CONFIRMED 하나뿐이어야 한다.
        assertThat(ai.extractRequests).hasSize(1);
        assertThat(ai.extractRequests.get(0)).hasSize(1);
        assertThat(ai.extractRequests.get(0).get(0).content()).isEqualTo("로드맵 확정");
        // gateStatus 를 값으로 실어 보낸다 — 여기가 CONFIRMED 가 아니면 Python 이 422 로 거절한다.
        assertThat(ai.extractRequests.get(0).get(0).gateStatus()).isEqualTo(GateStatus.CONFIRMED);
    }

    @Test
    @DisplayName("확정 항목이 없는 회의는 L4 를 부르지 않는다 — 빈 호출에 토큰을 쓰지 않는다")
    void 확정된_항목이_없으면_L4를_부르지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DISCUSSION, "가격 논의", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.DISCUSSED, "결론 없음")));
        FakeTupleRepository tuples = new FakeTupleRepository();

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(ai.extractRequests).isEmpty();
        // 그래도 저장은 불렀다 — 재실행에서 이전 tuple 을 지워야 한다.
        assertThat(tuples.replaceCalls).isEqualTo(1);
        assertThat(tuples.saved).isEmpty();
    }

    @Test
    @DisplayName("근거 발화가 없는 항목은 게이트에 넣지 않는다 — 확인할 수 없는 항목은 확정되지 않는다")
    void 근거가_없는_항목은_게이트_대상이_아니다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                // evidence 가 null 인 항목 — 자막 폴백·사람이 직접 추가한 항목의 모양이다.
                List.of(item(ItemType.DECISION, "근거 없는 결정", null)),
                decisionIds -> List.of());
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 판정 대상이 없으므로 게이트 호출 자체가 없다.
        assertThat(ai.gateRequests).isEmpty();
        assertThat(ai.extractRequests).isEmpty();
    }

    @Test
    @DisplayName("후보로 보내지 않은 항목의 게이트 판정은 버린다 — 게이트를 통한 게이트 우회를 막는다")
    void 후보_밖_판정은_반영하지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        // 항목 둘: 하나는 근거가 있어 후보가 되고, 하나는 근거가 없어 **일부러 제외된다**.
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "근거 있는 결정", 1L),
                        item(ItemType.DECISION, "근거 없는 결정", null)),
                // 계층이 후보가 아닌 항목(근거 없는 결정)에 CONFIRMED 를 돌려준다.
                // 그대로 저장되면 근거를 확인할 수 없는 항목이 L4 로 넘어간다.
                decisionIds -> List.of(
                        new GateVerdict(999_999L, GateStatus.CONFIRMED, "요청에 없던 항목")));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 후보는 하나였는데 그 하나는 판정을 못 받았고, 온 판정은 후보 밖이라 버려진다.
        // 결과적으로 확정된 항목이 없으므로 L4 를 부르지 않는다.
        assertThat(ai.gateRequests).hasSize(1);
        assertThat(ai.extractRequests).isEmpty();
        assertThat(tuples.saved).isEmpty();
    }

    @Test
    @DisplayName("후보 밖 판정에 실제 항목 id 가 실려도 버린다")
    void 후보_밖_판정이_실존_항목을_가리켜도_버린다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "근거 있는 결정", 1L),
                        item(ItemType.DECISION, "근거 없는 결정", null)),
                // 후보(첫 항목) 대신 **같은 회의의 제외된 항목** id 로 CONFIRMED 를 돌려준다.
                // meetingId 스코프만 보는 검사로는 통과하는 모양이다 — 그게 이 테스트의 요점이다.
                decisionIds -> List.of(new GateVerdict(
                        summaries.idOfContent("근거 없는 결정"), GateStatus.CONFIRMED, "제외된 항목")));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(ai.extractRequests).isEmpty();
        assertThat(tuples.saved).isEmpty();
    }

    @Test
    @DisplayName("tuple 을 근거 발화로 확정 항목에 되짚어 저장한다")
    void tuple을_근거_항목에_연결해_저장한다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 11L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(new AssignmentTuple(
                "로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, LocalDate.of(2026, 8, 12), 11L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(tuples.saved).hasSize(1);
        AssignmentTupleRepository.TupleRow row = tuples.saved.get(0);
        assertThat(row.tuple().title()).isEqualTo("로드맵 초안 작성");
        // 근거 발화 11 을 가진 확정 항목의 id 로 연결돼야 한다.
        assertThat(row.decisionId()).isEqualTo(summaries.idOfEvidence(11L));
        assertThat(row.topicSeq()).isEqualTo(1);
        assertThat(row.modelName()).isEqualTo("gemini-flash");
    }

    @Test
    @DisplayName("L1.5 해소 결과를 뒤 계층 발화에 주석으로 붙인다 — 원문은 고치지 않는다")
    void 지시어_해소_결과가_뒤_계층_발화에_반영된다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.references = List.of(
                new ResolvedReference(2L, "그거", ReferenceType.ARTIFACT, null, "로드맵 초안", 1L),
                new ResolvedReference(2L, "그분", ReferenceType.PERSON, 42L, null, 1L));

        orchestrator(summaries, new FakeTupleRepository(), ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // L1.5 는 원문을 본다.
        assertThat(ai.resolveUtterances).extracting(Utterance::text)
                .containsExactly("로드맵 정리합시다", "그거 그분한테 맡기죠");

        // L2 는 주석이 붙은 사본을 본다. 치환이 아니라 덧붙이기다 — 원문이 남아야 근거가 유지된다.
        assertThat(ai.segmentUtterances.get(1).text())
                .isEqualTo("그거 그분한테 맡기죠 [지시어 \"그거\" → 로드맵 초안] [지시어 \"그분\" → 김서준]");
        // 주석은 텍스트만 바꾼다. id 가 흔들리면 L2 가 돌려준 utteranceIds 와 어긋난다.
        assertThat(ai.segmentUtterances.get(1).utteranceId()).isEqualTo(2L);
    }

    @Test
    @DisplayName("UNRESOLVED 기권은 발화에 적지 않는다 — 모델이 그 문구를 내용으로 인용한다")
    void 기권한_지시어는_주석을_붙이지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.references = List.of(
                new ResolvedReference(2L, "그거", ReferenceType.UNRESOLVED, null, null, 1L),
                // PERSON 인데 명단 밖 — 이름을 적지 않아야 L4 가 담당자로 쓰지 않는다.
                new ResolvedReference(2L, "그분", ReferenceType.PERSON, null, "그 팀 분", 1L),
                // 원문에 없는 지시 표현 — 계약이 갈렸을 때 엉뚱한 자리에 붙는 것을 막는다.
                new ResolvedReference(2L, "저기", ReferenceType.ARTIFACT, null, "회의록", 1L));

        orchestrator(summaries, new FakeTupleRepository(), ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(ai.segmentUtterances.get(1).text()).isEqualTo("그거 그분한테 맡기죠");
    }

    @Test
    @DisplayName("계층이 실패하면 거기서 멈추고 뒤 계층을 부르지 않는다")
    void 계층_실패는_파이프라인을_멈춘다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.gateFailure = new AiLayerException("PROVIDER_UNAVAILABLE", "게이트 실패", true);
        FakeTupleRepository tuples = new FakeTupleRepository();
        FakeLayerRepository layers = new FakeLayerRepository();

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai, layers).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.FAILED);
        assertThat(outcome.failedLayer()).isEqualTo(LayerName.L3_5);
        assertThat(outcome.retryable()).isTrue();
        // L4 로 넘어가지 않는다. 넘어가면 게이트 없는 항목으로 tuple 을 뽑는다.
        assertThat(ai.extractRequests).isEmpty();
        assertThat(tuples.replaceCalls).isZero();
        assertThat(layers.failed).containsKey(LayerName.L3_5);
    }

    @Test
    @DisplayName("새 계층이 안 돈 회의는 '완료'로 생략되지 않는다")
    void RUN_LAYERS에_새_계층이_들어가면_재실행_대상이다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));

        // L2·L3 만 DONE 인 회의 — L1.5·L3.5·L4 가 붙기 전에 분석된 회의의 모양이다.
        FakeLayerRepository layers = new FakeLayerRepository();
        layers.done.add(LayerName.L2);
        layers.done.add(LayerName.L3);

        AnalysisOutcome outcome = orchestrator(summaries, new FakeTupleRepository(), ai, layers).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 생략되지 않고 다시 돈다. 생략되면 tuple 이 영원히 만들어지지 않는다.
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(ai.extractRequests).hasSize(1);
    }

    @Test
    @DisplayName("L1 이 화자를 이식하고, 뒤 계층은 이식된 화자를 본다")
    void L1이_이식한_화자가_뒤_계층에_보인다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));

        // 발화 2번(5~8초)은 화자가 null 이다. 참석자 전원(42)이 자막을 보내면 판정된다.
        FakeTranscriptRepository transcripts = new FakeTranscriptRepository(utterances());
        FakeCaptionRepository captions = new FakeCaptionRepository(
                new CaptionChunk(42L, 5_000, 8_000, new java.math.BigDecimal("-18.00")));

        AnalysisOutcome outcome = orchestratorOf(
                transcripts, captions, new FakeLayerRepository(), summaries,
                new FakeTupleRepository(), meetingId -> Optional.of(MEETING_DATE), ai)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        // 정본에 이식됐다.
        assertThat(transcripts.applied)
                .extracting(SpeakerAttributionResolver.Attribution::speakerMemberId)
                .containsOnly(42L);
        // 그리고 L2 가 보는 발화에 그 화자가 들어 있다 — 이식 후 다시 읽지 않으면 여기가 null 이다.
        assertThat(ai.segmentUtterances)
                .filteredOn(utterance -> utterance.utteranceId() == 2L)
                .extracting(Utterance::speakerMemberId)
                .containsExactly(42L);
    }

    @Test
    @DisplayName("자막이 없어도 분석은 끝까지 돈다 — CAP-11 미구현 상태의 정상 경로")
    void 자막이_없어도_파이프라인은_완주한다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        FakeTranscriptRepository transcripts = new FakeTranscriptRepository(utterances());

        AnalysisOutcome outcome = orchestratorOf(
                transcripts, new FakeCaptionRepository(), new FakeLayerRepository(), summaries,
                new FakeTupleRepository(), meetingId -> Optional.of(MEETING_DATE), ai)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 판정 0건이지만 실패가 아니다. 화자 미정은 정상 동작이고, 뒤 계층은 그대로 돈다.
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(transcripts.applied).isEmpty();
        assertThat(ai.extractRequests).hasSize(1);
    }

    @Test
    @DisplayName("재판정이 기권하면 예전 화자를 지운다 — 불확실해진 화자가 확정으로 굳으면 안 된다")
    void 기권한_발화의_예전_화자를_지운다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));

        // 발화 1 에는 예전 실행이 심어 둔 화자(42)가 남아 있다. 이번엔 자막이 없어 전원 기권이다.
        FakeTranscriptRepository transcripts = new FakeTranscriptRepository(utterances());

        AnalysisOutcome outcome = orchestratorOf(
                transcripts, new FakeCaptionRepository(), new FakeLayerRepository(), summaries,
                new FakeTupleRepository(), meetingId -> Optional.of(MEETING_DATE), ai)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        // 뒤 계층은 화자 없는 발화를 본다. 남아 있으면 L4 가 근거 없는 담당자를 확정한다.
        assertThat(ai.segmentUtterances).extracting(Utterance::speakerMemberId)
                .containsOnlyNulls();
    }

    @Test
    @DisplayName("완료 판정은 RUN_LAYERS 기준이다 — 예전 계층만 DONE 이면 완료가 아니다")
    void 완료_판정은_RUN_LAYERS를_본다() {
        FakeLayerRepository layers = new FakeLayerRepository();
        AnalysisOrchestrator orchestrator = orchestrator(
                new FakeSummaryRepository(), new FakeTupleRepository(),
                new RecordingAiLayerPort(List.of(), decisionIds -> List.of()), layers);

        // L2·L3 만 DONE — 계층 상태 행만 보면 "전부 DONE"이라 완료로 읽히는 모양이다.
        layers.done.add(LayerName.L2);
        layers.done.add(LayerName.L3);
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isFalse();

        layers.done.add(LayerName.L1_5);
        layers.done.add(LayerName.L3_5);
        layers.done.add(LayerName.L4);
        // L1 이 빠져 있다. 계층을 붙일 때 이 판정을 갱신하지 않으면 여기서 걸린다.
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isFalse();

        layers.done.add(LayerName.L1);
        // L5 가 빠져 있다. 계층을 붙일 때마다 이 자리가 조용히 재발한다.
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isFalse();

        layers.done.add(LayerName.L5);
        // L6·L7 이 빠져 있다. 계층을 붙일 때마다 이 자리가 조용히 재발한다.
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isFalse();

        layers.done.add(LayerName.L6);
        layers.done.add(LayerName.L7);
        // 분배(DIST)가 빠져 있다. **액션이 만들어지지 않은 회의는 완료가 아니다** —
        // 여기서 완료로 보면 검토 화면이 영영 빈 목록인 회의가 재실행 대상에서 빠진다.
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isFalse();

        layers.done.add(LayerName.DIST);
        assertThat(orchestrator.isFullyAnalyzed(MEETING)).isTrue();
    }

    @Test
    @DisplayName("발화가 없으면 계층을 하나도 부르지 않는다")
    void 발화가_없으면_생략한다() {
        RecordingAiLayerPort ai = new RecordingAiLayerPort(List.of(), decisionIds -> List.of());

        AnalysisOutcome outcome = orchestratorOf(
                new FakeTranscriptRepository(List.of()), new FakeCaptionRepository(),
                new FakeLayerRepository(), new FakeSummaryRepository(), new FakeTupleRepository(),
                meetingId -> Optional.of(MEETING_DATE), ai)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.SKIPPED);
        assertThat(ai.resolveCalls).isZero();
    }

    @Test
    @DisplayName("회의 날짜를 못 읽어도 분석은 돌고, 기준일 없이 L4 를 부른다")
    void 회의_날짜가_없어도_L4는_돈다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));

        AnalysisOutcome outcome = orchestratorOf(
                new FakeTranscriptRepository(utterances()), new FakeCaptionRepository(),
                new FakeLayerRepository(), summaries, new FakeTupleRepository(),
                meetingId -> Optional.empty(), ai)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        // null 을 넘긴다 — 오늘 날짜로 대체하면 그럴듯하게 틀린 마감이 보드에 꽂힌다.
        assertThat(ai.meetingDates).containsExactly((LocalDate) null);
    }

    // ── L5 · 관점 다변화 검증 ───────────────────────────────────────────────────

    @Test
    @DisplayName("L5 는 tuple 마다 한 번씩 돈다 — 주제 단위가 아니다")
    void L5는_tuple마다_한_번씩_돈다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L),
                        item(ItemType.DECISION, "일정 확정", 2L)),
                decisionIds -> decisionIds.stream()
                        .map(id -> new GateVerdict(id, GateStatus.CONFIRMED, "합의됨"))
                        .toList());
        // 한 주제에서 tuple 이 둘 나왔다. 주제 단위로 부르면 호출이 1회로 뭉친다.
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L),
                new AssignmentTuple("일정표 공유", 42L, AssigneeSource.FIRST_PERSON, null, 2L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(ai.verifyTargets).extracting(AssignmentTuple::title)
                .containsExactly("로드맵 초안 작성", "일정표 공유");
    }

    @Test
    @DisplayName("검증 결과를 해당 tuple 행에 적는다 — agree=false 는 검토 대상이다")
    void 검증_결과가_tuple_행에_반영된다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L),
                        item(ItemType.DECISION, "일정 확정", 2L)),
                decisionIds -> decisionIds.stream()
                        .map(id -> new GateVerdict(id, GateStatus.CONFIRMED, "합의됨"))
                        .toList());
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L),
                new AssignmentTuple("일정표 공유", 42L, AssigneeSource.FIRST_PERSON, null, 2L));
        // 두 번째 tuple 만 관점이 갈렸다 — 판정이 tuple 별로 따로 적히는지가 요점이다.
        ai.verifyResults = tuple -> "일정표 공유".equals(tuple.title())
                ? new VerifyTupleResult(false, List.of("assigneeCandidatePersonId"),
                        VerifyVerdict.REJECT, "근거 발화에 담당자 지목이 없음", RecordingAiLayerPort.RUN)
                : new VerifyTupleResult(true, List.of(), VerifyVerdict.ACCEPT, "확인됨",
                        RecordingAiLayerPort.RUN);
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        AssignmentTupleRepository.TupleVerification agreed = tuples.verificationOfTitle("로드맵 초안 작성");
        assertThat(agreed.agree()).isTrue();
        assertThat(agreed.disagreementFields()).isEmpty();

        AssignmentTupleRepository.TupleVerification disagreed = tuples.verificationOfTitle("일정표 공유");
        assertThat(disagreed.agree()).isFalse();
        assertThat(disagreed.disagreementFields()).containsExactly("assigneeCandidatePersonId");
        assertThat(disagreed.verdict()).isEqualTo(VerifyVerdict.REJECT);
        // 검증에 쓴 모델을 L4 의 것과 따로 남긴다 — 한 칸을 공유하면 추출 모델이 지워진다.
        assertThat(disagreed.modelName()).isEqualTo("gemini-flash");
    }

    @Test
    @DisplayName("검증에는 L4 에 넘긴 것과 같은 확정 항목을 넘긴다")
    void 검증_입력은_L4와_같다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L),
                        // 게이트를 통과하지 못할 항목 — 검증 재추출에도 실리면 안 된다.
                        item(ItemType.DISCUSSION, "예산은 논의만", 2L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨"),
                        new GateVerdict(decisionIds.get(1), GateStatus.DISCUSSED, "결론 없음")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));

        orchestrator(summaries, new FakeTupleRepository(), ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 입력이 갈리면 불일치가 관점 차이인지 입력 차이인지 구분되지 않는다.
        assertThat(ai.verifyItemRequests).hasSize(1);
        assertThat(ai.verifyItemRequests.get(0)).isEqualTo(ai.extractRequests.get(0));
        // 기준일도 같아야 한다 — 갈리면 dueDate 차이가 "관점이 갈렸다"로 기록된다.
        assertThat(ai.verifyMeetingDates).containsExactly(MEETING_DATE);
        // 확정된 것만 실렸다.
        assertThat(ai.verifyItemRequests.get(0))
                .extracting(AiLayerPort.ConfirmedItem::content)
                .containsExactly("로드맵 확정");
    }

    @Test
    @DisplayName("뽑힌 tuple 이 없으면 L5 를 부르지 않는다 — 실패가 아니라 대상 0건이다")
    void tuple이_없으면_L5를_부르지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        // L4 가 아무것도 못 뽑았다.
        ai.tuples = List.of();
        FakeLayerRepository layers = new FakeLayerRepository();

        AnalysisOutcome outcome = orchestrator(summaries, new FakeTupleRepository(), ai, layers).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(ai.verifyTargets).isEmpty();
        // 계층은 DONE 으로 닫힌다. FAILED 로 두면 ANLZ-02 가 영원히 재시도한다.
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(layers.done).contains(LayerName.L5);
        assertThat(layers.failed).isEmpty();
    }

    @Test
    @DisplayName("L5 가 실패하면 분석이 L5 에서 멈춘다 — 검증 없이 완료로 넘어가지 않는다")
    void L5_실패는_분석을_멈춘다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        // 두 관점이 모두 실패했을 때 Python 이 던지는 분류다.
        ai.verifyFailure = new AiLayerException("ALL_VIEWS_FAILED", "두 관점이 모두 실패", true);
        FakeTupleRepository tuples = new FakeTupleRepository();
        FakeLayerRepository layers = new FakeLayerRepository();

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai, layers).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.FAILED);
        assertThat(outcome.failedLayer()).isEqualTo(LayerName.L5);
        assertThat(outcome.retryable()).isTrue();
        assertThat(layers.failed).containsKey(LayerName.L5);
        // tuple 은 이미 저장돼 있고 지워지지 않는다 — 검증 실패는 추출 실패가 아니다.
        assertThat(tuples.saved).hasSize(1);
        // 다만 판정은 남지 않는다. 미검증(NULL)이 "검증에서 걸렸다"와 섞이면 안 된다.
        assertThat(tuples.verifications).isEmpty();
    }

    // ── L6 · L7 · 코드 계층 게이트 ──────────────────────────────────────────────

    @Test
    @DisplayName("L7 이 tuple 을 자동확정과 검토 필요로 가른다 — 검토 화면의 두 묶음이다")
    void L7이_두_묶음으로_가른다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L),
                        item(ItemType.DECISION, "일정 확정", 2L)),
                decisionIds -> decisionIds.stream()
                        .map(id -> new GateVerdict(id, GateStatus.CONFIRMED, "합의됨"))
                        .toList());
        ai.tuples = List.of(
                // 명시적 호명 + 명단 안 + 근거 있음 → 신호 넷을 다 만족할 수 있다.
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L),
                // 담당자가 명단 밖(unknown_person) → 조건2 에서 걸린다.
                new AssignmentTuple("일정표 공유", null, AssigneeSource.EXPLICIT_CALL, null, 2L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);

        AssignmentTupleRepository.TupleGateVerdict passed = tuples.gateOfTitle("로드맵 초안 작성");
        assertThat(passed.autoConfirmed()).isTrue();

        AssignmentTupleRepository.TupleGateVerdict blocked = tuples.gateOfTitle("일정표 공유");
        assertThat(blocked.autoConfirmed()).isFalse();
        // 어느 조건에서 걸렸는지가 남아야 게이트를 조일지 풀지 판단할 수 있다.
        assertThat(blocked.signals().assigneeInRoster()).isFalse();
        assertThat(blocked.signals().hasEvidence()).isTrue();
    }

    @Test
    @DisplayName("L5 가 갈렸다고 한 tuple 은 자동확정하지 않는다")
    void 관점이_갈린_tuple은_자동확정되지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        ai.verifyResults = tuple -> new VerifyTupleResult(false, List.of("assigneeCandidatePersonId"),
                VerifyVerdict.REJECT, "담당자 지목 없음", RecordingAiLayerPort.RUN);
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        AssignmentTupleRepository.TupleGateVerdict verdict = tuples.gateOfTitle("로드맵 초안 작성");
        assertThat(verdict.signals().viewsAgree()).isFalse();
        assertThat(verdict.autoConfirmed()).isFalse();
    }

    @Test
    @DisplayName("L6 이 중복을 잡으면 신호가 다 통과해도 자동확정하지 않는다")
    void 중복_tuple은_자동확정되지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        // 같은 근거 발화(1)에서 tuple 이 둘 나왔다 — L2 오버랩의 부산물이다.
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L),
                new AssignmentTuple("로드맵 정리", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(tuples.conflictsOfTitle("로드맵 초안 작성"))
                .containsExactly(ConflictType.DUPLICATE_EVIDENCE);

        AssignmentTupleRepository.TupleGateVerdict verdict = tuples.gateOfTitle("로드맵 초안 작성");
        // 신호 넷은 전부 통과했다 — 모순 때문에 걸린 것이라 둘을 따로 세야 한다.
        assertThat(verdict.signals().allPassed()).isTrue();
        assertThat(verdict.autoConfirmed()).isFalse();
    }

    @Test
    @DisplayName("모순이 없는 tuple 도 L6 결과에 담는다 — '검사했고 깨끗함'이 남아야 한다")
    void 모순이_없어도_검사_결과를_남긴다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(tuples.conflictsOfTitle("로드맵 초안 작성")).isEmpty();
    }

    @Test
    @DisplayName("tuple 이 없어도 L6·L7 은 DONE 으로 닫힌다 — 대상 0건은 실패가 아니다")
    void tuple이_없어도_코드_계층은_완료된다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DISCUSSION, "가격 논의", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.DISCUSSED, "결론 없음")));
        FakeLayerRepository layers = new FakeLayerRepository();

        AnalysisOutcome outcome = orchestrator(summaries, new FakeTupleRepository(), ai, layers).run(
                TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(layers.done).contains(LayerName.L6, LayerName.L7);
        assertThat(layers.failed).isEmpty();
    }

    // ── DIST · 액션 분배 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("게이트에서 걸린 배정도 분배한다 — 「AI 확인 필요」 묶음이 검토 화면에 있어야 한다")
    void 자동확정되지_않은_tuple도_분배한다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                // 하나는 자동확정된다(명시적 호명 · 명단 안 · 근거 있음 · 관점 일치).
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L),
                // 하나는 명단 밖 담당자라 게이트에서 걸린다.
                new AssignmentTuple("가격표 정리", 99L, AssigneeSource.EXPLICIT_CALL, null, 2L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingDistributionPort actions = new RecordingDistributionPort();

        orchestrator(summaries, tuples, ai, new FakeLayerRepository(), actions)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 둘 다 액션이 됐다. 통과한 것만 만들면 사람이 봐야 하는 쪽이 화면에 아예 없다.
        assertThat(actions.items).extracting(ActionDistributionItem::title)
                .containsExactly("로드맵 초안 작성", "가격표 정리");
        assertThat(tuples.gateOfTitle("로드맵 초안 작성").autoConfirmed()).isTrue();
        assertThat(tuples.gateOfTitle("가격표 정리").autoConfirmed()).isFalse();
    }

    @Test
    @DisplayName("분배 결과가 tuple 에 되짚힌다 — action_id 가 없으면 다음 실행이 같은 액션을 또 만든다")
    void 분배된_actionId를_tuple에_적는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();

        orchestrator(summaries, tuples, ai).run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(tuples.distributions).hasSize(1);
        assertThat(tuples.actionIdOfTitle("로드맵 초안 작성")).isNotNull();
    }

    @Test
    @DisplayName("분석 경로는 PERSONAL 액션만 만든다 — 수동 추가가 아니고, 출처 회의·프로젝트가 실린다")
    void 분배_입력은_PERSONAL이고_출처가_실린다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        RecordingDistributionPort actions = new RecordingDistributionPort();

        orchestrator(summaries, new FakeTupleRepository(), ai, new FakeLayerRepository(), actions)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        ActionDistributionItem item = actions.items.get(0);
        assertThat(item.actionType()).isEqualTo(ActionType.PERSONAL);
        assertThat(item.isManual()).isFalse();
        assertThat(item.sourceMeetingId()).isEqualTo(MEETING);
        assertThat(item.projectId()).isEqualTo(PROJECT);
        assertThat(item.companyId()).isEqualTo(COMPANY);
        // 근거 발화가 함께 넘어간다 — 검토 화면이 이 id 로 원문을 인용한다.
        assertThat(item.evidenceTranscriptId()).isEqualTo(1L);
        // 게이트 신호는 사본으로 싣는다. 판정까지 함께 있어야 "넷은 통과했는데 모순으로
        // 걸린 건"이 사본에서 통과한 것처럼 보이지 않는다.
        assertThat(item.gateSignals()).contains("\"autoConfirmed\":true");
    }

    @Test
    @DisplayName("담당자 미정 tuple 은 분배하지 않는다 — PERSONAL 액션은 담당자가 필수다")
    void 담당자가_없는_tuple은_분배하지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                // 명단 밖을 가리켰거나 담당자를 못 정한 tuple 이다. 이것을 TEAM 으로 돌리면
                // 다른 종류의 액션을 지어내는 것이 된다(분석 경로는 PERSONAL 만 생산한다).
                new AssignmentTuple("누군가 정리", null, null, null, 1L),
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 2L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingDistributionPort actions = new RecordingDistributionPort();

        orchestrator(summaries, tuples, ai, new FakeLayerRepository(), actions)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(actions.items).extracting(ActionDistributionItem::title)
                .containsExactly("로드맵 초안 작성");
        // 대기실에는 남아 있다 — 지우지 않는다. 다만 action 이 없어 검토 화면에는 안 보인다.
        assertThat(tuples.saved).hasSize(2);
        assertThat(tuples.distributions).hasSize(1);
    }

    @Test
    @DisplayName("이미 분배된 회의는 다시 분배하지 않는다 — 같은 일이 보드에 두 번 꽂히면 안 된다")
    void 이미_분배된_회의는_다시_분배하지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingDistributionPort actions = new RecordingDistributionPort();
        FakeLayerRepository layers = new FakeLayerRepository();

        /*
         * tuple 은 재실행마다 통째로 갈리므로(replace) action_id 가 사라진다. tuple 만 보면
         * "아직 분배 안 됨"으로 보이는데, action 쪽에는 이전 벌이 그대로 있는 상태다.
         */
        AnalysisOutcome outcome = new AnalysisOrchestrator(
                new FakeTranscriptRepository(utterances()), new FakeCaptionRepository(),
                layers, new FakeRunRepository(), summaries, tuples, meetingId -> Optional.of(MEETING_DATE),
                new SpeakerAttributionResolver(), new ConflictDetector(), new AutoConfirmGate(),
                new TupleDistributionService(tuples, actions, meetingId -> Optional.of(PROJECT),
                        // 이 회의에는 이미 분석 경로로 만든 액션이 있다.
                        (companyId, meetingId) -> true, new ObjectMapper()),
                ai, command -> {}, meetingId -> Optional.empty())
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 분석은 실패가 아니다. 액션만 만들지 않는다 — 지우는 쪽이 아니라 멈추는 쪽이 안전하다.
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(layers.done).contains(LayerName.DIST);
        assertThat(actions.items).isEmpty();
        assertThat(tuples.distributions).isEmpty();
    }

    @Test
    @DisplayName("프로젝트를 못 읽으면 DIST 가 FAILED 로 남는다 — 액션 없는 회의가 완료로 닫히면 안 된다")
    void 프로젝트를_못_읽으면_DIST가_실패로_남는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingDistributionPort actions = new RecordingDistributionPort();
        FakeLayerRepository layers = new FakeLayerRepository();

        AnalysisOutcome outcome = new AnalysisOrchestrator(
                new FakeTranscriptRepository(utterances()), new FakeCaptionRepository(),
                layers, new FakeRunRepository(), summaries, tuples, meetingId -> Optional.of(MEETING_DATE),
                new SpeakerAttributionResolver(), new ConflictDetector(), new AutoConfirmGate(),
                new TupleDistributionService(tuples, actions,
                        // meeting.project_id 는 NOT NULL 이라, 비었다는 것은 회의 행을 못 읽은
                        // 것이다 — 분배할 것이 없는 정상 상태가 아니라 데이터 오류다.
                        meetingId -> Optional.empty(),
                        (companyId, meetingId) -> false, new ObjectMapper()),
                ai, command -> {}, meetingId -> Optional.empty())
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        /*
         * DONE 으로 닫으면 isFullyAnalyzed 가 true 가 되고, 액션이 하나도 없는 회의가 force
         * 없이는 다시 돌지 않는다. FAILED 로 남겨야 ANLZ-02 가 이어서 돌릴 수 있다.
         */
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.FAILED);
        assertThat(outcome.failedLayer()).isEqualTo(LayerName.DIST);
        assertThat(layers.failed).containsKey(LayerName.DIST);
        assertThat(layers.done).doesNotContain(LayerName.DIST);
        // 앞 계층은 DONE 으로 남는다 — 판정을 다시 만들지 않고 분배만 다시 시도할 수 있다.
        assertThat(layers.done).contains(LayerName.L7);
        assertThat(actions.items).isEmpty();
    }

    @Test
    @DisplayName("tuple 이 없으면 분배도 없고, 그래도 DIST 는 DONE 이다 — 대상 0건은 실패가 아니다")
    void tuple이_없으면_분배하지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DISCUSSION, "가격 논의", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.DISCUSSED, "결론 없음")));
        FakeLayerRepository layers = new FakeLayerRepository();
        RecordingDistributionPort actions = new RecordingDistributionPort();

        AnalysisOutcome outcome = orchestrator(
                summaries, new FakeTupleRepository(), ai, layers, actions)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(layers.done).contains(LayerName.DIST);
        assertThat(layers.failed).isEmpty();
        // 빈 요청을 보내지 않는다 — C 쪽에 아무 일도 시키지 않는 호출이다.
        assertThat(actions.items).isEmpty();
    }

    // ── 실행 순서 (#134) ────────────────────────────────────────────────────────

    @Test
    @DisplayName("더 나중 실행이 시작되면 오래된 실행은 그 자리에서 멈춘다 — 옛 결과가 최신을 덮지 않는다")
    void 밀린_실행은_다음_계층을_잡지_못하고_멈춘다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        FakeTupleRepository tuples = new FakeTupleRepository();
        FakeRunRepository runs = new FakeRunRepository();
        FakeLayerRepository layers = new FakeLayerRepository(runs);

        /*
         * L2 를 잡으려는 순간 다른 실행이 시작한다. 이슈 #134 의 순서가 정확히 이 모양이다 —
         * 오래된 실행이 옛 발화를 손에 든 채 뒤늦게 저장하러 오는 것이다.
         */
        layers.beforeLock = layer -> {
            if (layer == LayerName.L2) {
                runs.anotherRunStarts();
            }
        };

        AnalysisOutcome outcome = orchestrator(summaries, tuples, ai, layers)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // 실패가 아니다. 순서가 정해진 것이고, 이 실행이 할 일은 더 새 실행이 한다.
        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.SUPERSEDED);
        assertThat(outcome.failedLayer()).isEqualTo(LayerName.L2);
        assertThat(outcome.retryable()).isFalse();

        // L2 를 잡지 못했으므로 그 뒤 계층은 하나도 돌지 않는다.
        assertThat(layers.locked).containsExactly(LayerName.L1, LayerName.L1_5);
        assertThat(layers.done).doesNotContain(LayerName.L2);

        /*
         * FAILED 로 남기지 않는 것이 요점이다 — 남기면 ANLZ-02 가 이 계층을 재개 대상으로 보고
         * 오래된 실행을 되살린다. 우리가 막으려던 것이 그것이다.
         */
        assertThat(layers.failed).isEmpty();
        // 밀린 실행은 아무것도 저장하지 않았다.
        assertThat(summaries.findByMeeting(COMPANY, MEETING).orElseThrow().topics()).isEmpty();
        assertThat(tuples.saved).isEmpty();
    }

    @Test
    @DisplayName("실행 번호를 못 받으면 계층을 하나도 잡지 않는다 — 같은 순간에 시작한 실행에 양보한다")
    void 실행_번호_발급에_밀리면_계층을_시작하지_않는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        FakeRunRepository runs = new FakeRunRepository();
        runs.conflicted = true;     // 다른 실행이 이 회의의 첫 행을 먼저 넣었다.
        FakeLayerRepository layers = new FakeLayerRepository(runs);

        AnalysisOutcome outcome = orchestrator(summaries, new FakeTupleRepository(), ai, layers)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.SUPERSEDED);
        // 아직 계층에 닿기 전이라 실패 계층이 없다.
        assertThat(outcome.failedLayer()).isNull();
        assertThat(layers.locked).isEmpty();
        // 모델을 부르지 않았다 — 물러날 실행에 토큰을 쓰지 않는다.
        assertThat(ai.gateRequests).isEmpty();
    }

    @Test
    @DisplayName("순서가 지켜지는 평범한 실행은 그대로 끝까지 돈다")
    void 최신_실행은_모든_계층을_잡는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        FakeRunRepository runs = new FakeRunRepository();
        FakeLayerRepository layers = new FakeLayerRepository(runs);

        AnalysisOutcome outcome = orchestrator(summaries, new FakeTupleRepository(), ai, layers)
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(outcome.status()).isEqualTo(AnalysisOutcome.Status.DONE);
        assertThat(layers.locked).containsExactly(
                LayerName.L1, LayerName.L1_5, LayerName.L2, LayerName.L3, LayerName.L3_5,
                LayerName.L4, LayerName.L5, LayerName.L6, LayerName.L7, LayerName.DIST);
    }

    // ── 미터링 배선 (teamId) ────────────────────────────────────────────────────

    @Test
    @DisplayName("분석 성공 시 회의 teamId 를 미터링 원장에 실어 기록한다")
    void 미터링_원장에_회의_teamId를_싣는다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingTokenUsagePort metering = new RecordingTokenUsagePort();

        meteringOrchestrator(summaries, tuples, ai, metering, meetingId -> Optional.of(TEAM))
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        // LLM 계층이 돌았으니 원장에 1회 기록된다.
        assertThat(metering.commands).hasSize(1);
        RecordTokenUsageCommand recorded = metering.commands.get(0);
        // 회의 teamId 가 그대로 실린다 — null 로 두면 대시보드 부서 breakdown 이 영영 안 잡힌다.
        assertThat(recorded.teamId()).isEqualTo(TEAM);
        assertThat(recorded.companyId()).isEqualTo(COMPANY);
        assertThat(recorded.meetingId()).isEqualTo(MEETING);
    }

    @Test
    @DisplayName("teamId 가 없는(OWNER 개설) 회의는 회사 단위로만 기록한다 — teamId=null")
    void teamId가_없으면_회사_단위로_기록한다() {
        FakeSummaryRepository summaries = new FakeSummaryRepository();
        RecordingAiLayerPort ai = new RecordingAiLayerPort(
                List.of(item(ItemType.DECISION, "로드맵 확정", 1L)),
                decisionIds -> List.of(new GateVerdict(decisionIds.get(0), GateStatus.CONFIRMED, "합의됨")));
        ai.tuples = List.of(
                new AssignmentTuple("로드맵 초안 작성", 42L, AssigneeSource.EXPLICIT_CALL, null, 1L));
        FakeTupleRepository tuples = new FakeTupleRepository();
        RecordingTokenUsagePort metering = new RecordingTokenUsagePort();

        // team_id 가 NULL 이거나 회의 행을 못 읽으면 Optional.empty() — 둘 다 회사 단위 경로다.
        meteringOrchestrator(summaries, tuples, ai, metering, meetingId -> Optional.empty())
                .run(TENANT, COMPANY, MEETING, PARTICIPANTS, false);

        assertThat(metering.commands).hasSize(1);
        assertThat(metering.commands.get(0).teamId()).isNull();
    }

    // ── 조립 ────────────────────────────────────────────────────────────────────

    private AnalysisOrchestrator orchestrator(FakeSummaryRepository summaries,
                                              FakeTupleRepository tuples,
                                              RecordingAiLayerPort ai) {
        return orchestrator(summaries, tuples, ai, new FakeLayerRepository());
    }

    private AnalysisOrchestrator orchestrator(FakeSummaryRepository summaries,
                                              FakeTupleRepository tuples,
                                              RecordingAiLayerPort ai,
                                              FakeLayerRepository layers) {
        return orchestrator(summaries, tuples, ai, layers, new RecordingDistributionPort());
    }

    private AnalysisOrchestrator orchestrator(FakeSummaryRepository summaries,
                                              FakeTupleRepository tuples,
                                              RecordingAiLayerPort ai,
                                              FakeLayerRepository layers,
                                              RecordingDistributionPort actions) {
        return orchestratorOf(new FakeTranscriptRepository(utterances()), new FakeCaptionRepository(),
                layers, summaries, tuples, meetingId -> Optional.of(MEETING_DATE), ai, actions);
    }

    private static AnalysisOrchestrator orchestratorOf(FakeTranscriptRepository transcripts,
                                                       FakeCaptionRepository captions,
                                                       FakeLayerRepository layers,
                                                       FakeSummaryRepository summaries,
                                                       FakeTupleRepository tuples,
                                                       MeetingDateProvider dates,
                                                       RecordingAiLayerPort ai) {
        return orchestratorOf(transcripts, captions, layers, summaries, tuples, dates, ai,
                new RecordingDistributionPort());
    }

    private static AnalysisOrchestrator orchestratorOf(FakeTranscriptRepository transcripts,
                                                       FakeCaptionRepository captions,
                                                       FakeLayerRepository layers,
                                                       FakeSummaryRepository summaries,
                                                       FakeTupleRepository tuples,
                                                       MeetingDateProvider dates,
                                                       RecordingAiLayerPort ai,
                                                       RecordingDistributionPort actions) {
        return new AnalysisOrchestrator(
                transcripts, captions, layers,
                /*
                 * 실행 번호 저장소는 잠금 가짜와 **같은 것을 공유해야** 한다 — 실물에서도 잠금이
                 * 같은 행을 보고 순서를 판정한다. 따로 주면 번호를 올려도 잠금이 모른다.
                 */
                layers.runs != null ? layers.runs : new FakeRunRepository(),
                summaries, tuples, dates,
                // 판정 로직은 순수 계산이라 가짜로 대체하지 않는다 — 실물을 넣어야
                // 오케스트레이터가 참석자 명단을 어떻게 넘기는지까지 함께 검증된다.
                new SpeakerAttributionResolver(), new ConflictDetector(), new AutoConfirmGate(),
                /*
                 * 분배 서비스도 실물이다. **같은 tuple 저장소를 넘기는 것이 요점**이다 —
                 * 분배가 무엇을 읽어 무엇을 만드는지는 tuple 을 공유해야만 검증된다.
                 * 액션 생성(C 도메인)만 가짜로 막는다.
                 */
                new TupleDistributionService(tuples, actions,
                        meetingId -> Optional.of(PROJECT), (companyId, meetingId) -> false,
                        new ObjectMapper()),
                ai, command -> {}, meetingId -> Optional.empty());
    }

    /* 미터링 원장에 무엇이 실리는지 검증하는 조립 — 기록 포트와 teamId 프로바이더를 주입한다. */
    private static AnalysisOrchestrator meteringOrchestrator(FakeSummaryRepository summaries,
                                                             FakeTupleRepository tuples,
                                                             RecordingAiLayerPort ai,
                                                             RecordTokenUsagePort metering,
                                                             MeetingTeamProvider teams) {
        return new AnalysisOrchestrator(
                new FakeTranscriptRepository(utterances()), new FakeCaptionRepository(),
                new FakeLayerRepository(), new FakeRunRepository(), summaries, tuples,
                meetingId -> Optional.of(MEETING_DATE),
                new SpeakerAttributionResolver(), new ConflictDetector(), new AutoConfirmGate(),
                new TupleDistributionService(tuples, new RecordingDistributionPort(),
                        meetingId -> Optional.of(PROJECT), (companyId, meetingId) -> false,
                        new ObjectMapper()),
                ai, metering, teams);
    }

    private static List<Utterance> utterances() {
        return List.of(
                new Utterance(1L, 42L, 0, 3_000, "로드맵 정리합시다"),
                new Utterance(2L, null, 5_000, 8_000, "그거 그분한테 맡기죠"));
    }

    private static com.module06.backend.capture.domain.model.TopicItem item(
            ItemType type, String content, Long evidenceUtteranceId) {
        return new com.module06.backend.capture.domain.model.TopicItem(
                type, content, "그렇게 분류한 근거", evidenceUtteranceId);
    }

    // ── 가짜 구현 ───────────────────────────────────────────────────────────────

    /*
     * 계층 호출을 기록하는 AiLayerPort. 무엇을 실어 보냈는지가 검증 대상이다 —
     * 게이트를 지나지 않은 항목이 L4 요청에 섞이는지는 요청 본문으로만 확인할 수 있다.
     */
    private static final class RecordingAiLayerPort implements AiLayerPort {

        private static final LayerRun RUN = new LayerRun(100, 20, "gemini-flash", "v1");

        private final List<com.module06.backend.capture.domain.model.TopicItem> l3Items;
        private final java.util.function.Function<List<Long>, List<GateVerdict>> verdicts;

        private List<ResolvedReference> references = List.of();
        private List<AssignmentTuple> tuples = List.of();
        private AiLayerException gateFailure;

        private int resolveCalls;
        private List<Utterance> resolveUtterances = List.of();
        private List<Utterance> segmentUtterances = List.of();
        private final List<List<GateCandidate>> gateRequests = new ArrayList<>();
        private final List<List<ConfirmedItem>> extractRequests = new ArrayList<>();
        private final List<LocalDate> meetingDates = new ArrayList<>();

        private final List<AssignmentTuple> verifyTargets = new ArrayList<>();
        private final List<List<ConfirmedItem>> verifyItemRequests = new ArrayList<>();
        private final List<LocalDate> verifyMeetingDates = new ArrayList<>();
        /* 기본은 두 관점 일치다. 불일치를 보려는 테스트만 이 함수를 바꾼다. */
        private java.util.function.Function<AssignmentTuple, VerifyTupleResult> verifyResults =
                tuple -> new VerifyTupleResult(true, List.of(), VerifyVerdict.ACCEPT, "근거 발화로 확인됨", RUN);
        private AiLayerException verifyFailure;

        /* 게이트 판정은 저장된 항목의 id 를 알아야 만들 수 있어 함수로 받는다. */
        private RecordingAiLayerPort(List<com.module06.backend.capture.domain.model.TopicItem> l3Items,
                                     java.util.function.Function<List<Long>, List<GateVerdict>> verdicts) {
            this.l3Items = l3Items;
            this.verdicts = verdicts;
        }

        @Override
        public ResolveReferenceResult resolveReference(long tenantId, long meetingId, List<Utterance> utterances,
                                                       List<Long> targetUtteranceIds, List<Participant> participants) {
            resolveCalls++;
            resolveUtterances = List.copyOf(utterances);
            // 계약: null 이 아니라 빈 리스트여야 한다(pydantic 이 list 자리의 None 을 422 로 거절한다).
            assertThat(targetUtteranceIds).isNotNull();
            return new ResolveReferenceResult(references, RUN);
        }

        @Override
        public SegmentTopicsResult segmentTopics(long tenantId, long meetingId, List<Utterance> utterances) {
            segmentUtterances = List.copyOf(utterances);
            return new SegmentTopicsResult(
                    List.of(new TopicSegment(1, "제품 로드맵", 1L, 2L, List.of(1L, 2L))), RUN);
        }

        @Override
        public SummarizeTopicResult summarizeTopic(long tenantId, long meetingId, int topicSeq, String topic,
                                                   List<Utterance> utterances, List<Participant> participants) {
            return new SummarizeTopicResult("주제 요약", l3Items, RUN);
        }

        @Override
        public GateResult gate(long tenantId, long meetingId, String topic, List<GateCandidate> candidates,
                               List<Utterance> utterances, List<Participant> participants) {
            if (gateFailure != null) {
                throw gateFailure;
            }
            gateRequests.add(List.copyOf(candidates));
            return new GateResult(
                    verdicts.apply(candidates.stream().map(GateCandidate::decisionId).toList()), RUN);
        }

        @Override
        public ExtractTuplesResult extractTuples(long tenantId, long meetingId, String topic,
                                                 List<ConfirmedItem> items, List<Utterance> utterances,
                                                 List<Participant> participants, LocalDate meetingDate) {
            extractRequests.add(List.copyOf(items));
            meetingDates.add(meetingDate);
            return new ExtractTuplesResult(tuples, RUN);
        }

        @Override
        public VerifyTupleResult verifyTuple(long tenantId, long meetingId, String topic, AssignmentTuple tuple,
                                             List<ConfirmedItem> items, List<Utterance> utterances,
                                             List<Participant> participants, LocalDate meetingDate) {
            if (verifyFailure != null) {
                throw verifyFailure;
            }
            verifyTargets.add(tuple);
            verifyItemRequests.add(List.copyOf(items));
            verifyMeetingDates.add(meetingDate);
            return verifyResults.apply(tuple);
        }
    }

    /*
     * meeting_summary·meeting_decision 을 흉내낸다. **id 를 붙여 돌려주는 것**이 요점이다 —
     * 게이트 판정을 그 id 로 되짚고, L4 는 되짚힌 gate_status 만 본다.
     */
    private static final class FakeSummaryRepository implements MeetingSummaryRepository {

        private final Map<Long, ItemView> byId = new LinkedHashMap<>();
        private final Map<Long, Integer> topicSeqById = new LinkedHashMap<>();
        private final Map<Integer, String> topicNameBySeq = new LinkedHashMap<>();
        private long nextId = 1_000L;

        @Override
        public void replace(long companyId, long meetingId, String overview, List<TopicDecisions> topics,
                            String modelName, String promptVersion) {
            byId.clear();
            topicSeqById.clear();
            topicNameBySeq.clear();

            for (TopicDecisions topic : topics) {
                topicNameBySeq.put(topic.topicSeq(), topic.topic());
                for (com.module06.backend.capture.domain.model.TopicItem item : topic.items()) {
                    long id = nextId++;
                    // 저장 시점의 gate_status 는 항상 null 이다 — 게이트가 아직 안 돌았다.
                    byId.put(id, new ItemView(id, item.itemType(), item.content(), item.reason(),
                            item.evidenceUtteranceId(), null));
                    topicSeqById.put(id, topic.topicSeq());
                }
            }
        }

        @Override
        public int applyGateVerdicts(long meetingId, List<GateVerdict> verdicts) {
            int applied = 0;
            for (GateVerdict verdict : verdicts) {
                ItemView existing = byId.get(verdict.decisionId());
                if (existing == null || verdict.gateStatus() == null) {
                    continue;
                }
                byId.put(existing.id(), new ItemView(existing.id(), existing.itemType(), existing.content(),
                        existing.reason(), existing.evidenceUtteranceId(), verdict.gateStatus().name()));
                applied++;
            }
            return applied;
        }

        @Override
        public Optional<MeetingSummaryView> findByMeeting(long companyId, long meetingId) {
            Map<Integer, List<ItemView>> grouped = new LinkedHashMap<>();
            byId.forEach((id, item) ->
                    grouped.computeIfAbsent(topicSeqById.get(id), seq -> new ArrayList<>()).add(item));

            List<TopicView> topics = grouped.entrySet().stream()
                    .map(entry -> new TopicView(entry.getKey(),
                            topicNameBySeq.get(entry.getKey()), entry.getValue()))
                    .toList();
            return Optional.of(new MeetingSummaryView("개요", topics));
        }

        private Long idOfContent(String content) {
            return byId.values().stream()
                    .filter(item -> content.equals(item.content()))
                    .map(ItemView::id)
                    .findFirst()
                    .orElseThrow();
        }

        private Long idOfEvidence(long evidenceUtteranceId) {
            return byId.values().stream()
                    .filter(item -> item.evidenceUtteranceId() != null)
                    .filter(item -> evidenceUtteranceId == item.evidenceUtteranceId())
                    .map(ItemView::id)
                    .findFirst()
                    .orElseThrow();
        }
    }

    /*
     * 정본 저장소. L1 이 이식한 화자를 기록하고, 이식 후 재조회에서 그 값을 반영해 돌려준다 —
     * 오케스트레이터가 "이식 후 다시 읽는다"를 실제로 하는지 이 가짜가 드러낸다.
     */
    private static final class FakeTranscriptRepository implements TranscriptRepository {

        private final List<Utterance> utterances;
        private final List<SpeakerAttributionResolver.Attribution> applied = new ArrayList<>();

        private FakeTranscriptRepository(List<Utterance> utterances) {
            this.utterances = new ArrayList<>(utterances);
        }

        @Override
        public List<Utterance> findByMeetingOrderByOffset(long meetingId) {
            return List.copyOf(utterances);
        }

        /* 이 테스트가 보는 것은 파이프라인이라 근거 검증 경로(RVW-03)는 지나지 않는다. */
        @Override
        public boolean existsInMeeting(long meetingId, long transcriptId) {
            throw new UnsupportedOperationException();
        }

        /*
         * 실물과 같은 계약이다 — **이번 판정이 그 회의의 화자 상태 전부다.** 목록에 없는 발화는
         * 화자를 NULL 로 되돌린다. 덮어쓰기만 흉내내면 "기권했는데 예전 판정이 남는" 버그가
         * 이 가짜에서는 재현되지 않아 테스트가 통과해 버린다.
         */
        @Override
        public int applySpeakerAttributions(long meetingId, List<SpeakerAttributionResolver.Attribution> attributions) {
            applied.addAll(attributions);

            Map<Long, SpeakerAttributionResolver.Attribution> byUtteranceId = new LinkedHashMap<>();
            attributions.forEach(attribution -> byUtteranceId.put(attribution.utteranceId(), attribution));

            utterances.replaceAll(utterance -> {
                SpeakerAttributionResolver.Attribution attribution = byUtteranceId.get(utterance.utteranceId());
                return new Utterance(utterance.utteranceId(),
                        attribution != null ? attribution.speakerMemberId() : null,
                        utterance.startOffsetMs(), utterance.endOffsetMs(), utterance.text());
            });
            return attributions.size();
        }
    }

    /* CAP-11 이 미구현이라 기본값은 자막 0건이다 — 실제 운영 상태와 같다. */
    private static final class FakeCaptionRepository implements CaptionRepository {

        private final List<CaptionChunk> captions;

        private FakeCaptionRepository(CaptionChunk... captions) {
            this.captions = List.of(captions);
        }

        @Override
        public List<CaptionChunk> findByMeeting(long meetingId) {
            return captions;
        }
    }

    /*
     * meeting_assignment_tuple 을 흉내낸다. **저장하면서 id 를 붙이는 것**이 요점이다 —
     * L5 는 저장된 행의 id 로 판정을 되짚으므로, id 가 없으면 그 경로가 검증되지 않는다.
     */
    private static final class FakeTupleRepository implements AssignmentTupleRepository {

        private final List<TupleRow> saved = new ArrayList<>();
        private final Map<Long, StoredTuple> storedById = new LinkedHashMap<>();
        private final Map<Long, TupleVerification> verifications = new LinkedHashMap<>();
        private final Map<Long, List<ConflictType>> conflicts = new LinkedHashMap<>();
        private final Map<Long, TupleGateVerdict> gateVerdicts = new LinkedHashMap<>();
        private final Map<Long, Long> distributions = new LinkedHashMap<>();
        private int replaceCalls;
        private long nextId = 9_000L;

        @Override
        public void replace(long companyId, long meetingId, List<TupleRow> rows) {
            replaceCalls++;
            saved.clear();
            saved.addAll(rows);

            // 교체이므로 이전 판정도 함께 사라진다 — 새 tuple 이 예전 검증 결과를 물려받으면
            // 검증받지 않은 배정이 검증된 것으로 보인다.
            storedById.clear();
            verifications.clear();
            conflicts.clear();
            gateVerdicts.clear();
            distributions.clear();
            for (TupleRow row : rows) {
                long id = nextId++;
                // 저장 시점의 verify_agree 는 항상 NULL 이다 — L5 가 아직 안 돌았다.
                storedById.put(id, new StoredTuple(id, row.tuple(), row.topicSeq(), row.topic(), null));
            }
        }

        @Override
        public List<StoredTuple> findByMeeting(long companyId, long meetingId) {
            return List.copyOf(storedById.values());
        }

        /*
         * 판정을 그 행에 되짚어 반영한다. **verify_agree 를 StoredTuple 에 실제로 반영하는
         * 것**이 요점이다 — L7 이 그 값을 네 번째 조건으로 읽으므로, 반영하지 않으면 게이트가
         * 언제나 미검증으로 보고 아무것도 자동확정하지 않는다.
         */
        @Override
        public int applyVerifications(long meetingId, List<TupleVerification> incoming) {
            int applied = 0;
            for (TupleVerification verification : incoming) {
                StoredTuple existing = storedById.get(verification.tupleId());
                if (existing != null) {
                    verifications.put(verification.tupleId(), verification);
                    storedById.put(existing.id(), new StoredTuple(existing.id(), existing.tuple(),
                            existing.topicSeq(), existing.topic(), verification.agree()));
                    applied++;
                }
            }
            return applied;
        }

        @Override
        public int applyConflicts(long meetingId, List<TupleConflicts> incoming) {
            int applied = 0;
            for (TupleConflicts conflict : incoming) {
                if (storedById.containsKey(conflict.tupleId())) {
                    conflicts.put(conflict.tupleId(), conflict.conflicts());
                    applied++;
                }
            }
            return applied;
        }

        @Override
        public int applyGateVerdicts(long meetingId, List<TupleGateVerdict> incoming) {
            int applied = 0;
            for (TupleGateVerdict verdict : incoming) {
                if (storedById.containsKey(verdict.tupleId())) {
                    gateVerdicts.put(verdict.tupleId(), verdict);
                    applied++;
                }
            }
            return applied;
        }

        @Override
        public int applyDistribution(long meetingId, List<TupleDistribution> incoming) {
            int applied = 0;
            for (TupleDistribution distribution : incoming) {
                // 이미 분배된 행은 거절한다 — 실물 엔티티(applyDistribution)와 같은 규칙이다.
                if (storedById.containsKey(distribution.tupleId())
                        && !distributions.containsKey(distribution.tupleId())) {
                    distributions.put(distribution.tupleId(), distribution.actionId());
                    applied++;
                }
            }
            return applied;
        }

        private TupleVerification verificationOfTitle(String title) {
            return verifications.get(idOfTitle(title));
        }

        private TupleGateVerdict gateOfTitle(String title) {
            return gateVerdicts.get(idOfTitle(title));
        }

        private List<ConflictType> conflictsOfTitle(String title) {
            return conflicts.get(idOfTitle(title));
        }

        private Long actionIdOfTitle(String title) {
            return distributions.get(idOfTitle(title));
        }

        private Long idOfTitle(String title) {
            return storedById.values().stream()
                    .filter(stored -> title.equals(stored.tuple().title()))
                    .map(StoredTuple::id)
                    .findFirst()
                    .orElseThrow();
        }
    }

    /*
     * 분배 요청을 기록하는 ActionDistributionPort. **무엇을 실어 보냈는지가 검증 대상이다** —
     * 게이트에서 걸린 배정까지 넘어가는지, 담당자 미정이 빠지는지는 요청 본문으로만 확인된다.
     */
    private static final class RecordingDistributionPort implements ActionDistributionPort {

        private final List<ActionDistributionItem> items = new ArrayList<>();
        private long nextActionId = 700L;

        @Override
        public List<DistributedAction> distribute(DistributeActionsCommand command) {
            items.addAll(command.items());
            return command.items().stream()
                    .map(item -> new DistributedAction(nextActionId++, item))
                    .toList();
        }
    }

    /* 미터링 원장에 실린 커맨드를 붙잡아 두는 기록 포트. 무엇을 기록하는지가 검증 대상이다. */
    private static final class RecordingTokenUsagePort implements RecordTokenUsagePort {

        private final List<RecordTokenUsageCommand> commands = new ArrayList<>();

        @Override
        public void record(RecordTokenUsageCommand command) {
            commands.add(command);
        }
    }

    private static final class FakeLayerRepository implements AnalysisLayerRepository {

        private final java.util.Set<LayerName> done = new java.util.HashSet<>();
        private final Map<LayerName, String> failed = new LinkedHashMap<>();
        /* 잠금을 잡은 계층. 밀린 실행이 **어디서 멈췄는지**가 #134 의 검증 대상이다. */
        private final List<LayerName> locked = new ArrayList<>();

        /*
         * 실행 번호를 아는 저장소. 실물 어댑터가 잠금과 같은 트랜잭션에서 이 값을 보는 것을
         * 흉내낸다. null 이면 순서 검사가 없는 것이고, 그때는 항상 잠긴다.
         */
        private final FakeRunRepository runs;
        /* 잠그기 **직전에** 끼어들 자리. 계층 사이에 다른 실행이 시작하는 순간을 만든다. */
        private java.util.function.Consumer<LayerName> beforeLock = layer -> { };

        private FakeLayerRepository() {
            this(null);
        }

        private FakeLayerRepository(FakeRunRepository runs) {
            this.runs = runs;
        }

        @Override
        public LockOutcome tryLock(long meetingId, LayerName layer, long runSeq) {
            beforeLock.accept(layer);
            if (runs != null && runSeq < runs.current) {
                return LockOutcome.of(LockResult.SUPERSEDED);
            }
            locked.add(layer);
            // 실물은 attempt_count 를 준다. 여기서는 늘 첫 주인이다.
            return LockOutcome.acquired(1);
        }

        @Override
        public void markDone(long meetingId, LayerName layer, int attempt, LayerRun run) {
            done.add(layer);
            attempts.add(attempt);
        }

        @Override
        public void markFailed(long meetingId, LayerName layer, int attempt, String errorCode,
                               String errorMessage, LayerRun spent) {
            failed.put(layer, errorCode);
            attempts.add(attempt);
        }

        /* 계층이 한 걸음 나아갈 때마다 찍히는 심장(#177). 몇 번 찍혔는지만 센다. */
        private int heartbeats;
        /* 쓰기에 실려 온 주인 번호(#212). 잠금이 준 값이 그대로 와야 한다. */
        private final List<Integer> attempts = new ArrayList<>();

        @Override
        public void heartbeat(long meetingId, LayerName layer, int attempt) {
            heartbeats++;
            attempts.add(attempt);
        }

        @Override
        public List<LayerState> findStates(long meetingId) {
            return done.stream()
                    .map(layer -> new LayerState(layer, LayerStatus.DONE, 0, 0, false))
                    .toList();
        }
    }

    /*
     * meeting_analysis_run 을 흉내낸다. 실물과 같은 성질 하나만 지킨다 —
     * **발급할 때마다 번호가 오르고, 마지막에 발급된 번호가 곧 최신이다.**
     */
    private static final class FakeRunRepository implements AnalysisRunRepository {

        private long current;
        /* 첫 행 INSERT 경합. 실물에서 PK 충돌로 물러나는 경로다. */
        private boolean conflicted;

        @Override
        public OptionalLong begin(long meetingId) {
            if (conflicted) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(++current);
        }

        /* 다른 실행이 지금 시작했다. 순서 역전을 만드는 손잡이다. */
        void anotherRunStarts() {
            current++;
        }
    }
}
