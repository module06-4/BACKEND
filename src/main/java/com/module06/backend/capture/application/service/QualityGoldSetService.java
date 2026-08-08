package com.module06.backend.capture.application.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.module06.backend.capture.application.port.out.ActionReviewQueryPort;
import com.module06.backend.capture.application.port.out.ActionReviewQueryPort.ReviewAction;
import com.module06.backend.capture.application.port.out.QualityGoldSetRepository;
import com.module06.backend.capture.application.port.out.QualityGoldSetRepository.FreezeCommand;
import com.module06.backend.capture.application.port.out.QualityGoldSetRepository.GoldSetView;
import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase;
import com.module06.backend.capture.exception.CaptureErrorCode;
import com.module06.backend.global.exception.BusinessException;

/*
 * QLTY-01 · gold set 등록.
 *
 * <h2>동결이 이 API 의 본체다</h2>
 * 요청 본문은 {@code meetingId} 와 {@code note} 뿐이다 — 정답 라벨을 따로 받지 않는다. 그 회의의
 * **지금 상태가 곧 정답**이기 때문이다. 사람이 검토 화면에서 전량 확인한 값을 그 순간 그대로
 * 떠서 얼린다.
 *
 * <h2>미검토가 남아 있으면 얼리지 않는다</h2>
 * PENDING 인 액션은 **AI 가 낸 값 그대로**다. 그걸 함께 얼리면 모델의 출력이 정답지에 들어가
 * 자기 자신을 채점하게 된다 — precision 이 실제보다 높게 나오고, 그 숫자로 프롬프트 개선을
 * 판단하게 된다. 측정 장치가 측정 대상을 베끼는 셈이다.
 *
 * <h2>기존 버전을 고치지 않는다</h2>
 * 재라벨링은 version 을 올린 새 행이다. 기존 행을 갱신하면 그걸로 잰 이전 측정치를 재현할 수
 * 없어져 동결의 목적 자체가 무너진다(V5.11 주석).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class QualityGoldSetService implements RegisterGoldSetUseCase {

    private static final String STATUS_PENDING = "PENDING";

    private final QualityGoldSetRepository qualityGoldSetRepository;
    private final ActionReviewQueryPort actionReviewQueryPort;
    private final MeetingAccessGuard meetingAccessGuard;
    private final ObjectMapper objectMapper;

    /*
     * **트랜잭션을 두지 않는다.** 저장이 UNIQUE(meeting_id, version) 충돌로 떨어지는 것을 여기서
     * 잡아 409 로 옮기는데, 트랜잭션 안이면 그 예외를 잡아도 커밋에서 다시 터진다
     * (AnalysisRunPersistenceAdapter 와 같은 자리다).
     */
    @Override
    public GoldSetRegistered register(RegisterGoldSetCommand command) {
        meetingAccessGuard.requireAccessible(command.companyId(), command.meetingId());

        List<ReviewAction> actions =
                actionReviewQueryPort.findByMeeting(command.companyId(), command.meetingId(), null);
        if (actions.isEmpty()) {
            // 얼릴 것이 없다. 빈 정답지는 precision 의 분모를 0 으로 만들어 지표를 못 낸다.
            throw new BusinessException(CaptureErrorCode.GOLD_SET_NO_ACTIONS);
        }

        long pending = actions.stream()
                .filter(action -> STATUS_PENDING.equals(action.reviewStatus()))
                .count();
        if (pending > 0) {
            /*
             * 검토가 안 끝났다. 그대로 얼리면 AI 출력이 정답지에 들어가 **모델이 자기 자신을
             * 채점하게 된다** — 그 숫자로 개선을 판단하면 방향이 통째로 틀린다.
             */
            log.info("gold set 등록 거절 — 미검토 액션이 남아 있다. meetingId={} 미검토={}건",
                    command.meetingId(), pending);
            throw new BusinessException(CaptureErrorCode.GOLD_SET_NOT_FULLY_REVIEWED);
        }

        int version = qualityGoldSetRepository.latestVersionOf(command.meetingId()) + 1;

        try {
            GoldSetView frozen = qualityGoldSetRepository.freeze(new FreezeCommand(
                    command.companyId(),
                    command.meetingId(),
                    version,
                    labeledActionsJson(actions),
                    // 결정·논의 정답(L3·L3.5 채점용)은 아직 안 얼린다 — 그 라벨을 만드는 화면이
                    // 없어서 지금 넣으면 AI 출력을 정답으로 굳히는 것과 같다.
                    null,
                    command.requestedBy(),
                    command.note()));

            log.info("gold set 동결 — meetingId={} version={} 액션={}건",
                    command.meetingId(), version, actions.size());

            return new GoldSetRegistered(frozen.id(), version, actions.size(), frozen.frozenAt());
        } catch (DataIntegrityViolationException e) {
            /*
             * 같은 버전을 동시에 등록했다. 재시도하지 않는다 — 먼저 얼린 쪽이 이 회의의 그
             * 버전이고, 이쪽이 다시 시도해 다음 버전을 만들면 **같은 라벨의 정답지가 두 벌**
             * 생겨 어느 것으로 잰 수치인지 알 수 없게 된다.
             */
            log.info("gold set 등록 경합 — 같은 버전을 다른 요청이 먼저 얼렸다. meetingId={} version={}",
                    command.meetingId(), version);
            throw new BusinessException(CaptureErrorCode.GOLD_SET_ALREADY_FROZEN);
        }
    }

    /*
     * 정답 라벨을 만든다 — 담당자·기한·근거 발화다(V5.11 컬럼 주석).
     *
     * 제목을 함께 담는 이유는 사람이 나중에 이 정답지를 눈으로 확인할 때 필요해서다. 반대로
     * 게이트 신호나 review_status 는 담지 않는다 — **그건 AI 가 낸 값**이고, 정답지에 섞이면
     * 채점할 때 정답과 예측이 같은 파일 안에 있게 된다.
     */
    private String labeledActionsJson(List<ReviewAction> actions) {
        List<Map<String, Object>> labeled = actions.stream()
                .map(action -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("actionId", action.actionId());
                    entry.put("title", action.title());
                    entry.put("assigneeMemberId", action.assigneeMemberId());
                    entry.put("dueDate", action.dueDate() != null ? action.dueDate().toString() : null);
                    entry.put("evidenceTranscriptId",
                            action.evidence() != null ? action.evidence().transcriptId() : null);
                    // 사람이 반려한 것도 정답이다 — "이건 액션이 아니다"가 그 회의의 정답이고,
                    // 빼면 hallucination 을 잡았는지 채점할 수 없다.
                    entry.put("rejected", "REJECTED".equals(action.reviewStatus()));
                    /*
                     * 사람이 직접 넣은 것인가. **recall 의 분모가 이 값에 걸린다** — AI 가 안
                     * 만들어서 손으로 넣은 액션이 "놓친 것"이다. 스냅샷에 없으면 채점할 때
                     * 현재 review_log 를 다시 봐야 하고, 그 순간 동결이 무의미해진다.
                     */
                    entry.put("manual", action.manual());
                    return entry;
                })
                .toList();

        try {
            return objectMapper.writeValueAsString(labeled);
        } catch (JacksonException e) {
            /*
             * 여기서 대체값을 쓰지 않는다. 라벨 JSON 이 깨진 정답지는 **정답지가 아니고**,
             * 빈 배열로 얼리면 그걸로 잰 precision 이 조용히 0 이 된다. 동결은 되돌릴 수
             * 없으므로 차라리 실패한다(RVW-02 의 라벨 직렬화와 반대 판단이다 — 그쪽은 이미
             * 내려진 사람의 판정을 잃지 않는 것이 우선이었다).
             */
            throw new BusinessException(CaptureErrorCode.GOLD_SET_LABEL_SERIALIZATION_FAILED);
        }
    }
}
