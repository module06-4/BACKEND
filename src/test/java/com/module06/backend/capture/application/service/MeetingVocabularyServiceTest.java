package com.module06.backend.capture.application.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.module06.backend.capture.application.port.out.AiLayerPort;
import com.module06.backend.capture.application.port.out.CustomVocabularyPort;
import com.module06.backend.capture.application.port.out.MeetingVocabularyRepository;
import com.module06.backend.capture.application.port.out.MeetingVocabularyRepository.VocabularyView;
import com.module06.backend.capture.application.usecase.RebuildMeetingVocabularyUseCase.RebuildVocabularyCommand;
import com.module06.backend.capture.domain.model.VocabularyStatus;
import com.module06.backend.global.exception.BusinessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * STT-01 · STT-02.
 *
 * <p>검증의 축은 <b>계정 상한을 낭비하지 않는가</b>다. 어휘 리소스는 계정당 개수 상한이 있고,
 * 넘치면 <b>신규 회의가 어휘 없이 돌게 된다</b> — 그 회의들은 아무 오류 없이 인식률만 낮아지므로
 * 상한에 걸렸다는 사실이 한참 뒤에야 드러난다.
 */
class MeetingVocabularyServiceTest {

    private static final long COMPANY = 7L;
    private static final long MEETING = 500L;
    private static final long HOST = 12L;
    private static final long OTHER = 99L;

    @Test
    @DisplayName("만든 적 없는 회의는 PENDING 으로 답한다 — 404 면 대부분의 회의가 오류를 받는다")
    void 만든_적_없으면_PENDING() {
        VocabularyView view = service(new FakeVocabularyRepository(), new RecordingVocabularyPort())
                .getVocabulary(COMPANY, MEETING);

        assertThat(view.status()).isEqualTo(VocabularyStatus.PENDING);
        assertThat(view.phraseCount()).isZero();
        assertThat(view.builtAt()).isNull();
    }

    @Test
    @DisplayName("재생성은 참석자 이름을 어휘로 넘긴다 — 이 목록이 곧 인식률이다")
    void 참석자_이름을_어휘로_넘긴다() {
        RecordingVocabularyPort provider = new RecordingVocabularyPort();

        service(new FakeVocabularyRepository(), provider)
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST));

        // 명단 밖 탈출구의 이름도 넣는다 — 어휘의 목적은 받아쓰기 정확도이지 명단 판정이 아니다.
        assertThat(provider.built.get(0).phrases()).containsExactly("김서준", "박도현", "명단 외");
    }

    @Test
    @DisplayName("제출한 리소스 이름을 저장한다 — 없으면 나중에 지울 수 없어 계정 상한이 잠긴다")
    void 리소스_이름을_저장한다() {
        FakeVocabularyRepository vocabularies = new FakeVocabularyRepository();

        service(vocabularies, new RecordingVocabularyPort())
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST));

        assertThat(vocabularies.assignedName).isEqualTo("resource-500");
    }

    @Test
    @DisplayName("이미 재생성 중이면 제공자를 다시 부르지 않는다 — 어휘가 두 벌 만들어져 상한을 갉아먹는다")
    void 이미_재생성_중이면_제출하지_않는다() {
        FakeVocabularyRepository vocabularies = new FakeVocabularyRepository();
        vocabularies.alreadyRebuilding = true;
        RecordingVocabularyPort provider = new RecordingVocabularyPort();

        service(vocabularies, provider).rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST));

        // 오류가 아니다 — 이미 시작된 그 작업이 답이다. 다만 제출을 또 하면 안 된다.
        assertThat(provider.built).isEmpty();
    }

    @Test
    @DisplayName("제출이 실패하면 FAILED 로 남긴다 — PENDING 이면 선점에 막혀 다시 누를 수도 없다")
    void 제출이_실패하면_FAILED로_남긴다() {
        FakeVocabularyRepository vocabularies = new FakeVocabularyRepository();
        RecordingVocabularyPort provider = new RecordingVocabularyPort();
        provider.failing = true;

        assertThatThrownBy(() -> service(vocabularies, provider)
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST)))
                .isInstanceOf(RuntimeException.class);

        assertThat(vocabularies.failedCode).isEqualTo("VOCABULARY_BUILD_FAILED");
        // 만들어지지도 않은 이름을 적어 두면 정리 작업이 없는 리소스를 찾는다.
        assertThat(vocabularies.assignedName).isNull();
    }

    @Test
    @DisplayName("넣을 참석자가 없으면 409 — 빈 어휘가 계정 상한을 하나 차지한다")
    void 참석자가_없으면_거절한다() {
        RecordingVocabularyPort provider = new RecordingVocabularyPort();

        assertThatThrownBy(() -> new MeetingVocabularyService(
                new FakeVocabularyRepository(), provider,
                meetingId -> List.of(), accessibleGuard(), meetingId -> Optional.of(HOST))
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST)))
                .isInstanceOf(BusinessException.class);

        // 제공자를 부르지 않아야 한다 — 부르면 빈 리소스가 만들어진다.
        assertThat(provider.built).isEmpty();
    }

    @Test
    @DisplayName("회의 담당자가 아니면 403 — 아무나 누르면 계정 상한을 갉아먹는다")
    void 담당자가_아니면_거절한다() {
        RecordingVocabularyPort provider = new RecordingVocabularyPort();

        assertThatThrownBy(() -> service(new FakeVocabularyRepository(), provider)
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, OTHER)))
                .isInstanceOf(BusinessException.class);

        assertThat(provider.built).isEmpty();
    }

    @Test
    @DisplayName("담당자를 모르면 통과시키지 않는다 — 모르는 채 지나가면 이 검사는 없는 것과 같다")
    void 담당자를_모르면_거절한다() {
        assertThatThrownBy(() -> new MeetingVocabularyService(
                new FakeVocabularyRepository(), new RecordingVocabularyPort(),
                roster(), accessibleGuard(), meetingId -> Optional.empty())
                .rebuild(new RebuildVocabularyCommand(COMPANY, MEETING, HOST)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    @DisplayName("다른 회사 회의는 관문에서 막는다 — 조회조차 하지 않는다")
    void 다른_회사_회의는_막는다() {
        FakeVocabularyRepository vocabularies = new FakeVocabularyRepository();

        assertThatThrownBy(() -> new MeetingVocabularyService(
                vocabularies, new RecordingVocabularyPort(), roster(),
                new MeetingAccessGuard((companyId, meetingId) -> false),
                meetingId -> Optional.of(HOST))
                .getVocabulary(COMPANY, MEETING))
                .isInstanceOf(BusinessException.class);

        assertThat(vocabularies.queried).isFalse();
    }

    // ── 조립 ────────────────────────────────────────────────────────────────────

    private MeetingVocabularyService service(FakeVocabularyRepository vocabularies,
                                             RecordingVocabularyPort provider) {
        return new MeetingVocabularyService(
                vocabularies, provider, roster(), accessibleGuard(), meetingId -> Optional.of(HOST));
    }

    private static MeetingAccessGuard accessibleGuard() {
        return new MeetingAccessGuard((companyId, meetingId) -> true);
    }

    /* 명단 밖 탈출구(personId=null)를 포함한다 — 실제 명단의 모양이다. */
    private static MeetingParticipantProvider roster() {
        return meetingId -> List.of(
                new AiLayerPort.Participant(42L, "김서준"),
                new AiLayerPort.Participant(43L, "박도현"),
                new AiLayerPort.Participant(null, "명단 외"));
    }

    private static final class FakeVocabularyRepository implements MeetingVocabularyRepository {

        private boolean queried;
        private String assignedName;
        private String failedCode;
        /* 이미 재생성 중인 상태를 흉내낸다 — 선점에 실패하는 쪽. */
        private boolean alreadyRebuilding;

        @Override
        public Optional<VocabularyView> findByMeeting(long meetingId) {
            queried = true;
            return Optional.empty();
        }

        @Override
        public Optional<VocabularyView> claimRebuild(long meetingId) {
            if (alreadyRebuilding) {
                return Optional.empty();
            }
            return Optional.of(new VocabularyView(1L, meetingId, VocabularyStatus.PENDING, 0, null,
                    LocalDateTime.of(2026, 8, 4, 9, 12)));
        }

        @Override
        public void markBuildFailed(long vocabularyId, String errorCode) {
            failedCode = errorCode;
        }

        @Override
        public void assignPendingName(long vocabularyId, String pendingVocabularyName) {
            assignedName = pendingVocabularyName;
        }
    }

    private static final class RecordingVocabularyPort implements CustomVocabularyPort {

        private final List<BuildRequest> built = new ArrayList<>();
        private boolean failing;

        @Override
        public String requestBuild(BuildRequest request) {
            if (failing) {
                // 실물에서는 연결 실패·제공자 오류가 이 모양으로 온다.
                throw new IllegalStateException("제공자에 닿지 않는다");
            }
            built.add(request);
            return "resource-" + request.meetingId();
        }

        @Override
        public void delete(String providerVocabularyName) {
            throw new UnsupportedOperationException();
        }
    }
}
