package com.module06.backend.capture.application.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.module06.backend.capture.application.port.out.AiLayerPort;
import com.module06.backend.capture.application.port.out.AnalysisLayerRepository;
import com.module06.backend.capture.application.port.out.AnalysisLayerRepository.LockOutcome;
import com.module06.backend.capture.application.port.out.AnalysisLayerRepository.LockResult;
import com.module06.backend.capture.application.port.out.AnalysisRunRepository;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.StoredTuple;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleConflicts;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleGateVerdict;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleRow;
import com.module06.backend.capture.application.port.out.AssignmentTupleRepository.TupleVerification;
import com.module06.backend.capture.application.port.out.CaptionRepository;
import com.module06.backend.capture.application.port.out.ExtractTuplesResult;
import com.module06.backend.capture.application.port.out.GateResult;
import com.module06.backend.capture.application.port.out.LayerRun;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository.ItemView;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository.MeetingSummaryView;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository.TopicDecisions;
import com.module06.backend.capture.application.port.out.MeetingSummaryRepository.TopicView;
import com.module06.backend.capture.application.port.out.ResolveReferenceResult;
import com.module06.backend.capture.application.port.out.SegmentTopicsResult;
import com.module06.backend.capture.application.port.out.SummarizeTopicResult;
import com.module06.backend.capture.application.port.out.TranscriptRepository;
import com.module06.backend.capture.application.port.out.VerifyTupleResult;
import com.module06.backend.capture.application.result.AnalysisOutcome;
import com.module06.backend.capture.domain.model.AssignmentTuple;
import com.module06.backend.capture.domain.model.CaptionChunk;
import com.module06.backend.capture.domain.model.ConflictType;
import com.module06.backend.capture.domain.model.GateStatus;
import com.module06.backend.capture.domain.model.GateVerdict;
import com.module06.backend.capture.domain.model.LayerName;
import com.module06.backend.capture.domain.model.LayerStatus;
import com.module06.backend.capture.domain.model.ReferenceType;
import com.module06.backend.capture.domain.model.ResolvedReference;
import com.module06.backend.capture.domain.model.TopicSegment;
import com.module06.backend.capture.domain.model.Utterance;
import com.module06.backend.metering.application.command.RecordTokenUsageCommand;
import com.module06.backend.metering.application.port.in.RecordTokenUsagePort;

/*
 * 분석 파이프라인의 오케스트레이터다. **계층 순서·잠금·상태 기록이 이 저장소의 몫**이고,
 * 계층 안에서 무엇을 판단하는지는 Python 몫이다(명세 「내부 API」 머리말).
 *
 * <h2>지금 도는 구간</h2>
 * L1(화자 귀속) → L1.5(지시어 해소) → L2(주제 분할) → 주제마다 L3(정리)
 * → meeting_summary·meeting_decision 저장 → 주제마다 L3.5(확정/논의 게이트) → gate_status 반영
 * → 주제마다 L4(tuple 추출) → meeting_assignment_tuple 저장
 * → tuple 마다 L5(관점 다변화 검증) → 그 행에 판정 반영
 * → L6(규칙·모순 검사) → L7(자동확정 게이트) → 분배(action 생성 · DIST).
 * **파이프라인은 여기서 끝난다.**
 *
 * 호출 단위가 계층마다 다르다 — 회의당 1회(L1·L1.5·L2·L6·L7) · 주제당 1회(L3·L3.5·L4) ·
 * tuple 당 1회(L5). 단위는 각 계층의 Python 요청이 무엇을 하나로 받는지가 정하고,
 * 코드 계층은 그 판정이 무엇을 한꺼번에 봐야 하는지가 정한다(L6 의 중복 검사가 그렇다).
 *
 * <h2>파이프라인이 끝나면 분배가 이어진다</h2>
 * L7 이 tuple 을 「AI 확신도 높음」과 「AI 확인 필요」로 가르고, 그 두 묶음이 **전부** action
 * 으로 만들어진다(08/05 확정 · ActionDistributionPort 주석). 만들어진 액션은 PENDING 이고
 * 보드 노출은 review_status 로 걸러진다 — 자동 확정 건도 **RVW-05 의 확정 전까지는 아무 데도
 * 가 있지 않다**(명세 RVW-01). 액션을 만드는 것 자체는 C 도메인이 하고, A 는 그 포트를 부른다.
 *
 * L1·L6·L7 이 **코드 계층**이다(LLM 아님 · 토큰 0). 나머지는 Python 계층 호출이다.
 * 계층을 하나 붙일 때 바꾸는 곳은 {@link #run} 안의 호출 순서와 {@link #RUN_LAYERS} 두 곳이고,
 * 잠금·상태 기록·토큰 집계는 {@link #runLayer} 가 공통으로 갖는다.
 *
 * <h2>왜 L3.5 가 L4 와 함께 붙는가</h2>
 * L4 를 단독으로 붙일 수 없다. Python 쪽 요청 스키마가 항목의 gateStatus 를
 * Literal["CONFIRMED"] 로 요구하므로 게이트를 지나지 않은 항목은 넣을 방법이 없고,
 * 전부 CONFIRMED 로 채워 보내는 것은 그 스키마가 막으려던 실패 그대로다 —
 * **아직 합의도 안 된 논의가 담당자에게 배정된다.**
 *
 * <h2>왜 계층마다 잠그나</h2>
 * 파이프라인 전체가 아니라 **계층 단위로** RUNNING 을 잡는다. 그래야 ANLZ-02(계층 재개)가
 * 성립한다 — 실패한 계층부터 이어서 돌리려면 앞 계층이 DONE 으로 남아 있어야 하고,
 * 잠금이 회의 단위면 "어디까지 됐는지"가 사라져 처음부터 다시 돌게 된다. 그만큼 재과금이다.
 *
 * ⚠ 잠금은 "돌고 있는가"만 말하지 **잠근 쪽이 살아 있는가**를 말하지 않는다. 배포나 크래시로
 * 실행이 끊기면 RUNNING 이 그대로 남아 그 회의가 영원히 분석되지 않았다(#177). 그래서 계층이
 * 한 걸음 나아갈 때마다 심장을 찍고({@link #heartbeat}), 멈춘 잠금만 다음 실행이 회수한다.
 *
 * <h2>실패를 삼키지 않는다</h2>
 * 계층이 실패하면 그 계층을 FAILED 로 남기고 **거기서 멈춘다.** 다음 계층으로 넘어가면
 * 입력이 빈 채로 도는데, 그 결과는 "산출물 없음"이 되어 품질 문제로 위장된다.
 * 이 파이프라인에서 가장 위험한 실패 방향이 그거다.
 *
 * <h2>오래된 실행은 최신 실행을 덮지 못한다 (#134)</h2>
 * 계층 잠금은 "동시에 같은 계층을 돌리는 것"만 막는다. 실행 A 가 발화를 읽은 뒤 멈춰 있는
 * 동안 실행 B 가 끝까지 돌고, 그 뒤 A 가 깨어나 옛 입력으로 저장하는 순서는 잠금이 막지 못한다 —
 * 저장 경로가 전부 replace(교체)라 화자·요약·tuple 이 통째로 되돌아간다.
 *
 * 그래서 실행마다 회의 스코프 번호를 발급받고({@link AnalysisRunRepository#begin}),
 * **계층 잠금을 잡을 때마다** 그 번호가 아직 최신인지 확인한다. 밀린 실행은 거기서 멈춘다.
 *
 * 저장 직전이 아니라 잠금 자리에서 확인하는 이유 — 그 자리가 원자적으로 검사할 수 있는 유일한
 * 곳이고, 계층의 모든 쓰기가 그 잠금 안에서 일어난다. 밀린 실행이 더 쓸 수 있는 것은 **지금 쥔
 * 계층 하나**뿐이고, 최신 실행은 그 잠금이 풀린 뒤에야 같은 계층을 잡으므로 계층별 마지막
 * 쓰기는 언제나 최신 실행의 것이 된다.
 *
 * ⚠ 남는 성질 하나 — 최신 실행이 잠금에 막혀 ALREADY_RUNNING 으로 물러나면, 오래된 실행이
 * 자기 계층을 마저 쓰고 최신 입력은 이번에 반영되지 않는다. 덮어쓰기는 아니고(최신 실행은
 * 아무것도 쓰지 않았다) 다음 트리거에서 회복된다. 이건 이 수정 이전부터 있던 성질이다.
 *
 * ⚠ L1.5 는 이 규칙의 경계선이다. 실패해도 L2 의 입력(원문 발화)은 온전하므로 "빈 입력 전파"가
 * 일어나지 않고, 계속 돌리면 요약까지는 나온다. 그래도 멈추는 쪽을 골랐다 — 이어서 돌리려면
 * "일부 계층이 실패한 DONE"이라는 결과 종류가 필요하고, 그건 ANLZ-01 응답과 CAP-06 계약을
 * 함께 바꾸는 일이다. 지금은 계층 상태(analysis_layer)로 어디서 멈췄는지 보이고 ANLZ-02 가
 * 이어서 돌린다. 이 판단을 뒤집을 거면 AnalysisOutcome 에 부분 성공을 먼저 만들 것.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AnalysisOrchestrator {

    /* 이 오케스트레이터가 실제로 도는 계층. 계층을 붙이면 여기도 같이 늘린다 —
     * 빠뜨리면 "전부 완료" 판정이 새 계층을 안 보고 생략을 결정한다. */
    private static final Set<LayerName> RUN_LAYERS = Set.of(
            LayerName.L1, LayerName.L1_5, LayerName.L2, LayerName.L3, LayerName.L3_5,
            LayerName.L4, LayerName.L5, LayerName.L6, LayerName.L7, LayerName.DIST);

    private final TranscriptRepository transcriptRepository;
    private final CaptionRepository captionRepository;
    private final AnalysisLayerRepository analysisLayerRepository;
    private final AnalysisRunRepository analysisRunRepository;
    private final MeetingSummaryRepository meetingSummaryRepository;
    private final AssignmentTupleRepository assignmentTupleRepository;
    private final MeetingDateProvider meetingDateProvider;
    private final SpeakerAttributionResolver speakerAttributionResolver;
    private final ConflictDetector conflictDetector;
    private final AutoConfirmGate autoConfirmGate;
    private final TupleDistributionService tupleDistributionService;
    private final AiLayerPort aiLayerPort;
    // A→미터링 인바운드 포트. 분석이 끝까지 성공하면 이 실행의 토큰 사용량을 원장에 1회 기록한다(usage-based 과금).
    private final RecordTokenUsagePort recordTokenUsagePort;
    // 회의 소속 팀 조회. 미터링 원장에 teamId 를 실어 부서 단위 집계를 가능하게 한다(없으면 회사 단위로만 기록).
    private final MeetingTeamProvider meetingTeamProvider;

    /*
     * 회의 하나를 분석한다.
     *
     * @param tenantId  테넌트(회사) 식별자. 계층에 그대로 넘어가 few-shot 조회 필터가 된다 —
     *                  빠지면 다른 회사 회의 발화가 프롬프트에 주입된다. 정확도 문제가 아니라 유출이다.
     * @param participants 닫힌 목록. 명단 밖 참석자를 나타내는 personId=null 항목을 포함해야 한다.
     */
    public AnalysisOutcome run(long tenantId, long companyId, long meetingId,
                               List<AiLayerPort.Participant> participants, boolean force) {

        /*
         * 이미 다 돌아간 회의를 다시 돌리지 않는다.
         *
         * ANLZ-01 도 같은 판정을 하지만(409), 이 오케스트레이터는 곧 MEET-08(회의 종료)과
         * SQS 워커도 부른다. 그 경로는 유스케이스의 검사를 지나지 않으므로, 중복 메시지 하나가
         * 회의 전체를 다시 태우게 된다 — SQS 는 at-least-once 라 중복은 언젠가 반드시 온다.
         * 계층 잠금은 "동시 실행"만 막고 "완료 후 재실행"은 막지 못한다.
         */
        if (!force && isFullyAnalyzed(meetingId)) {
            log.info("분석 생략 — 이미 완료된 회의다. meetingId={}", meetingId);
            return AnalysisOutcome.skipped("이미 분석이 완료된 회의입니다.");
        }

        /*
         * 이 실행의 번호를 발급받는다. **발화를 읽기 전이어야 한다** — 읽고 나서 발급하면
         * 읽기와 발급 사이에 시작한 실행이 더 낮은 번호를 갖게 되고, 더 새 자막을 본 실행이
         * 오래된 것으로 판정되어 거절된다. 순서가 정확히 거꾸로 뒤집힌다(#134).
         */
        OptionalLong issued = analysisRunRepository.begin(meetingId);
        if (issued.isEmpty()) {
            // 같은 순간에 다른 실행이 먼저 시작했다. 그쪽이 최신이므로 물러난다.
            log.info("분석 물러남 — 같은 순간에 시작한 다른 실행이 있다. meetingId={}", meetingId);
            return AnalysisOutcome.superseded(null);
        }
        long runSeq = issued.getAsLong();

        List<Utterance> loaded = transcriptRepository.findByMeetingOrderByOffset(meetingId);
        if (loaded.isEmpty()) {
            // 발화가 없으면 계층을 부르지 않는다. 빈 입력으로 돌리면 빈 결과에 돈만 쓰고,
            // 그 빈 결과가 "분석 완료"로 기록돼 자막·STT 쪽 사고를 가린다.
            // (명세 ANLZ-01 의 SKIPPED_NO_CAPTION 과 같은 성질이다.)
            log.info("분석 생략 — 발화 0건 meetingId={}", meetingId);
            return AnalysisOutcome.skipped("발화가 없어 분석을 건너뛰었습니다.");
        }

        // ── L1 · 화자 귀속 (코드 계층 · LLM 아님) ────────────────────────────────
        /*
         * 파이프라인의 **첫** 계층이다. 자막(caption_chunk)과 정본을 ±1.5초 창에서 맞춰
         * rms 최대인 참석자를 화자로 정하고, 그 결과를 정본에 이식한다. 모델이 아니라 산수다.
         *
         * 여기가 먼저여야 하는 이유 — 뒤 계층 전부가 화자를 본다. L1.5 는 "그분"을 풀 때,
         * L4 는 1인칭 발화("제가 할게요")의 담당자를 정할 때 speakerMemberId 를 쓴다.
         * 나중에 돌리면 이미 담당자가 정해진 뒤에 화자가 밝혀진다.
         *
         * ⚠ **CAP-11(자막 청크 전송)이 아직 구현되지 않았다.** caption_chunk 가 비어 있으면
         * 전원 판정 포기로 끝난다 — 지금과 같은 상태이고, 정상 동작이다. CAP-11 이 붙는 순간
         * 이 계층이 실제로 화자를 채우기 시작한다.
         */
        LayerOutcome<Integer> attributed = runLayer(meetingId, runSeq, LayerName.L1, sink -> {
            /*
             * 판정할 발화를 **잠금 안에서 다시 읽는다.** 위에서 읽은 loaded 는 실행 여부를
             * 가리는 데(발화 0건) 쓰고 여기서는 쓰지 않는다 — 그 읽기와 이 잠금 사이에 자막이
             * 더 들어왔을 수 있고, 그때 옛 목록으로 판정하면 새 발화의 화자가 통째로 빈다.
             *
             * 창을 좁히는 것이지 없애는 것이 아니다. 순서 자체는 실행 번호가 보장한다(#134).
             */
            List<Utterance> fresh = transcriptRepository.findByMeetingOrderByOffset(meetingId);
            List<CaptionChunk> captions = captionRepository.findByMeeting(meetingId);

            // 명단 밖 탈출구(personId=null)는 제외한다 — 소거법과 "전원 자막" 판단의 분모가
            // 실제 참석자 수여야 한다. 탈출구를 포함하면 참석자가 2명인 회의가 3명으로 보여
            // 소거법이 성립하지 않는다. L6·L7 도 같은 집합을 쓴다.
            List<SpeakerAttributionResolver.Attribution> attributions =
                    speakerAttributionResolver.resolve(fresh, captions, attendeeMemberIdsOf(participants));

            int applied = transcriptRepository.applySpeakerAttributions(meetingId, attributions);
            if (applied != attributions.size()) {
                // 그 회의에 없는 발화 id 가 섞였다. 오류로 올리지 않는다 — 이식되지 않은 발화는
                // 화자가 미정으로 남아 안전한 방향이고, 여기서 회의 전체를 실패시키면 발화
                // 하나 때문에 분석을 못 하게 된다.
                log.warn("화자 이식 건수가 판정 수와 다르다 — meetingId={} 판정={} 이식={}",
                        meetingId, attributions.size(), applied);
            }

            // 토큰을 쓰지 않는 코드 계층이다. LayerRun.empty() 를 기록하는 것이 맞다 —
            // 0 을 채우는 것이 아니라 실제로 0 이다(모델을 부르지 않았다).
            return new Accumulated<>(applied, LayerRun.empty());
        });
        if (!attributed.succeeded()) {
            return attributed.toAnalysisOutcome(LayerName.L1);
        }

        /*
         * 이식 후의 정본을 다시 읽는다. 판정 결과를 메모리에서 합치지 않는 이유는 L4 가 게이트
         * 반영 후 값을 다시 읽는 것과 같다 — 이식되지 않은 판정(그 회의에 없는 id)까지 화자가
         * 채워진 것으로 취급하면 DB 와 프롬프트가 서로 다른 말을 하게 된다.
         *
         * ⚠ 판정 0건이어도 다시 읽는다. L1 은 이번에 기권한 발화의 화자를 NULL 로 되돌리므로
         * (applySpeakerAttributions), 판정이 하나도 없어도 DB 는 바뀔 수 있다 — 예전 판정이
         * 전부 지워지는 경우가 정확히 그것이다. loaded 를 재사용하면 이미 지워진 화자를
         * 뒤 계층에 그대로 넘기게 된다.
         */
        List<Utterance> rawUtterances = transcriptRepository.findByMeetingOrderByOffset(meetingId);

        // ── L1.5 · 지시어 해소 ──────────────────────────────────────────────────
        /*
         * L2 **앞에** 돈다. 선행사는 주제 경계를 넘으므로("아까 그 얘기") 회의 전체를 한 번에
         * 보내고, 결과를 뒤 계층이 보는 발화에 반영한다. 순서를 뒤집으면 L4 가 이미 담당자를
         * 정한 뒤에 대명사가 풀린다 — 검토 사유 WRONG_ASSIGNEE 가 L1.5 로 귀속되는 이유다.
         */
        LayerOutcome<ResolveReferenceResult> resolved = runLayer(meetingId, runSeq, LayerName.L1_5, sink -> {
            ResolveReferenceResult result = aiLayerPort.resolveReference(
                    // 대상 발화를 추리지 않고 전체를 넘긴다 — 지시어 후보를 고르는 코드가 아직 없고,
                    // 잘못 추리면 후보에서 빠진 지시어는 아예 풀릴 기회가 없다.
                    tenantId, meetingId, rawUtterances, List.of(), participants);
            sink.add(result.run());
            return new Accumulated<>(result, sink.spent());
        });
        if (!resolved.succeeded()) {
            return resolved.toAnalysisOutcome(LayerName.L1_5);
        }

        /*
         * 해소 결과를 발화에 주석으로 붙인 사본을 만든다. **DB 의 발화는 고치지 않는다** —
         * transcript_chunk 는 원본이고, 사람이 나중에 "정말 그렇게 말했나"를 확인하는 근거다.
         * 원문을 치환하면 그 근거가 사라지고, 잘못된 해소를 되돌릴 수도 없다.
         */
        List<Utterance> utterances = annotate(rawUtterances, resolved.value().references(), participants);

        Map<Long, Utterance> byId = new LinkedHashMap<>();
        utterances.forEach(utterance -> byId.put(utterance.utteranceId(), utterance));

        // ── L2 · 주제 분할 ───────────────────────────────────────────────────────
        LayerOutcome<SegmentTopicsResult> segmented = runLayer(meetingId, runSeq, LayerName.L2, sink -> {
            SegmentTopicsResult result = aiLayerPort.segmentTopics(tenantId, meetingId, utterances);
            // 토큰을 먼저 sink 에 넣는다. 아래에서 던져도 이미 쓴 비용은 기록된다.
            sink.add(result.run());

            if (result.topics().isEmpty()) {
                // 발화는 있는데 주제가 하나도 안 나왔다. 여기서 "회의 전체를 한 주제"로 지어내면
                // 분할이 실패한 사실이 감춰지고, L3 는 회의 전체를 한 번에 요약하게 된다.
                //
                // 계층 안에서 던지는 이유: 밖에서 판정하면 runLayer 가 이미 L2 를 DONE 으로
                // 닫아버려서, 분석은 실패했는데 analysis_layer 는 "완료"라고 말하게 된다.
                // CAP-06 이 그 상태를 그대로 내려주므로 화면도 같이 거짓말한다.
                throw new AiLayerException("EMPTY_TOPICS", "주제 분할 결과가 비어 있습니다.", true);
            }
            return new Accumulated<>(result, sink.spent());
        });
        if (!segmented.succeeded()) {
            return segmented.toAnalysisOutcome(LayerName.L2);
        }

        List<TopicSegment> topics = segmented.value().topics();

        // ── L3 · 주제별 정리 (주제마다 한 번) + 산출물 저장 ──────────────────────
        List<TopicDecisions> decisions = new ArrayList<>();
        StringBuilder overview = new StringBuilder();

        LayerOutcome<Void> summarized = runLayer(meetingId, runSeq, LayerName.L3, sink -> {
            for (TopicSegment topic : topics) {
                SummarizeTopicResult result = aiLayerPort.summarizeTopic(
                        tenantId, meetingId, topic.topicSeq(), topic.topic(),
                        utterancesOf(topic, byId), participants);

                decisions.add(new TopicDecisions(topic.topicSeq(), topic.topic(), result.items()));
                appendOverview(overview, topic, result.summary());
                // 주제마다 부르므로 토큰은 누적한다. 마지막 호출 값만 남기면 회의당 비용이
                // 주제 수만큼 과소 집계되고 QLTY-03 이 틀어진다.
                // sink 에 넣으므로 중간에 터져도 그때까지의 비용이 FAILED 기록에 남는다.
                sink.add(result.run());
            }

            /*
             * 저장을 계층 안에서 한다 — DONE 표시보다 **먼저** 끝나야 한다.
             *
             * 밖에서 저장하면 runLayer 가 L3 를 DONE 으로 닫은 뒤에 저장이 실패할 수 있고,
             * 그러면 "L3 완료인데 요약이 없는" 상태가 남는다. ANLZ-03 은 404 를 주고 CAP-06 은
             * 완료라고 말하는, 아무도 원인을 못 찾는 조합이다.
             */
            meetingSummaryRepository.replace(companyId, meetingId, overview.toString().strip(),
                    decisions, sink.spent().modelName(), sink.spent().promptVersion());

            return new Accumulated<>(null, sink.spent());
        });
        if (!summarized.succeeded()) {
            return summarized.toAnalysisOutcome(LayerName.L3);
        }

        // ── L3.5 · 확정/논의 게이트 (주제마다 한 번) + gate_status 반영 ──────────
        /*
         * L3 **저장 후에** 돈다. 판정을 meeting_decision.gate_status 에 적어야 하고, 저장 전에
         * 부르면 응답을 어느 행에 적용할지 임시 순번으로 맞춰야 한다 — 그 맞추기가 틀리면
         * A 항목의 판정이 B 항목에 저장되는데, 조회는 성공하므로 아무도 오류를 못 본다.
         */
        LayerOutcome<Void> gated = runLayer(meetingId, runSeq, LayerName.L3_5, sink -> {
            Map<Integer, TopicView> savedBySeq = savedTopicsBySeq(companyId, meetingId);
            List<GateVerdict> verdicts = new ArrayList<>();
            // 후보로 보낸 항목의 id. 판정을 되짚을 수 있는 유일한 집합이다.
            Set<Long> candidateIds = new HashSet<>();

            for (TopicSegment topic : topics) {
                TopicView saved = savedBySeq.get(topic.topicSeq());
                if (saved == null) {
                    continue;
                }
                List<AiLayerPort.GateCandidate> candidates = gateCandidatesOf(saved);
                if (candidates.isEmpty()) {
                    // 근거 발화가 있는 항목이 하나도 없다. 부르면 판정할 대상이 없는 호출에
                    // 토큰만 쓴다. 이 주제의 항목은 미판정(NULL)으로 남아 L4 로 넘어가지 않는다.
                    continue;
                }
                candidates.forEach(candidate -> candidateIds.add(candidate.decisionId()));

                GateResult result = aiLayerPort.gate(tenantId, meetingId, topic.topic(), candidates,
                        utterancesOf(topic, byId), participants);
                sink.add(result.run());
                verdicts.addAll(result.verdicts());
            }

            /*
             * 후보 밖 항목의 판정을 한 번 더 버린다.
             *
             * 어댑터가 이미 호출 단위로 같은 검사를 한다(AiLayerHttpAdapter#toGateVerdicts).
             * 그런데 그 검사는 **구현체의 성실함**에 달려 있고, 이 포트는 다른 구현으로 갈릴 수 있다
             * (테스트 가짜 · 나중의 다른 제공자 어댑터). 여기서 막는 것이 저장 직전의 마지막 관문이다.
             *
             * 왜 이렇게까지 하나 — 근거가 없어 **일부러 후보에서 제외한** 항목의 id 로 CONFIRMED 가
             * 오면 그 항목이 확정되어 L4 로 넘어간다. 게이트가 막으려던 상태가 게이트를 통해
             * 만들어지는 경로다.
             */
            List<GateVerdict> scoped = verdicts.stream()
                    .filter(verdict -> candidateIds.contains(verdict.decisionId()))
                    .toList();
            if (scoped.size() != verdicts.size()) {
                log.warn("후보 밖 게이트 판정을 버렸다 — meetingId={} 받음={} 사용={}",
                        meetingId, verdicts.size(), scoped.size());
            }

            int applied = meetingSummaryRepository.applyGateVerdicts(meetingId, scoped);
            if (applied != candidateIds.size()) {
                /*
                 * 판정을 못 받은 항목이 있다. 오류로 올리지 않는다 — 그 항목은 미판정(NULL)으로
                 * 남아 L4 로 넘어가지 않으므로 안전한 방향이고, 여기서 회의 전체를 실패시키면
                 * 항목 하나 때문에 요약까지 못 쓰게 된다. 대신 수를 남겨 게이트 누락을 볼 수 있게 한다.
                 *
                 * ⚠ 이 비교는 후보 밖 판정을 버린 **뒤에** 해야 의미가 있다. 버리기 전에 비교하면
                 * "후보 A 미판정 + 비후보 B 판정"이 수가 같아 경고 없이 지나간다.
                 */
                log.warn("게이트 판정이 대상 수와 다르다 — meetingId={} 대상={} 반영={}",
                        meetingId, candidateIds.size(), applied);
            }
            return new Accumulated<>(null, sink.spent());
        });
        if (!gated.succeeded()) {
            return gated.toAnalysisOutcome(LayerName.L3_5);
        }

        // ── L4 · assignment tuple 추출 (주제마다 한 번) + 산출물 저장 ────────────
        LayerOutcome<Integer> extracted = runLayer(meetingId, runSeq, LayerName.L4, sink -> {
            /*
             * 게이트 반영 **후의 값을 다시 읽는다.** 위에서 받은 판정 목록을 그대로 쓰면
             * 되짚지 못해 반영되지 않은 판정까지 CONFIRMED 로 취급하게 된다 — DB 에는
             * 미판정으로 남은 항목이 tuple 로는 뽑히는, 두 곳이 서로 다른 말을 하는 상태다.
             */
            Map<Integer, TopicView> gatedBySeq = savedTopicsBySeq(companyId, meetingId);

            LocalDate meetingDate = meetingDateOf(meetingId);

            List<TupleRow> rows = new ArrayList<>();
            for (TopicSegment topic : topics) {
                TopicView gatedTopic = gatedBySeq.get(topic.topicSeq());
                if (gatedTopic == null) {
                    continue;
                }
                List<ItemView> confirmed = confirmedItemsOf(gatedTopic);
                if (confirmed.isEmpty()) {
                    // 확정된 항목이 없는 주제는 부르지 않는다. 논의만 있었던 주제에서 배정을
                    // 뽑으려 하면 계층이 없는 담당자를 만들어내거나 빈 결과에 토큰만 쓴다.
                    continue;
                }

                ExtractTuplesResult result = aiLayerPort.extractTuples(
                        tenantId, meetingId, topic.topic(), toConfirmedItems(confirmed),
                        utterancesOf(topic, byId), participants, meetingDate);
                sink.add(result.run());

                rows.addAll(toTupleRows(meetingId, result, confirmed, topic));
            }

            /*
             * rows 가 비어도 저장을 부른다 — 재실행에서 이전 tuple 을 지워야 한다.
             * 건너뛰면 지난 실행의 배정이 남아, 이번에 아무것도 확정되지 않은 회의가
             * 예전 배정을 그대로 들고 있게 된다.
             */
            assignmentTupleRepository.replace(companyId, meetingId, rows);
            return new Accumulated<>(rows.size(), sink.spent());
        });
        if (!extracted.succeeded()) {
            return extracted.toAnalysisOutcome(LayerName.L4);
        }

        // ── L5 · 관점 다변화 검증 (tuple 마다 한 번) + 판정 반영 ─────────────────
        /*
         * L4 **저장 후에** 돈다. 판정을 meeting_assignment_tuple 의 각 행에 적어야 하고,
         * 저장 전에 부르면 응답을 어느 행에 적용할지 순번으로 맞춰야 한다 — L3.5 를 L3 저장
         * 뒤에 두는 것과 정확히 같은 이유다.
         *
         * <h2>왜 tuple 마다 부르나</h2>
         * Python 의 요청이 tuple 단수다. 주제 단위로 묶어 보내려면 응답 판정을 어느 tuple 에
         * 적용할지 되짚는 키가 계약에 새로 필요한데, 그 키가 없어서 겪은 문제가 L4 의
         * toTupleRows 다(근거 발화로 되짚고 있고 한 발화에 항목이 둘이면 포기한다).
         * 같은 자리를 하나 더 만들지 않는다.
         *
         * <h2>판정이 false 여도 행을 지우지 않는다</h2>
         * agree=false 는 "틀렸다"가 아니라 "확신할 수 없다"이다 — 두 관점이 갈렸거나 한쪽이
         * 죽은 것이다. 지우면 사람이 검토할 대상 자체가 사라지고, 그건 검증을 안 한 것보다 나쁘다.
         * 이 판정을 무엇에 쓸지(자동확정 대상에서 제외)는 L7 이 정한다.
         */
        LayerOutcome<Integer> verified = runLayer(meetingId, runSeq, LayerName.L5, sink -> {
            List<StoredTuple> stored = assignmentTupleRepository.findByMeeting(companyId, meetingId);
            if (stored.isEmpty()) {
                // 뽑힌 배정이 없으면 검증할 것이 없다. 계층은 DONE 으로 닫는다 — 실패가 아니라
                // 대상이 0건인 것이고, FAILED 로 두면 ANLZ-02 가 영원히 재시도하게 된다.
                log.info("L5 생략 — 검증할 tuple 이 없다. meetingId={}", meetingId);
                return new Accumulated<>(0, sink.spent());
            }

            /*
             * 검증 재추출에 넘길 재료를 주제 순번으로 찾을 수 있게 만든다.
             *
             * items 는 L4 에 넘긴 것과 **같은 집합**이어야 한다. 다르면 두 관점이 서로 다른
             * 입력을 본 것이라, 불일치가 관점 차이인지 입력 차이인지 구분되지 않는다 —
             * 그러면 disagreementFields 가 원인 조사에 쓸모없어진다.
             */
            Map<Integer, TopicView> gatedBySeq = savedTopicsBySeq(companyId, meetingId);
            Map<Integer, TopicSegment> topicBySeq = new HashMap<>();
            topics.forEach(topic -> topicBySeq.put(topic.topicSeq(), topic));

            LocalDate meetingDate = meetingDateOf(meetingId);

            List<TupleVerification> verifications = new ArrayList<>();
            for (StoredTuple row : stored) {
                TopicSegment topic = topicBySeq.get(row.topicSeq());
                TopicView gatedTopic = gatedBySeq.get(row.topicSeq());
                if (topic == null || gatedTopic == null) {
                    // 저장된 tuple 의 주제를 이번 실행에서 찾지 못했다. 문맥 없이 검증하면
                    // 좁은 시야 재추출이 근거를 못 찾아 전부 notReproduced 로 나오는데,
                    // 그건 tuple 이 의심스럽다는 뜻이 아니라 우리가 재료를 못 준 것이다.
                    log.warn("검증할 주제를 찾지 못해 건너뛴다 — meetingId={} tupleId={} topicSeq={}",
                            meetingId, row.id(), row.topicSeq());
                    continue;
                }

                VerifyTupleResult result = aiLayerPort.verifyTuple(
                        tenantId, meetingId, row.topic(), row.tuple(),
                        toConfirmedItems(confirmedItemsOf(gatedTopic)),
                        utterancesOf(topic, byId), participants, meetingDate);
                // tuple 마다 부르므로 토큰을 누적한다. 중간에 터져도 그때까지의 비용이 남는다.
                sink.add(result.run());

                verifications.add(new TupleVerification(
                        row.id(), result.agree(), result.disagreementFields(),
                        result.verdict(), result.reason(),
                        result.run().modelName(), result.run().promptVersion()));
            }

            int applied = assignmentTupleRepository.applyVerifications(meetingId, verifications);
            if (applied != verifications.size()) {
                // 되짚지 못한 행이 있다. 오류로 올리지 않는다 — 그 행은 미검증(NULL)으로 남고,
                // 미검증은 검토 대상에서 빠지는 것이 아니라 "아직 안 봤다"로 남는다.
                log.warn("L5 판정 반영 수가 대상과 다르다 — meetingId={} 대상={} 반영={}",
                        meetingId, verifications.size(), applied);
            }

            long needsReview = verifications.stream().filter(v -> !v.agree()).count();
            log.info("L5 검증 — meetingId={} tuple {}건 중 검토 대상 {}건",
                    meetingId, verifications.size(), needsReview);
            return new Accumulated<>(applied, sink.spent());
        });
        if (!verified.succeeded()) {
            return verified.toAnalysisOutcome(LayerName.L5);
        }

        /*
         * 참석자 명단(명단 밖 탈출구 제외). L6·L7 이 함께 쓴다 — L1 이 쓰는 것과 같은 집합이고,
         * 탈출구(personId=null)를 넣으면 "명단 안"의 뜻이 흐려진다.
         */
        Set<Long> attendeeMemberIds = attendeeMemberIdsOf(participants);

        // ── L6 · 규칙·모순 검사 (코드 계층 · LLM 아님) ───────────────────────────
        /*
         * 저장된 tuple 을 데이터만 보고 검사한다. 모델을 부르지 않으므로 토큰이 0 이다.
         *
         * <h2>회의 단위로 한 번에 본다</h2>
         * 중복 검사가 tuple 하나만 보고는 성립하지 않는다 — 같은 근거에서 나온 다른 tuple 이
         * 있는지는 전체를 봐야 안다. 그 중복은 드문 사고가 아니라 L2 오버랩의 부산물이다.
         *
         * <h2>모순이 있어도 지우지 않는다</h2>
         * 표시만 하고 남긴다. 지우면 사람이 검토할 대상 자체가 사라진다. 대신 뒤의 L7 이
         * 모순 있는 tuple 을 자동확정하지 않는다 — 게이트는 조이는 방향으로만 쓴다.
         */
        LayerOutcome<Map<Long, List<ConflictType>>> checked = runLayer(meetingId, runSeq, LayerName.L6, sink -> {
            List<StoredTuple> stored = assignmentTupleRepository.findByMeeting(companyId, meetingId);
            if (stored.isEmpty()) {
                return new Accumulated<>(Map.of(), LayerRun.empty());
            }

            Map<Long, List<ConflictType>> conflicts = conflictDetector.detect(
                    stored, utterances, attendeeMemberIds, meetingDateOf(meetingId));

            /*
             * 모순이 없는 행도 반영한다 — 검사 시각이 찍혀야 "검사했고 깨끗함"이 되고,
             * 안 찍으면 "아직 안 봄"으로 남는다(V5.14 주석).
             */
            List<TupleConflicts> rows = conflicts.entrySet().stream()
                    .map(entry -> new TupleConflicts(entry.getKey(), entry.getValue()))
                    .toList();
            int applied = assignmentTupleRepository.applyConflicts(meetingId, rows);
            if (applied != rows.size()) {
                log.warn("L6 결과 반영 수가 대상과 다르다 — meetingId={} 대상={} 반영={}",
                        meetingId, rows.size(), applied);
            }
            // 토큰을 쓰지 않는 코드 계층이다. 0 을 채우는 것이 아니라 실제로 0 이다.
            return new Accumulated<>(conflicts, LayerRun.empty());
        });
        if (!checked.succeeded()) {
            return checked.toAnalysisOutcome(LayerName.L6);
        }

        // ── L7 · 자동확정 게이트 (코드 계층 · LLM 아님) ─────────────────────────
        /*
         * 계층의 마지막이다. 여기서 가른 두 묶음이 곧 검토 화면의
         * 「AI 확신도 높음」과 「AI 확인 필요」다.
         *
         * <h2>action 을 만들지 않는다 — 만드는 것은 다음 단계(DIST)다</h2>
         * 여기서 액션까지 만들면 게이트 판정과 분배가 한 잠금 안에 묶인다. 판정은 우리 데이터로
         * 언제든 다시 낼 수 있지만 액션은 이미 만들어진 것을 회수해야 하므로, 두 단계를 나눠
         * 각자의 잠금과 상태를 갖게 한다 — 분배가 실패해도 게이트 결과는 DONE 으로 남는다.
         *
         * <h2>L6 뒤에 도는 이유</h2>
         * 모순이 있으면 신호 넷과 무관하게 자동확정하지 않는다. 순서를 뒤집으면 모순을 모르는
         * 채로 게이트를 통과시키게 된다.
         */
        LayerOutcome<Map<Long, AutoConfirmGate.Verdict>> gated7 = runLayer(meetingId, runSeq, LayerName.L7, sink -> {
            List<StoredTuple> stored = assignmentTupleRepository.findByMeeting(companyId, meetingId);
            if (stored.isEmpty()) {
                log.info("L7 생략 — 판정할 tuple 이 없다. meetingId={}", meetingId);
                return new Accumulated<>(Map.of(), LayerRun.empty());
            }

            Map<Long, AutoConfirmGate.Verdict> verdicts = autoConfirmGate.evaluate(
                    stored, checked.value(), utterances, attendeeMemberIds);

            List<TupleGateVerdict> rows = verdicts.entrySet().stream()
                    .map(entry -> new TupleGateVerdict(
                            entry.getKey(), entry.getValue().signals(), entry.getValue().autoConfirmed()))
                    .toList();
            int applied = assignmentTupleRepository.applyGateVerdicts(meetingId, rows);
            if (applied != rows.size()) {
                log.warn("L7 판정 반영 수가 대상과 다르다 — meetingId={} 대상={} 반영={}",
                        meetingId, rows.size(), applied);
            }

            return new Accumulated<>(verdicts, LayerRun.empty());
        });
        if (!gated7.succeeded()) {
            return gated7.toAnalysisOutcome(LayerName.L7);
        }

        // ── DIST · 액션 분배 (코드 단계 · LLM 아님) ──────────────────────────────
        /*
         * tuple 을 action 으로 넘긴다. 파이프라인 산출물이 **처음 A 밖으로 나가는 자리**다.
         * 08/05 확정대로 분석 직후에 만들고, 보드 노출은 review_status 가 막는다.
         *
         * <h2>두 묶음을 모두 넘긴다</h2>
         * 게이트를 통과한 것만 만들면 「AI 확인 필요」 묶음이 검토 화면에 아예 나타나지 않는다 —
         * 사람이 봐야 하는 쪽이 그쪽인데 볼 방법이 없어진다.
         *
         * <h2>계층이 아니지만 runLayer 로 감싼다</h2>
         * 잠금이 필요하다. SQS 는 at-least-once 라 같은 회의가 두 번 들어오는 일이 언젠가
         * 반드시 있고, 그때 만들어지는 것은 사람 보드에 두 번 꽂히는 액션이다. 상태를 남기는
         * 이유도 같다 — "분석은 완료인데 액션이 없는" 회의가 왜 그런지 CAP-06 으로 보여야 한다.
         */
        LayerOutcome<Integer> distributed = runLayer(meetingId, runSeq, LayerName.DIST, sink ->
                // 토큰을 쓰지 않는다. 모델을 부르지 않으므로 실제로 0 이다.
                new Accumulated<>(
                        tupleDistributionService.distribute(companyId, meetingId, gated7.value()),
                        LayerRun.empty()));
        if (!distributed.succeeded()) {
            return distributed.toAnalysisOutcome(LayerName.DIST);
        }

        // 분석이 끝까지 성공했다. 이 실행이 쓴 토큰을 미터링 원장에 1회 기록한다.
        recordTokenUsage(companyId, meetingId, runSeq, java.util.Arrays.asList(
                attributed.run(), resolved.run(), segmented.run(), summarized.run(),
                gated.run(), extracted.run(), verified.run(), checked.run(),
                gated7.run(), distributed.run()));

        long autoConfirmed = gated7.value().values().stream()
                .filter(AutoConfirmGate.Verdict::autoConfirmed).count();
        log.info("분석 완료 — meetingId={} 주제 {}개 항목 {}건 tuple {}건 검증 {}건 자동확정 {}건 액션 {}건",
                meetingId, decisions.size(),
                decisions.stream().mapToInt(d -> d.items().size()).sum(),
                extracted.value(), verified.value(), autoConfirmed, distributed.value());
        return AnalysisOutcome.done(decisions.size());
    }

    /*
     * 분석이 끝까지 성공한 이 실행의 토큰 사용량을 미터링 원장에 1회 기록한다(usage-based 과금).
     *
     * 멱등키 = runSeq. 실행마다 유일하고, force 재실행은 새 runSeq 라 새 과금 이벤트가 된다.
     * SQS 중복 등으로 같은 실행이 두 번 기록돼도 원장의 job_id UNIQUE 가 이중청구를 막는다.
     *
     * model 은 이 실행에서 실제로 부른 LLM 계층의 모델명(원가 계수용)이다. 코드 계층(L1·L6·L7·DIST)은
     * 토큰 0·모델 없음이라 건너뛴다. LLM 계층이 하나도 안 돌았으면 기록할 원가 축이 없어 생략한다.
     *
     * teamId 는 회의 소속 팀이다(meetingTeamProvider). meeting.team_id 가 NULL 인 OWNER 개설
     * 회의나 회의 행을 못 읽은 경우엔 null 로 남아 회사 단위로만 집계된다 — 부서 breakdown 없이
     * 회사 실측만 남는 정상 경로다. 조회 자체도 best-effort 다(try 안에서 함께 삼킨다).
     *
     * ⚠ 기록 실패로 분석을 실패시키지 않는다. 분석은 이미 완료됐고 토큰도 이미 썼다 — 예외를 올리면
     * 완료된 회의가 FAILED 로 뒤집혀 ANLZ-02 가 재분석하고, 그게 진짜 이중과금이다. 미터링 누락은
     * 원장 한 건 빠지는 것이라 크게 로깅하고 넘어간다(대시보드 소진율로 관측 가능).
     */
    private void recordTokenUsage(long companyId, long meetingId, long runSeq, List<LayerRun> runs) {
        LayerRun total = LayerRun.empty();
        String model = null;
        for (LayerRun run : runs) {
            if (run == null) {
                continue;
            }
            total = total.plus(run);
            if (model == null && run.modelName() != null && !run.modelName().isBlank()) {
                model = run.modelName();
            }
        }
        if (model == null) {
            log.warn("토큰 미터링 기록 생략 — LLM 계층 토큰이 없어 모델을 정할 수 없다. meetingId={} runSeq={}",
                    meetingId, runSeq);
            return;
        }
        String jobId = "meeting-" + meetingId + "-run-" + runSeq;
        try {
            // teamId 조회도 try 안에 둔다 — 실패해도 분석은 이미 완료됐고, 여기서 예외를 올리면
            // 완료된 회의가 FAILED 로 뒤집혀 재분석·이중과금이 된다(아래 catch 와 같은 이유).
            Long teamId = meetingTeamProvider.teamIdOf(meetingId).orElse(null);
            recordTokenUsagePort.record(new RecordTokenUsageCommand(
                    companyId, teamId, meetingId, jobId,
                    total.tokensIn(), total.tokensOut(), model));
        } catch (RuntimeException e) {
            log.error("토큰 미터링 기록 실패 — 분석은 완료됨, 원장만 누락. meetingId={} runSeq={} jobId={}",
                    meetingId, runSeq, jobId, e);
        }
    }

    /*
     * 참석자 명단에서 실제 사람만 남긴다.
     *
     * 명단 밖 탈출구(personId=null)를 제외하는 이유는 L1 과 같다 — "명단 안에 있는가"를
     * 묻는 자리에 탈출구가 섞이면 그 질문의 뜻이 흐려진다.
     */
    private Set<Long> attendeeMemberIdsOf(List<AiLayerPort.Participant> participants) {
        return participants.stream()
                .map(AiLayerPort.Participant::personId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());
    }

    /*
     * 상대 표현("다음 주까지")의 기준일이다. 없으면 계층이 상대 표현을 계산하지 않는다 —
     * 오늘 날짜로 대체하지 않는다. 재실행은 회의 몇 주 뒤일 수도 있고, 그때 오늘로 계산하면
     * 그럴듯하게 틀린 마감이 보드에 꽂힌다.
     *
     * L4 와 L5 가 같은 값을 써야 한다. L5 의 좁은 시야 재추출도 기한을 다시 계산하는데,
     * 기준일이 갈리면 dueDate 가 그것 때문에 달라지고 그 차이가 "관점이 갈렸다"로 기록된다.
     */
    private LocalDate meetingDateOf(long meetingId) {
        LocalDate meetingDate = meetingDateProvider.meetingDateOf(meetingId).orElse(null);
        if (meetingDate == null) {
            log.warn("회의 날짜를 읽지 못해 상대 기한을 계산하지 않는다. meetingId={}", meetingId);
        }
        return meetingDate;
    }

    /*
     * 계층 하나를 잠그고 → 돌리고 → 상태를 닫는다. 계층이 늘어도 이 흐름은 한 곳에 남는다.
     *
     * 잠금 실패는 오류가 아니다. 중복 수신이 걸러진 정상 동작이고, 그때 이미 다른 실행이
     * 같은 계층을 돌고 있으므로 여기서는 조용히 물러난다.
     */
    private <T> LayerOutcome<T> runLayer(long meetingId, long runSeq, LayerName layer, LayerCall<T> call) {
        LockOutcome acquired = analysisLayerRepository.tryLock(meetingId, layer, runSeq);
        LockResult lock = acquired.result();
        /*
         * 이 잠금의 주인 번호(#212). 심장과 상태 기록에 그대로 실어, **뺏긴 뒤의 쓰기**를
         * 저장소가 걸러낼 수 있게 한다 — 회수(#177)가 생기면서 잠금은 도중에 주인이 바뀔 수 있다.
         */
        int attempt = acquired.attempt();
        if (lock == LockResult.ALREADY_RUNNING) {
            log.info("계층 잠금 실패 — 이미 실행 중이다. meetingId={} layer={}", meetingId, layer.wireValue());
            return LayerOutcome.locked();
        }
        if (lock == LockResult.SUPERSEDED) {
            /*
             * 더 나중에 시작한 실행이 있다. **여기서 멈추는 것이 이 수정의 전부다**(#134) —
             * 계속 돌면 옛 입력으로 만든 결과가 최신 결과 위에 덮인다.
             *
             * FAILED 로 남기지 않는다. 실패가 아니라 순서가 정해진 것이고, FAILED 로 두면
             * ANLZ-02 가 이 계층을 재개 대상으로 보고 오래된 실행을 되살린다.
             */
            log.info("계층 실행 물러남 — 더 나중 실행이 있다. meetingId={} layer={}",
                    meetingId, layer.wireValue());
            return LayerOutcome.superseded();
        }
        /*
         * 실패해도 그때까지 쓴 토큰이 남아야 한다. L3 는 주제마다 부르므로 5개 중 3번째에서
         * 터져도 앞의 2번은 이미 과금됐다 — 그걸 0 으로 기록하면 QLTY-03 이 실제보다 싼
         * 기준선을 보여주고, 그 숫자로 특화 모델 전환의 손익분기점을 계산하게 된다.
         */
        UsageSink sink = new UsageSink(() -> heartbeat(meetingId, layer, attempt));
        try {
            Accumulated<T> accumulated = call.execute(sink);
            analysisLayerRepository.markDone(meetingId, layer, attempt, accumulated.run());
            return LayerOutcome.success(accumulated.value(), accumulated.run());
        } catch (AiLayerException e) {
            // 계층이 던진 분류를 그대로 남긴다. 여기서 다시 판정하면 Python 과 두 곳에서
            // 재시도 여부를 정하게 되고, 한쪽만 고쳐지는 상태가 만들어진다.
            log.warn("계층 실패 — meetingId={} layer={} code={} retryable={} 사용토큰={}/{}",
                    meetingId, layer.wireValue(), e.getErrorCode(), e.isRetryable(),
                    sink.spent().tokensIn(), sink.spent().tokensOut(), e);
            analysisLayerRepository.markFailed(
                    meetingId, layer, attempt, e.getErrorCode(), e.getMessage(), sink.spent());
            return LayerOutcome.failure(e.getErrorCode(), e.getMessage(), e.isRetryable());
        } catch (RuntimeException e) {
            // 우리 코드의 버그다. 제공자 실패와 섞으면 "재시도하면 되는 것"으로 오분류되어
            // 같은 버그를 세 번 돌린다.
            log.error("계층 실행 중 내부 오류 — meetingId={} layer={}", meetingId, layer.wireValue(), e);
            analysisLayerRepository.markFailed(
                    meetingId, layer, attempt, "ORCHESTRATION_ERROR", e.toString(), sink.spent());
            return LayerOutcome.failure("ORCHESTRATION_ERROR", e.toString(), false);
        }
    }

    /*
     * 이 계층을 잡은 실행이 아직 살아 있다고 알린다(#177).
     *
     * **실패해도 계층을 세우지 않는다.** 심장 기록은 부기(簿記)이고, 그것 때문에 계층이 통째로
     * 실패하면 잠금을 되찾으려고 만든 장치가 오히려 분석을 죽인다. 여기서 잡지 않으면 runLayer
     * 의 catch 가 이 예외를 우리 코드의 버그(ORCHESTRATION_ERROR)로 분류하게 된다 —
     * 그건 사실도 아니고, 그 계층이 실제로 한 일까지 함께 실패로 기록된다.
     *
     * 한 번 못 찍는 것의 대가는 작다. 다음 호출에서 다시 찍히고, 유예(5분)가 그 사이를 덮는다.
     *
     * @param attempt 내가 잡은 잠금의 주인 번호(#212). 뺏긴 뒤라면 저장소가 무시한다
     */
    private void heartbeat(long meetingId, LayerName layer, int attempt) {
        try {
            analysisLayerRepository.heartbeat(meetingId, layer, attempt);
        } catch (RuntimeException e) {
            log.warn("계층 심장 기록 실패 — 분석은 계속한다. meetingId={} layer={}",
                    meetingId, layer.wireValue(), e);
        }
    }

    /*
     * 이 오케스트레이터가 도는 계층이 전부 DONE 인가.
     *
     * 계층별 재개(ANLZ-02)는 아직 아니다 — L2 산출물을 따로 저장하지 않으므로 L3 만 이어서
     * 돌릴 수 없다. 그래서 지금은 "전부 완료면 생략, 아니면 처음부터"만 구분한다.
     *
     * ⚠ **공개해 두는 이유가 있다.** ANLZ-01 도 "이미 완료"를 판정해 409 를 주는데, 그쪽이
     * 자기만의 기준을 쓰면 두 판정이 갈린다. 실제로 갈렸던 모양이 이렇다 — 계층 상태 행이
     * 전부 DONE 이면 완료로 보는 기준을 쓰면, L2·L3 만 돌던 시절에 분석된 회의는 그 두 행이
     * DONE 이라 "완료"가 되고, 뒤에 붙은 L1.5·L3.5·L4 는 force 없이는 영원히 돌지 않는다.
     * 계층을 붙일 때마다 조용히 재발하므로 판정을 이 한 곳에만 둔다({@link #RUN_LAYERS}).
     */
    public boolean isFullyAnalyzed(long meetingId) {
        Set<LayerName> done = analysisLayerRepository.findStates(meetingId).stream()
                .filter(state -> state.status() == LayerStatus.DONE)
                .map(AnalysisLayerRepository.LayerState::layer)
                .collect(Collectors.toSet());
        return done.containsAll(RUN_LAYERS);
    }

    /*
     * 저장된 주제를 topicSeq 로 찾을 수 있게 만든다.
     *
     * 요약이 없으면 우리 버그다 — 바로 앞에서 L3 가 저장했다. 던져서 ORCHESTRATION_ERROR 로
     * 분류되게 둔다. 빈 목록으로 넘기면 게이트가 "판정 대상 없음"으로 조용히 지나가고,
     * 뒤이어 L4 도 아무것도 못 뽑는데 둘 다 DONE 으로 기록된다.
     */
    private Map<Integer, TopicView> savedTopicsBySeq(long companyId, long meetingId) {
        MeetingSummaryView view = meetingSummaryRepository.findByMeeting(companyId, meetingId)
                .orElseThrow(() -> new IllegalStateException(
                        "L3 가 저장한 요약을 찾을 수 없습니다. meetingId=" + meetingId));

        Map<Integer, TopicView> bySeq = new HashMap<>();
        view.topics().forEach(topic -> bySeq.put(topic.topicSeq(), topic));
        return bySeq;
    }

    /*
     * 게이트에 넘길 판정 대상을 고른다.
     *
     * 근거 발화가 없는 항목은 **제외한다.** Python 쪽 GateCandidate 가 non-nullable 로 받아
     * 실으면 422 이기도 하지만, 더 중요한 건 근거를 확인할 수 없는 항목이 확정으로 올라가지
     * 않는 쪽이 안전하다는 것이다. 제외된 항목은 gate_status 가 NULL 로 남는다.
     */
    private List<AiLayerPort.GateCandidate> gateCandidatesOf(TopicView topic) {
        return topic.items().stream()
                .filter(item -> item.id() != null && item.evidenceUtteranceId() != null)
                .map(item -> new AiLayerPort.GateCandidate(
                        item.id(), item.itemType(), item.content(), item.evidenceUtteranceId()))
                .toList();
    }

    /*
     * L4 로 넘길 항목을 고른다 — gate_status 가 CONFIRMED 인 것만이다.
     *
     * 미판정(NULL)과 DISCUSSED 를 같이 뺀다. 넘어가지 않는 건 같지만 이유가 다르고,
     * 그 구분은 gate_status 컬럼에 그대로 남아 있다.
     */
    private List<ItemView> confirmedItemsOf(TopicView topic) {
        return topic.items().stream()
                .filter(item -> GateStatus.fromNullable(item.gateStatus()) == GateStatus.CONFIRMED)
                .toList();
    }

    private List<AiLayerPort.ConfirmedItem> toConfirmedItems(List<ItemView> confirmed) {
        return confirmed.stream()
                .map(item -> new AiLayerPort.ConfirmedItem(
                        item.itemType(),
                        // 값으로 실어 보낸다. 여기서 CONFIRMED 를 지어 채우면 게이트가 뚫린다 —
                        // 위 필터를 지난 항목만 오므로 이 값은 항상 CONFIRMED 다.
                        GateStatus.CONFIRMED,
                        item.content(),
                        item.evidenceUtteranceId() != null
                                ? List.of(item.evidenceUtteranceId())
                                : List.of()))
                .toList();
    }

    /*
     * tuple 을 저장 행으로 옮기면서 **어느 확정 항목에서 나왔는지** 되짚는다.
     *
     * 되짚는 키는 근거 발화 id 다. L4 응답에 항목 키가 없고(계약이 그렇다), 대신 우리가 각
     * 항목의 근거 발화를 실어 보내면 Python 이 tuple 의 근거를 그 안에서만 고르게 좁힌다.
     * 그래서 근거 발화가 곧 연결선이 된다.
     *
     * 한 근거 발화에 확정 항목이 둘 이상 걸리면 **연결하지 않고 null 로 둔다.** 둘 중 하나를
     * 고르면 그 tuple 은 엉뚱한 결정에서 나온 것으로 기록되고, 사람이 검토할 때 근거 항목을
     * 눌러도 다른 내용이 나온다 — 근거를 모르는 것보다 나쁘다.
     */
    private List<TupleRow> toTupleRows(long meetingId, ExtractTuplesResult result,
                                       List<ItemView> confirmed, TopicSegment topic) {
        Map<Long, Long> decisionIdByEvidence = new HashMap<>();
        Set<Long> ambiguous = new HashSet<>();

        for (ItemView item : confirmed) {
            Long evidence = item.evidenceUtteranceId();
            if (evidence == null) {
                continue;
            }
            if (decisionIdByEvidence.put(evidence, item.id()) != null) {
                ambiguous.add(evidence);
            }
        }
        ambiguous.forEach(decisionIdByEvidence::remove);

        List<TupleRow> rows = new ArrayList<>();
        for (AssignmentTuple tuple : result.tuples()) {
            Long decisionId = decisionIdByEvidence.get(tuple.evidenceUtteranceId());
            if (decisionId == null) {
                log.info("tuple 의 근거 항목을 되짚지 못해 연결 없이 저장한다 — meetingId={} 주제={} 근거={}",
                        meetingId, topic.topic(), tuple.evidenceUtteranceId());
            }
            rows.add(new TupleRow(tuple, decisionId, topic.topicSeq(), topic.topic(),
                    result.run().modelName(), result.run().promptVersion()));
        }
        return rows;
    }

    /*
     * L1.5 결과를 발화 사본에 주석으로 붙인다.
     *
     * **치환이 아니라 덧붙이기다.** "그거"를 해소된 표현으로 바꿔치면 원문이 사라져, 해소가
     * 틀렸을 때 뒤 계층이 무엇을 보고 판단했는지 되짚을 수 없다. 덧붙이면 최악이 모델이
     * 주석 문구를 인용하는 것이고, 그건 사람이 보면 바로 알아챈다.
     *
     * 원문에 surface 가 실제로 있는지 다시 확인한다. Python 후처리도 같은 검사를 하지만,
     * 계약이 갈렸을 때 엉뚱한 발화에 주석이 붙는 것보다 안 붙는 편이 낫다.
     */
    private List<Utterance> annotate(List<Utterance> utterances, List<ResolvedReference> references,
                                     List<AiLayerPort.Participant> participants) {
        if (references == null || references.isEmpty()) {
            return utterances;
        }

        Map<Long, String> nameByPersonId = new HashMap<>();
        participants.forEach(participant -> {
            if (participant.personId() != null) {
                nameByPersonId.put(participant.personId(), participant.name());
            }
        });

        Map<Long, List<ResolvedReference>> byUtteranceId = new HashMap<>();
        for (ResolvedReference reference : references) {
            if (reference.utteranceId() != null && reference.isAnnotatable()) {
                byUtteranceId.computeIfAbsent(reference.utteranceId(), id -> new ArrayList<>())
                        .add(reference);
            }
        }
        if (byUtteranceId.isEmpty()) {
            return utterances;
        }

        List<Utterance> annotated = new ArrayList<>(utterances.size());
        for (Utterance utterance : utterances) {
            List<ResolvedReference> matched = byUtteranceId.get(utterance.utteranceId());
            annotated.add(matched == null
                    ? utterance
                    : new Utterance(utterance.utteranceId(), utterance.speakerMemberId(),
                            utterance.startOffsetMs(), utterance.endOffsetMs(),
                            withAnnotations(utterance, matched, nameByPersonId)));
        }
        return annotated;
    }

    private String withAnnotations(Utterance utterance, List<ResolvedReference> references,
                                   Map<Long, String> nameByPersonId) {
        String text = utterance.text();
        if (text == null || text.isBlank()) {
            return text;
        }

        StringBuilder annotated = new StringBuilder(text);
        for (ResolvedReference reference : references) {
            if (!text.contains(reference.surface())) {
                log.debug("원문에 없는 지시 표현이라 주석을 붙이지 않는다 — utteranceId={} surface={}",
                        utterance.utteranceId(), reference.surface());
                continue;
            }
            String target = targetOf(reference, nameByPersonId);
            if (target == null) {
                continue;
            }
            annotated.append(" [지시어 \"").append(reference.surface()).append("\" → ")
                    .append(target).append(']');
        }
        return annotated.toString();
    }

    /*
     * 주석에 적을 대상을 고른다.
     *
     * PERSON 인데 명단 밖(resolvedPersonId=null)이면 이름을 적지 않는다. "사람을 가리키지만
     * 누군지 모른다"를 이름 없이 남겨야 L4 가 그걸 담당자로 쓰지 않는다 — 여기서 resolvedText 의
     * 표현("그 팀 분")을 이름처럼 적으면 담당자 후보로 읽힐 여지가 생긴다.
     */
    private String targetOf(ResolvedReference reference, Map<Long, String> nameByPersonId) {
        if (reference.referenceType() == ReferenceType.PERSON) {
            return reference.resolvedPersonId() != null
                    ? nameByPersonId.get(reference.resolvedPersonId())
                    : null;
        }
        return reference.resolvedText();
    }

    /*
     * 주제에 넘길 발화를 고른다. TopicSegment.utteranceIds 를 쓴다 — 고유 구간(start·end)이
     * 아니라 **오버랩이 얹힌 목록**이어야 경계 발화를 L3 가 양쪽에서 볼 수 있다.
     *
     * 목록에 없는 id 는 조용히 건너뛴다. 그런 id 가 왔다면 L2 응답과 우리가 넘긴 발화가
     * 어긋난 것인데, 여기서 예외를 던지면 회의 전체가 실패한다. 계층 하나의 결과가 조금
     * 빈 것이 낫다.
     */
    private List<Utterance> utterancesOf(TopicSegment topic, Map<Long, Utterance> byId) {
        return topic.utteranceIds().stream()
                .map(byId::get)
                .filter(java.util.Objects::nonNull)
                .toList();
    }

    /* 회의 전체 개요는 주제 요약을 이어 붙여 만든다. 회의 단위 요약 계층은 아직 없다. */
    private void appendOverview(StringBuilder overview, TopicSegment topic, String summary) {
        if (summary == null || summary.isBlank()) {
            return;
        }
        if (!overview.isEmpty()) {
            overview.append("\n\n");
        }
        overview.append("· ").append(topic.topic()).append('\n').append(summary.strip());
    }

    @FunctionalInterface
    private interface LayerCall<T> {
        Accumulated<T> execute(UsageSink sink);
    }

    /*
     * 계층이 쓴 토큰을 모으는 자리. 성공·실패 양쪽에서 같은 값을 쓰려면 호출 바깥에 있어야 한다 —
     * 람다 안의 지역변수로 두면 예외가 나갈 때 그 값이 함께 사라진다(그게 원래 버그였다).
     */
    private static final class UsageSink {

        private LayerRun spent = LayerRun.empty();

        /*
         * 모델 호출 하나가 끝날 때마다 부를 것(#177). 계층 경계에서만 심장을 찍으면 L5 처럼
         * tuple 마다 도는 계층이 한 번의 갱신 사이에 몇 분을 보내고, 그 시간을 견디려고 유예를
         * 늘리면 진짜 죽은 잠금이 그만큼 늦게 풀린다.
         */
        private final Runnable heartbeat;

        private UsageSink(Runnable heartbeat) {
            this.heartbeat = heartbeat;
        }

        void add(LayerRun run) {
            /*
             * 심장은 토큰이 있든 없든 찍는다. sink.add 는 계층이 **한 걸음 나아갈 때마다**
             * 불리는 유일한 자리이고, 그게 곧 "아직 살아 있다"의 뜻이다.
             */
            heartbeat.run();
            if (run != null) {
                spent = spent.plus(run);
            }
        }

        LayerRun spent() {
            return spent;
        }
    }

    /* 계층 호출의 산출물 + 그 호출에 든 비용. */
    private record Accumulated<T>(T value, LayerRun run) {
    }

    /* 계층 하나의 실행 결과. 성공·이미 실행 중·밀림·실패 넷을 구분한다. */
    private record LayerOutcome<T>(T value, LayerRun run, boolean succeeded, boolean alreadyRunning,
                                   boolean stale, String errorCode, String errorMessage,
                                   boolean retryable) {

        static <T> LayerOutcome<T> success(T value, LayerRun run) {
            return new LayerOutcome<>(value, run, true, false, false, null, null, false);
        }

        /*
         * 이름이 컴포넌트(alreadyRunning)와 겹치면 안 된다 — 같은 이름의 무인자 메서드를
         * 레코드가 접근자 재정의로 보고, static 이면 "invalid accessor" 로 컴파일이 막힌다.
         */
        static <T> LayerOutcome<T> locked() {
            return new LayerOutcome<>(null, LayerRun.empty(), false, true, false, null, null, false);
        }

        /* 더 나중에 시작한 실행이 있어 물러난 것이다. 실패와 섞으면 재개가 오래된 실행을 되살린다. */
        static <T> LayerOutcome<T> superseded() {
            return new LayerOutcome<>(null, LayerRun.empty(), false, false, true, null, null, false);
        }

        static <T> LayerOutcome<T> failure(String errorCode, String message, boolean retryable) {
            return new LayerOutcome<>(null, LayerRun.empty(), false, false, false,
                    errorCode, message, retryable);
        }

        AnalysisOutcome toAnalysisOutcome(LayerName layer) {
            if (alreadyRunning) {
                return AnalysisOutcome.alreadyRunning(layer);
            }
            if (stale) {
                return AnalysisOutcome.superseded(layer);
            }
            return AnalysisOutcome.failed(layer, errorCode, errorMessage, retryable);
        }
    }
}
