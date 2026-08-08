package com.module06.backend.cap.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.module06.backend.cap.application.command.CompletePartUploadCommand;
import com.module06.backend.cap.application.command.IssuePartUploadUrlsCommand;
import com.module06.backend.cap.application.guard.CapMeetingAccessGuard;
import com.module06.backend.cap.application.port.out.CapObjectStoragePort;
import com.module06.backend.cap.application.port.out.CaptureHeartbeatPort;
import com.module06.backend.cap.application.usecase.IssuePartUploadUrlsUseCase;
import com.module06.backend.cap.domain.model.CaptureUploadState;
import com.module06.backend.cap.domain.model.RecordingPart;
import com.module06.backend.cap.domain.repository.CaptureUploadStateRepository;
import com.module06.backend.cap.domain.repository.MeetingReferenceRepository;
import com.module06.backend.cap.domain.repository.ProjectTeamReferenceRepository;
import com.module06.backend.cap.domain.repository.RecordingPartRepository;
import com.module06.backend.global.exception.BusinessException;

/*
 * presign(#4)+complete(#7) 실제 로직(CaptureUploadService)의 회의 존재·참석자 검증·녹음자
 * 배정/검증(하트비트 기반 이어받기 포함)·s3Key/segmentSeq 위조 차단 규칙을 검증하는 단위 테스트다.
 * 지금까지 이 서비스는 컨트롤러 계약 테스트(CaptureUploadControllerTest, UseCase 인터페이스를
 * 페이크)로만 간접 커버됐고 서비스 자체의 단위 테스트가 없었다 — 이 파일이 그 공백을 메운다.
 */
@DisplayName("presign/complete 캡처 업로드 서비스")
class CaptureUploadServiceTest {

    private static final long MEETING_ID = 500L;
    private static final long COMPANY_ID = 1L;
    private static final long CALLER_ID = 7L;

    // presign이 발급한 URL 개수·complete가 저장한 청크·하트비트 refresh 호출 여부를 기록한다.
    private final List<RecordingPart> savedParts = new ArrayList<>();
    private final boolean[] heartbeatRefreshed = new boolean[1];

    // ── presign(issuePartUploadUrls) ──

    /* 회의가 없으면 CAP-002로 거절하는지 검증한다. */
    @Test
    @DisplayName("presign: 회의가 없으면 CAP-002로 거절한다")
    void issue_rejectsWhenMeetingMissing() {
        CaptureUploadService service = service(Optional.empty(), true, null, true, true);

        assertErrorCode(() -> service.issuePartUploadUrls(issueCmd(2)), "CAP-002");
    }

    /* 참석자가 아니면 CAP-010으로 거절하는지 검증한다(비참석자가 회의ID만으로 녹음자로 배정되는 것 차단). */
    @Test
    @DisplayName("presign: 참석자가 아니면 CAP-010으로 거절한다")
    void issue_rejectsNonAttendee() {
        CaptureUploadService service = service(Optional.of(COMPANY_ID), false, null, true, true);

        assertErrorCode(() -> service.issuePartUploadUrls(issueCmd(2)), "CAP-010");
    }

    /* 첫 presign(상태 없음)이면 호출자가 녹음자로 배정되고, 요청한 개수만큼 세그먼트 0의 URL이 발급되는지 검증한다. */
    @Test
    @DisplayName("presign: 첫 호출이면 녹음자로 배정되고 count개 URL을 발급한다")
    void issue_firstCallAssignsRecorderAndIssuesUrls() {
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, null, true, true);

        IssuePartUploadUrlsUseCase.Result result = service.issuePartUploadUrls(issueCmd(3));

        assertThat(result.segmentSeq()).isZero();
        assertThat(result.parts()).hasSize(3);
        assertThat(result.parts()).extracting(IssuePartUploadUrlsUseCase.Part::seq)
                .containsExactly(1, 2, 3);
        assertThat(result.parts().get(0).presignedUrl()).contains("segments/0/parts/0001");
        assertThat(heartbeatRefreshed[0]).isTrue();
    }

    /* 이미 녹음자가 있고 같은 사람이 재호출하면(정상 배치 재요청) 세그먼트가 그대로 유지되는지 검증한다. */
    @Test
    @DisplayName("presign: 현재 녹음자 본인의 재호출은 세그먼트를 유지한다")
    void issue_sameRecorderKeepsSegment() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, CALLER_ID);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        IssuePartUploadUrlsUseCase.Result result = service.issuePartUploadUrls(issueCmd(1));

        assertThat(result.segmentSeq()).isZero();
    }

    /* 다른 사람이 녹음자인데 하트비트가 살아있으면(30초 이내) 이어받기가 막혀 CAP-004로 거절되는지 검증한다. */
    @Test
    @DisplayName("presign: 녹음자가 살아있으면 다른 사람의 이어받기를 CAP-004로 거절한다")
    void issue_rejectsTakeoverWhenRecorderAlive() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, 99L);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        assertErrorCode(() -> service.issuePartUploadUrls(issueCmd(1)), "CAP-004");
    }

    /* 녹음자의 하트비트가 끊겼으면(30초 초과) 다른 참석자가 이어받아 세그먼트가 올라가는지 검증한다. */
    @Test
    @DisplayName("presign: 녹음자가 끊겼으면 다른 참석자가 이어받고 세그먼트가 올라간다")
    void issue_allowsTakeoverWhenRecorderDead() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, 99L);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, false, true);

        IssuePartUploadUrlsUseCase.Result result = service.issuePartUploadUrls(issueCmd(1));

        assertThat(result.segmentSeq()).isEqualTo(1);
    }

    // ── complete(completePartUpload) ──

    /* 회의가 없으면 CAP-002로 거절하는지 검증한다. */
    @Test
    @DisplayName("complete: 회의가 없으면 CAP-002로 거절한다")
    void complete_rejectsWhenMeetingMissing() {
        CaptureUploadService service = service(Optional.empty(), true, null, true, true);

        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm"))),
                "CAP-002");
        assertThat(savedParts).isEmpty();
    }

    /* 참석자가 아니면 CAP-010으로 거절하는지 검증한다. */
    @Test
    @DisplayName("complete: 참석자가 아니면 CAP-010으로 거절한다")
    void complete_rejectsNonAttendee() {
        CaptureUploadService service = service(Optional.of(COMPANY_ID), false, null, true, true);

        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm"))),
                "CAP-010");
    }

    /* presign이 한 번도 호출된 적 없으면(상태 없음) CAP-004로 거절되는지 검증한다. */
    @Test
    @DisplayName("complete: presign 이력이 없으면 CAP-004로 거절한다")
    void complete_rejectsWhenStateMissing() {
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, null, true, true);

        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm"))),
                "CAP-004");
        assertThat(savedParts).isEmpty();
    }

    /* 현재 녹음자가 아닌 사람이 완료 통보하면 CAP-004로 거절되는지 검증한다. */
    @Test
    @DisplayName("complete: 현재 녹음자가 아니면 CAP-004로 거절한다")
    void complete_rejectsNonRecorder() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, 99L);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm"))),
                "CAP-004");
        assertThat(savedParts).isEmpty();
    }

    /* 본문 segmentSeq가 서버가 아는 현재 세그먼트와 다르면 CAP-009로 거절되는지 검증한다(위조 세그먼트 방지). */
    @Test
    @DisplayName("complete: segmentSeq가 서버 상태와 다르면 CAP-009로 거절한다")
    void complete_rejectsSegmentMismatch() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, CALLER_ID);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        assertErrorCode(() -> service.completePartUpload(completeCmd(1, 1, expectedKey(1, 1, "webm"))),
                "CAP-009");
        assertThat(savedParts).isEmpty();
    }

    /* 제출된 s3Key가 서버 재구성 값과 다르면(경로 조작) CAP-009로 거절되는지 검증한다(IDOR 방지). */
    @Test
    @DisplayName("complete: s3Key가 서버 재구성 값과 다르면 CAP-009로 거절한다")
    void complete_rejectsKeyMismatch() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, CALLER_ID);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        String tamperedKey = "stt-temp/org-999/meeting-999/segments/0/parts/0001.webm";
        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, tamperedKey)), "CAP-009");
        assertThat(savedParts).isEmpty();
    }

    /* S3에 실제로 그 크기로 업로드된 게 없으면(HEAD 불일치) CAP-019로 거절되는지 검증한다(#155 —
       클라이언트가 업로드도 안 하고 완료를 통보하는 것을 막는다). */
    @Test
    @DisplayName("complete: S3에 실제 업로드가 확인 안 되면 CAP-019로 거절한다")
    void complete_rejectsWhenObjectNotUploaded() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, CALLER_ID);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, false);

        assertErrorCode(() -> service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm"))),
                "CAP-019");
        assertThat(savedParts).isEmpty();
    }

    /* 정상 완료 통보면 청크가 저장되고, lastSeq·하트비트가 갱신되는지 검증한다. */
    @Test
    @DisplayName("complete: 정상 통보면 청크를 저장하고 상태·하트비트를 갱신한다")
    void complete_savesChunkAndRefreshesState() {
        CaptureUploadState existing = CaptureUploadState.startWithRecorder(MEETING_ID, CALLER_ID);
        CaptureUploadService service = service(Optional.of(COMPANY_ID), true, existing, true, true);

        service.completePartUpload(completeCmd(0, 1, expectedKey(0, 1, "webm")));

        assertThat(savedParts).hasSize(1);
        RecordingPart saved = savedParts.get(0);
        assertThat(saved.getMeetingId()).isEqualTo(MEETING_ID);
        assertThat(saved.getSeq()).isEqualTo(1);
        assertThat(saved.getContentType()).isEqualTo("audio/webm");
        assertThat(saved.getUploaderMemberId()).isEqualTo(CALLER_ID);
        assertThat(existing.getLastSeq()).isEqualTo(1);
        assertThat(heartbeatRefreshed[0]).isTrue();
    }

    // 서버가 재구성할 것으로 기대하는 s3Key(테스트에서 tampered 여부 비교용으로 직접 계산).
    private String expectedKey(int segmentSeq, int seq, String extension) {
        return "stt-temp/org-%d/meeting-%d/segments/%d/parts/%04d.%s"
                .formatted(COMPANY_ID, MEETING_ID, segmentSeq, seq, extension);
    }

    private IssuePartUploadUrlsCommand issueCmd(int count) {
        return new IssuePartUploadUrlsCommand(MEETING_ID, CALLER_ID, count, "audio/webm");
    }

    private CompletePartUploadCommand completeCmd(int segmentSeq, int seq, String s3Key) {
        return new CompletePartUploadCommand(MEETING_ID, CALLER_ID, segmentSeq, seq, s3Key, 1_000_000L);
    }

    // 회의 존재/참석 여부·기존 상태·녹음자 하트비트 생존 여부·S3 objectMatches 결과를 지정해
    // 서비스를 조립한다. 회의는 회사 1 소속으로 고정. state가 null이면 "presign 이력 없음"을 뜻한다.
    private CaptureUploadService service(Optional<Long> companyId, boolean attendee, CaptureUploadState state,
                                         boolean recorderAlive, boolean objectMatches) {
        savedParts.clear();
        heartbeatRefreshed[0] = false;

        MeetingReferenceRepository meetingRef = new MeetingReferenceRepository() {
            @Override
            public boolean existsById(Long meetingId) {
                return companyId.isPresent();
            }

            @Override
            public boolean isAttendee(Long meetingId, Long memberId) {
                return attendee;
            }

            @Override
            public boolean isHost(Long meetingId, Long memberId) {
                return false;
            }

            @Override
            public Optional<Long> findCompanyId(Long meetingId) {
                return companyId;
            }

            @Override
            public int countAttendees(Long meetingId) {
                return 0;
            }

            @Override
            public Optional<Long> findProjectId(Long meetingId) {
                return Optional.empty();
            }
        };
        ProjectTeamReferenceRepository projectTeamRef = (projectId, teamId) -> false;
        CapMeetingAccessGuard accessGuard = new CapMeetingAccessGuard(meetingRef, projectTeamRef);

        CaptureUploadStateRepository stateRepo = new CaptureUploadStateRepository() {
            @Override
            public Optional<CaptureUploadState> findByMeetingId(Long meetingId) {
                return Optional.ofNullable(state);
            }

            @Override
            public CaptureUploadState save(CaptureUploadState toSave) {
                return toSave;
            }
        };
        RecordingPartRepository partRepo = new RecordingPartRepository() {
            @Override
            public RecordingPart save(RecordingPart recordingPart) {
                savedParts.add(recordingPart);
                return recordingPart;
            }

            @Override
            public List<Integer> findSeqsInSegment(Long meetingId, int segmentSeq) {
                return List.of();
            }

            @Override
            public void deleteByMeetingId(Long meetingId) {
            }
        };
        CapObjectStoragePort storage = new CapObjectStoragePort() {
            @Override
            public IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType) {
                return new IssuedPartUploadUrl("https://stub/upload/" + s3Key, 900);
            }

            @Override
            public IssuedPlaybackUrl issuePlaybackUrl(String s3Key) {
                throw new AssertionError("presign/complete 경로에서 재생 URL은 호출되면 안 됩니다.");
            }

            @Override
            public void deleteRecording(String s3Key) {
                throw new AssertionError("presign/complete 경로에서 삭제는 호출되면 안 됩니다.");
            }

            @Override
            public boolean objectMatches(String s3Key, long expectedSizeBytes) {
                return objectMatches;
            }
        };
        CaptureHeartbeatPort heartbeat = new CaptureHeartbeatPort() {
            @Override
            public void refresh(Long meetingId) {
                heartbeatRefreshed[0] = true;
            }

            @Override
            public boolean isAlive(Long meetingId) {
                return recorderAlive;
            }
        };
        CompletePartUploadWriter writer = new CompletePartUploadWriter(partRepo, stateRepo, heartbeat);
        return new CaptureUploadService(meetingRef, accessGuard, stateRepo, storage, heartbeat, writer);
    }

    private void assertErrorCode(Runnable execution, String expectedCode) {
        assertThatThrownBy(execution::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo(expectedCode);
    }
}
