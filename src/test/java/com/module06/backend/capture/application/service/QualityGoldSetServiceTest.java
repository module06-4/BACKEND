package com.module06.backend.capture.application.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import tools.jackson.databind.ObjectMapper;

import com.module06.backend.capture.application.port.out.ActionReviewQueryPort;
import com.module06.backend.capture.application.port.out.ActionReviewQueryPort.ReviewAction;
import com.module06.backend.capture.application.port.out.ActionReviewQueryPort.ReviewTarget;
import com.module06.backend.capture.application.port.out.QualityGoldSetRepository;
import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase.GoldSetRegistered;
import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase.RegisterGoldSetCommand;
import com.module06.backend.global.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * QLTY-01 · gold set 등록.
 *
 * <p>검증의 축은 <b>측정 장치가 측정 대상을 베끼지 않는가</b>다. 미검토 액션은 AI 가 낸 값
 * 그대로라, 함께 얼리면 모델이 자기 자신을 채점하게 된다 — precision 이 실제보다 높게 나오고
 * 그 숫자로 프롬프트 개선을 판단하게 된다. <b>동결은 되돌릴 수 없어</b> 뒤늦게 알아도 못 고친다.
 */
class QualityGoldSetServiceTest {

    private static final long COMPANY = 7L;
    private static final long MEETING = 500L;
    private static final long ME = 12L;

    @Test
    @DisplayName("전량 검토된 회의를 얼린다 — 버전은 마지막 다음이다")
    void 전량_검토된_회의를_얼린다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(2);

        GoldSetRegistered registered = service(goldSets, reviewed("HUMAN_CONFIRMED", "REJECTED"))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, "무작위 표본 #4"));

        assertThat(registered.version()).isEqualTo(3);
        assertThat(registered.actionCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("미검토가 남아 있으면 409 — AI 출력이 정답지에 들어가면 자기 자신을 채점한다")
    void 미검토가_남으면_거절한다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(0);

        assertThatThrownBy(() -> service(goldSets, reviewed("HUMAN_CONFIRMED", "PENDING"))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null)))
                .isInstanceOf(BusinessException.class);

        // 동결은 되돌릴 수 없다 — 얼기 전에 막아야 한다.
        assertThat(goldSets.frozen).isNull();
    }

    @Test
    @DisplayName("얼릴 액션이 없으면 409 — 빈 정답지는 precision 의 분모를 0 으로 만든다")
    void 액션이_없으면_거절한다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(0);

        assertThatThrownBy(() -> service(goldSets, new StubReviewQueryPort(List.of()))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null)))
                .isInstanceOf(BusinessException.class);

        assertThat(goldSets.frozen).isNull();
    }

    @Test
    @DisplayName("반려된 액션도 정답으로 얼린다 — 빼면 hallucination 을 잡았는지 채점할 수 없다")
    void 반려도_정답으로_얼린다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(0);

        service(goldSets, reviewed("HUMAN_CONFIRMED", "REJECTED"))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null));

        assertThat(goldSets.frozen.labeledActions()).contains("\"rejected\":true");
        // "이건 액션이 아니다"도 그 회의의 정답이다.
        assertThat(goldSets.frozen.labeledActions()).contains("\"rejected\":false");
    }

    @Test
    @DisplayName("게이트 신호는 정답지에 담지 않는다 — AI 가 낸 값이 정답과 같은 파일에 있으면 안 된다")
    void AI가_낸_값은_담지_않는다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(0);

        service(goldSets, reviewed("HUMAN_CONFIRMED"))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null));

        String labeled = goldSets.frozen.labeledActions();
        assertThat(labeled).doesNotContain("autoConfirmed").doesNotContain("signals");
        // 담기는 것은 담당자·기한·근거 발화다(V5.11 컬럼 주석).
        assertThat(labeled).contains("assigneeMemberId").contains("dueDate")
                .contains("evidenceTranscriptId");
    }

    @Test
    @DisplayName("같은 버전 동시 등록은 409 — 다음 버전으로 재시도하면 같은 라벨의 정답지가 두 벌 생긴다")
    void 동시_등록은_거절한다() {
        RecordingGoldSetRepository goldSets = new RecordingGoldSetRepository(0);
        goldSets.conflicting = true;

        assertThatThrownBy(() -> service(goldSets, reviewed("HUMAN_CONFIRMED"))
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 회사 회의는 관문에서 막는다 — 조회조차 하지 않는다")
    void 다른_회사_회의는_막는다() {
        StubReviewQueryPort actions = reviewed("HUMAN_CONFIRMED");

        assertThatThrownBy(() -> new QualityGoldSetService(
                new RecordingGoldSetRepository(0), actions,
                new MeetingAccessGuard((companyId, meetingId) -> false), new ObjectMapper())
                .register(new RegisterGoldSetCommand(COMPANY, MEETING, ME, null)))
                .isInstanceOf(BusinessException.class);

        assertThat(actions.queried).isFalse();
    }

    // ── 조립 ────────────────────────────────────────────────────────────────────

    private QualityGoldSetService service(RecordingGoldSetRepository goldSets, StubReviewQueryPort actions) {
        return new QualityGoldSetService(goldSets, actions,
                new MeetingAccessGuard((companyId, meetingId) -> true), new ObjectMapper());
    }

    private static StubReviewQueryPort reviewed(String... statuses) {
        List<ReviewAction> actions = new java.util.ArrayList<>();
        for (int i = 0; i < statuses.length; i++) {
            actions.add(new ReviewAction(
                    100L + i, 42L, "김서준", null, "로드맵 초안 작성", null,
                    LocalDate.of(2026, 8, 8), false, "제품 로드맵", false, statuses[i], null,
                    new ActionReviewQueryPort.Evidence(8812L, "김서준", "제가 정리할게요", 1000),
                    null, null));
        }
        return new StubReviewQueryPort(actions);
    }

    private static final class StubReviewQueryPort implements ActionReviewQueryPort {

        private final List<ReviewAction> actions;
        private boolean queried;

        private StubReviewQueryPort(List<ReviewAction> actions) {
            this.actions = actions;
        }

        @Override
        public List<ReviewAction> findByMeeting(long companyId, long meetingId, String reviewStatus) {
            queried = true;
            return actions;
        }

        @Override
        public Optional<ReviewTarget> findOne(long companyId, long meetingId, long actionId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<LocalDateTime> dispatchedAtOf(long companyId, long meetingId) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class RecordingGoldSetRepository implements QualityGoldSetRepository {

        private final int latestVersion;
        private FreezeCommand frozen;
        private boolean conflicting;

        private RecordingGoldSetRepository(int latestVersion) {
            this.latestVersion = latestVersion;
        }

        @Override
        public GoldSetView freeze(FreezeCommand command) {
            if (conflicting) {
                // 실물에서는 UNIQUE(meeting_id, version) 충돌이 이 모양으로 온다.
                throw new DataIntegrityViolationException("duplicate version");
            }
            frozen = command;
            return new GoldSetView(4L, command.version(), LocalDateTime.of(2026, 8, 5, 16, 0));
        }

        @Override
        public int latestVersionOf(long meetingId) {
            return latestVersion;
        }

        /* 동결 라벨 조회는 QLTY-02 채점의 몫이다 — 등록은 쓰지 않는다. */
        @Override
        public java.util.List<FrozenLabels> latestLabelsOf(long companyId) {
            throw new UnsupportedOperationException();
        }
    }
}
