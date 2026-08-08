package com.module06.backend.cap.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.module06.backend.cap.application.guard.CapMeetingAccessGuard;
import com.module06.backend.cap.application.port.out.CapObjectStoragePort;
import com.module06.backend.cap.application.usecase.DeleteRecordingUseCase;
import com.module06.backend.cap.domain.model.Recording;
import com.module06.backend.cap.domain.model.RecordingPart;
import com.module06.backend.cap.domain.repository.MeetingReferenceRepository;
import com.module06.backend.cap.domain.repository.ProcessingCompletionRepository;
import com.module06.backend.cap.domain.repository.ProjectTeamReferenceRepository;
import com.module06.backend.cap.domain.repository.RecordingPartRepository;
import com.module06.backend.cap.domain.repository.RecordingRepository;
import com.module06.backend.global.exception.BusinessException;

/*
 * CAP-15 녹음 삭제 서비스의 404·회사스코프(403)·STT/분석 완료 차단(409)·하드 삭제 규칙을 검증한다.
 * 회의는 회사 1 소속 고정(findCompanyId=1).
 */
@DisplayName("CAP-15 녹음 삭제 서비스")
class DeleteRecordingServiceTest {

    private static final String KEY = "recordings/org-1/meeting-500/recording.ogg";

    // 삭제 부수효과 기록(테스트별 새로).
    private final boolean[] storageDeleted = new boolean[1];
    private final boolean[] partsDeleted = new boolean[1];
    private final boolean[] recordingDeleted = new boolean[1];

    /* 회의(녹음)가 없으면 CAP-016으로 거절하는지 검증한다. */
    @Test
    @DisplayName("회의가 없으면 CAP-016으로 거절한다")
    void rejectsWhenMeetingMissing() {
        DeleteRecordingService service = service(Optional.empty(), Optional.empty(), false);

        assertErrorCode(() -> service.deleteRecording(500L, 1L, false), "CAP-016");
        assertNothingDeleted();
    }

    /* 다른 회사면 Z-002(ACCESS_DENIED)로 거절하는지 검증한다(owner/admin이라도). */
    @Test
    @DisplayName("다른 회사 녹음이면 Z-002로 거절한다")
    void rejectsWhenOtherCompany() {
        // 회의는 회사 1, 요청자 회사 2
        DeleteRecordingService service = service(Optional.of(1L), Optional.of(recording()), false);

        assertErrorCode(() -> service.deleteRecording(500L, 2L, false), "Z-002");
        assertNothingDeleted();
    }

    /* 녹음본이 없으면 CAP-016으로 거절하는지 검증한다. */
    @Test
    @DisplayName("녹음본이 없으면 CAP-016으로 거절한다")
    void rejectsWhenRecordingMissing() {
        DeleteRecordingService service = service(Optional.of(1L), Optional.empty(), false);

        assertErrorCode(() -> service.deleteRecording(500L, 1L, false), "CAP-016");
        assertNothingDeleted();
    }

    /* STT/분석 미완료 + confirm 없으면 CAP-017로 막고 아무것도 삭제하지 않는지 검증한다. */
    @Test
    @DisplayName("STT/분석 미완료 + confirm 없으면 CAP-017로 막는다")
    void rejectsWhenUnfinishedWithoutConfirm() {
        DeleteRecordingService service = service(Optional.of(1L), Optional.of(recording()), true);

        assertErrorCode(() -> service.deleteRecording(500L, 1L, false), "CAP-017");
        assertNothingDeleted();
    }

    /* STT/분석 미완료여도 confirm=true면 강행 삭제하는지 검증한다. */
    @Test
    @DisplayName("미완료여도 confirm=true면 강행 삭제한다")
    void deletesWhenUnfinishedButConfirmed() {
        DeleteRecordingService service = service(Optional.of(1L), Optional.of(recording()), true);

        DeleteRecordingUseCase.Result result = service.deleteRecording(500L, 1L, true);

        assertThat(result.freedBytes()).isEqualTo(15_000_000L);
        assertThat(result.deletedAt()).isNotNull();
        assertAllDeleted();
    }

    /* STT/분석 완료면 confirm 없이도 삭제하고 S3·part·recording을 모두 지우는지 검증한다. */
    @Test
    @DisplayName("완료 상태면 confirm 없이 삭제하고 오디오를 모두 지운다")
    void deletesWhenFinished() {
        DeleteRecordingService service = service(Optional.of(1L), Optional.of(recording()), false);

        DeleteRecordingUseCase.Result result = service.deleteRecording(500L, 1L, false);

        assertThat(result.freedBytes()).isEqualTo(15_000_000L);
        assertAllDeleted();
    }

    /* recording.sttTriggered가 완료 판정 저장소로 그대로 전달되는지 검증한다(0건=완료 오판 방지, #11 코드리뷰 반영). */
    @Test
    @DisplayName("recording의 sttTriggered를 완료 판정에 그대로 넘긴다")
    void passesSttTriggeredToCompletionCheck() {
        boolean[] receivedSttTriggered = new boolean[1];
        Recording triggered = Recording.register(500L, "recording.ogg", KEY, 15_000_000L, true);
        DeleteRecordingService service = service(Optional.of(1L), Optional.of(triggered), false,
                (meetingId, sttTriggered) -> {
                    receivedSttTriggered[0] = sttTriggered;
                    return false;
                });

        service.deleteRecording(500L, 1L, false);

        assertThat(receivedSttTriggered[0]).isTrue();
    }

    private Recording recording() {
        return Recording.register(500L, "recording.ogg", KEY, 15_000_000L);
    }

    private void assertNothingDeleted() {
        assertThat(storageDeleted[0]).isFalse();
        assertThat(partsDeleted[0]).isFalse();
        assertThat(recordingDeleted[0]).isFalse();
    }

    private void assertAllDeleted() {
        assertThat(storageDeleted[0]).as("S3 삭제").isTrue();
        assertThat(partsDeleted[0]).as("recording_part 삭제").isTrue();
        assertThat(recordingDeleted[0]).as("recording 삭제").isTrue();
    }

    // 회의 companyId·녹음본·미완료여부를 지정해 서비스를 조립한다. 삭제 부수효과를 기록한다.
    private DeleteRecordingService service(Optional<Long> companyId, Optional<Recording> recording,
                                          boolean unfinished) {
        return service(companyId, recording, unfinished, null);
    }

    // 완료 판정 저장소를 직접 지정하고 싶을 때(예: sttTriggered 전달값 검증)를 위한 오버로드.
    private DeleteRecordingService service(Optional<Long> companyId, Optional<Recording> recording,
                                          boolean unfinished, ProcessingCompletionRepository completionOverride) {
        storageDeleted[0] = false;
        partsDeleted[0] = false;
        recordingDeleted[0] = false;

        MeetingReferenceRepository meetingRef = new MeetingReferenceRepository() {
            @Override
            public boolean existsById(Long meetingId) {
                return companyId.isPresent();
            }

            @Override
            public boolean isAttendee(Long meetingId, Long memberId) {
                return true;
            }

            @Override
            public boolean isHost(Long meetingId, Long memberId) {
                return true;
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
        RecordingRepository recordingRepo = new RecordingRepository() {
            @Override
            public Recording save(Recording r) {
                return r;
            }

            @Override
            public boolean existsByMeetingId(Long meetingId) {
                return recording.isPresent();
            }

            @Override
            public Optional<Recording> findByMeetingId(Long meetingId) {
                return recording;
            }

            @Override
            public void deleteByMeetingId(Long meetingId) {
                recordingDeleted[0] = true;
            }
        };
        RecordingPartRepository partRepo = new RecordingPartRepository() {
            @Override
            public RecordingPart save(RecordingPart p) {
                return p;
            }

            @Override
            public List<Integer> findSeqsInSegment(Long meetingId, int segmentSeq) {
                return List.of();
            }

            @Override
            public void deleteByMeetingId(Long meetingId) {
                partsDeleted[0] = true;
            }
        };
        ProcessingCompletionRepository completion = completionOverride != null
                ? completionOverride
                : (meetingId, sttTriggered) -> unfinished;
        CapObjectStoragePort storage = new CapObjectStoragePort() {
            @Override
            public IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType) {
                throw new AssertionError("삭제 경로에서 업로드 URL은 호출되면 안 됩니다.");
            }

            @Override
            public IssuedPlaybackUrl issuePlaybackUrl(String s3Key) {
                throw new AssertionError("삭제 경로에서 재생 URL은 호출되면 안 됩니다.");
            }

            @Override
            public void deleteRecording(String s3Key) {
                storageDeleted[0] = true;
            }

            @Override
            public boolean objectMatches(String s3Key, long expectedSizeBytes) {
                throw new AssertionError("삭제 경로에서 objectMatches는 호출되면 안 됩니다.");
            }
        };
        // 삭제 경로(회사 스코프만 씀)는 프로젝트 멤버 판정을 타지 않으므로 항상 false인 스텁으로 충분하다.
        ProjectTeamReferenceRepository projectTeamRef = (projectId, teamId) -> false;
        CapMeetingAccessGuard accessGuard = new CapMeetingAccessGuard(meetingRef, projectTeamRef);
        return new DeleteRecordingService(meetingRef, accessGuard, recordingRepo, partRepo, completion, storage);
    }

    private void assertErrorCode(Runnable execution, String expectedCode) {
        assertThatThrownBy(execution::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo(expectedCode);
    }
}
