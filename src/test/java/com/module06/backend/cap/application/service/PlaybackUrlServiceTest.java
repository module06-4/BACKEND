package com.module06.backend.cap.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.module06.backend.cap.application.guard.CapMeetingAccessGuard;
import com.module06.backend.cap.application.port.out.CapObjectStoragePort;
import com.module06.backend.cap.application.usecase.GetPlaybackUrlUseCase;
import com.module06.backend.cap.application.usecase.GetPlaybackUrlUseCase.Requester;
import com.module06.backend.cap.domain.model.Recording;
import com.module06.backend.cap.domain.repository.MeetingReferenceRepository;
import com.module06.backend.cap.domain.repository.ProjectTeamReferenceRepository;
import com.module06.backend.cap.domain.repository.RecordingRepository;
import com.module06.backend.global.exception.BusinessException;

/*
 * CAP-14 재생 URL 발급 서비스의 열람 권한(참석자 / 같은 회사 owner·admin / 프로젝트 멤버)·녹음 존재·
 * presigned GET/duration 규칙을 검증한다. 회의는 회사 1·프로젝트 12 소속으로 고정(findCompanyId=1,
 * findProjectId=12) — 열람 권한 판정 자체는 CapMeetingAccessGuard(공용)가 하므로 여기서는 서비스가
 * 그 결과를 올바르게 쓰는지만 본다.
 */
@DisplayName("CAP-14 재생 URL 발급 서비스")
class PlaybackUrlServiceTest {

    private static final String KEY = "recordings/org-1/meeting-500/recording.ogg";
    private static final Long PROJECT_ID = 12L;

    /* 참석자면 role 무관하게 발급되는지 검증한다. */
    @Test
    @DisplayName("참석자는 재생 URL을 발급받는다")
    void attendeeGetsUrl() {
        PlaybackUrlService service = service(true, Optional.of(recording(3612)), List.of());

        GetPlaybackUrlUseCase.Result result = service.getPlaybackUrl(500L, member(7L, 1L));

        assertThat(result.url()).isEqualTo("https://stub/playback/" + KEY);
        assertThat(result.expiresIn()).isEqualTo(10800);
        assertThat(result.durationMs()).isEqualTo(3_612_000L);
    }

    /* 참석 안 하고, 같은 회사 owner/admin도 아니고, 프로젝트 멤버도 아니면 CAP-010으로 거절되는지 검증한다. */
    @Test
    @DisplayName("참석 안 한 일반 멤버는 CAP-010으로 거절한다")
    void rejectsNonAttendeeMember() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of());

        assertErrorCode(() -> service.getPlaybackUrl(500L, member(7L, 1L)), "CAP-010");
    }

    /* 참석 안 했어도 같은 회사 owner는 발급받는지 검증한다(감독 열람). */
    @Test
    @DisplayName("같은 회사 owner는 참석 안 해도 발급받는다")
    void sameCompanyOwnerGetsUrl() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of());

        GetPlaybackUrlUseCase.Result result =
                service.getPlaybackUrl(500L, new Requester(7L, 1L, null, "OWNER", false));

        assertThat(result.url()).isEqualTo("https://stub/playback/" + KEY);
    }

    /* 참석 안 했어도 같은 회사 admin은 발급받는지 검증한다. */
    @Test
    @DisplayName("같은 회사 admin은 참석 안 해도 발급받는다")
    void sameCompanyAdminGetsUrl() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of());

        GetPlaybackUrlUseCase.Result result =
                service.getPlaybackUrl(500L, new Requester(7L, 1L, null, "MEMBER", true));

        assertThat(result.url()).isEqualTo("https://stub/playback/" + KEY);
    }

    /* 다른 회사 owner는 참석 안 했으면 CAP-010으로 거절되는지 검증한다(cross-tenant 차단). */
    @Test
    @DisplayName("다른 회사 owner/admin은 거절한다(cross-tenant 차단)")
    void rejectsOtherCompanyOwner() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of());

        // 회의는 회사 1인데 요청자는 회사 2의 owner
        assertErrorCode(() -> service.getPlaybackUrl(500L, new Requester(7L, 2L, null, "OWNER", false)), "CAP-010");
        // 회사 2의 admin도 마찬가지
        assertErrorCode(() -> service.getPlaybackUrl(500L, new Requester(7L, 2L, null, "MEMBER", true)), "CAP-010");
    }

    /* 참석 안 하고 owner/admin도 아니어도, 같은 회사이고 회의 프로젝트에 자기 팀이 배정돼 있으면
       발급받는지 검증한다. 회의는 회사 1 소속이므로 요청자도 회사 1이어야 한다. */
    @Test
    @DisplayName("같은 회사 프로젝트 멤버는 참석 안 해도 발급받는다")
    void projectMemberGetsUrl() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of(9L));

        GetPlaybackUrlUseCase.Result result =
                service.getPlaybackUrl(500L, new Requester(7L, 1L, 9L, "MEMBER", false));

        assertThat(result.url()).isEqualTo("https://stub/playback/" + KEY);
    }

    /* 같은 회사여도 팀이 이 회의의 프로젝트에 배정돼 있지 않으면 프로젝트 멤버로 인정되지 않는지 검증한다. */
    @Test
    @DisplayName("다른 프로젝트 팀은 거절한다")
    void rejectsUnassignedTeam() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of(9L));

        assertErrorCode(() -> service.getPlaybackUrl(500L, new Requester(7L, 1L, 99L, "MEMBER", false)), "CAP-010");
    }

    /* 팀이 배정돼 있어도 요청자가 다른 회사면 거절하는지 검증한다(프로젝트 멤버 경로의 cross-tenant 차단,
       CodeRabbit 지적 — project_team 조인만으로는 회사 스코프가 보장되지 않는다). */
    @Test
    @DisplayName("팀은 배정돼 있어도 다른 회사면 거절한다")
    void rejectsProjectMemberFromOtherCompany() {
        PlaybackUrlService service = service(false, Optional.of(recording(100)), List.of(9L));

        assertErrorCode(() -> service.getPlaybackUrl(500L, new Requester(7L, 2L, 9L, "MEMBER", false)), "CAP-010");
    }

    /* 녹음본이 없으면 CAP-016으로 거절되는지 검증한다(권한 통과 후). */
    @Test
    @DisplayName("녹음본이 없으면 CAP-016으로 거절한다")
    void rejectsWhenRecordingMissing() {
        PlaybackUrlService service = service(true, Optional.empty(), List.of());

        assertErrorCode(() -> service.getPlaybackUrl(500L, member(7L, 1L)), "CAP-016");
    }

    /* duration이 아직 안 채워졌으면(null) durationMs가 0인지 검증한다. */
    @Test
    @DisplayName("duration 미채움이면 durationMs는 0이다")
    void durationZeroWhenNotComputed() {
        PlaybackUrlService service = service(true, Optional.of(Recording.register(500L, "recording.ogg", KEY, 100L)),
                List.of());

        assertThat(service.getPlaybackUrl(500L, member(7L, 1L)).durationMs()).isZero();
    }

    // 일반 멤버 요청자(회사 지정, 팀 없음).
    private Requester member(Long memberId, Long companyId) {
        return new Requester(memberId, companyId, null, "MEMBER", false);
    }

    // duration_sec 지정 녹음본.
    private Recording recording(int durationSec) {
        return Recording.restore(1L, 500L, "recording.ogg", KEY, 15_000_000L, durationSec, null, null);
    }

    // 참석 여부·녹음본·프로젝트에 배정된 팀 목록을 지정해 서비스를 조립한다. 회의는 회사 1·프로젝트 12 소속,
    // 스토리지는 키 기반 가짜 GET URL을 돌려준다.
    private PlaybackUrlService service(boolean attendee, Optional<Recording> recording, List<Long> assignedTeamIds) {
        MeetingReferenceRepository meetingRef = new MeetingReferenceRepository() {
            @Override
            public boolean existsById(Long meetingId) {
                return true;
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
                return Optional.of(1L);
            }

            @Override
            public int countAttendees(Long meetingId) {
                return 0;
            }

            @Override
            public Optional<Long> findProjectId(Long meetingId) {
                return Optional.of(PROJECT_ID);
            }
        };
        ProjectTeamReferenceRepository projectTeamRef =
                (projectId, teamId) -> projectId.equals(PROJECT_ID) && assignedTeamIds.contains(teamId);
        CapMeetingAccessGuard accessGuard = new CapMeetingAccessGuard(meetingRef, projectTeamRef);

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
            }
        };
        CapObjectStoragePort storage = new CapObjectStoragePort() {
            @Override
            public IssuedPartUploadUrl issuePartUploadUrl(String s3Key, String contentType) {
                throw new AssertionError("재생 경로에서 업로드 URL은 호출되면 안 됩니다.");
            }

            @Override
            public IssuedPlaybackUrl issuePlaybackUrl(String s3Key) {
                return new IssuedPlaybackUrl("https://stub/playback/" + s3Key, 10800);
            }

            @Override
            public void deleteRecording(String s3Key) {
            }

            @Override
            public boolean objectMatches(String s3Key, long expectedSizeBytes) {
                throw new AssertionError("재생 경로에서 objectMatches는 호출되면 안 됩니다.");
            }
        };
        return new PlaybackUrlService(accessGuard, recordingRepo, storage);
    }

    // 실행 결과가 예상 서비스 오류 코드인지 검증한다.
    private void assertErrorCode(Runnable execution, String expectedCode) {
        assertThatThrownBy(execution::run)
                .isInstanceOf(BusinessException.class)
                .extracting(exception -> ((BusinessException) exception).getErrorCode().getCode())
                .isEqualTo(expectedCode);
    }
}
