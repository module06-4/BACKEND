package com.module06.backend.capture.presentation.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.usecase.GetMeetingVocabularyUseCase;
import com.module06.backend.capture.application.usecase.RebuildMeetingVocabularyUseCase;
import com.module06.backend.capture.application.usecase.RebuildMeetingVocabularyUseCase.RebuildVocabularyCommand;
import com.module06.backend.capture.presentation.api.response.MeetingVocabularyResponse;
import com.module06.backend.global.response.ApiResponse;
import com.module06.backend.global.security.AuthPrincipal;

/*
 * STT-01 · STT-02 — 회의별 STT 커스텀 어휘.
 *
 * <h2>회의 입장 **전에** 보는 화면이다</h2>
 * 다른 캡처 API 는 회의가 끝난 뒤를 다루지만 이 둘은 시작 전이다. AWS Transcribe 가 어휘를
 * 미리 리소스로 만들어 이름으로 참조하기 때문에, 예약 시점에 만들어 두지 않으면 그 회의는
 * 어휘 없이 받아쓰기된다 — 그래서 "만들어졌는지"를 미리 볼 자리가 필요하다.
 *
 * ⚠ **막는 값이 아니다.** READY 가 아니어도 녹음은 시작할 수 있고 고유명사 인식률만 낮아진다.
 * 화면도 경고이지 차단이 아니어야 한다.
 */
@Tag(name = "Meeting Vocabulary", description = "STT 커스텀 어휘 상태·재생성 API")
@RestController
@RequestMapping("/api/meetings/{meetingId}")
@RequiredArgsConstructor
public class MeetingVocabularyController {

    private final GetMeetingVocabularyUseCase getMeetingVocabularyUseCase;
    private final RebuildMeetingVocabularyUseCase rebuildMeetingVocabularyUseCase;

    @Operation(
            summary = "커스텀 어휘 상태 조회 (STT-01)",
            description = "회의별 STT 커스텀 어휘의 생성 상태를 준다. READY 가 아니어도 녹음은 "
                    + "시작할 수 있다 — 고유명사 인식률만 낮아진다. 아직 만든 적이 없는 회의는 "
                    + "PENDING 으로 답한다(정보가 없다는 뜻이지 오류가 아니다)."
    )
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'LEADER', 'MEMBER')")
    @GetMapping("/vocabulary")
    public ApiResponse<MeetingVocabularyResponse> getVocabulary(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable Long meetingId
    ) {
        return ApiResponse.success(
                "조회 성공",
                MeetingVocabularyResponse.from(
                        getMeetingVocabularyUseCase.getVocabulary(me.getCompanyId(), meetingId)));
    }

    /*
     * STT-02 · 커스텀 어휘 재생성.
     *
     * **회의 담당자만 부를 수 있다(403).** 어휘 생성은 제공자 계정의 한정된 자원을 쓰고,
     * 계정당 개수 상한이 있다 — 참석자 아무나 반복해 누르면 그 상한을 갉아먹고 **다른 회의가
     * 어휘 없이 돌게 된다.**
     */
    @Operation(
            summary = "커스텀 어휘 재생성 (STT-02)",
            description = "참석자가 추가·변경됐거나 생성이 FAILED 일 때 다시 만든다. 회의 담당자만 "
                    + "요청할 수 있다(403) — 계정당 어휘 개수 상한이 있어 아무나 반복해 누르면 "
                    + "다른 회의가 어휘 없이 돌게 된다. 넣을 참석자가 없으면 409 다."
    )
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN', 'LEADER', 'MEMBER')")
    @PostMapping("/vocabulary/rebuild")
    // 실제 HTTP 상태도 202 여야 한다. ApiResponse.accepted 는 본문 필드만 채운다(#223 지적).
    @ResponseStatus(HttpStatus.ACCEPTED)
    public ApiResponse<MeetingVocabularyResponse> rebuildVocabulary(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal me,
            @PathVariable Long meetingId
    ) {
        return ApiResponse.accepted(
                "어휘 생성을 시작했습니다.",
                MeetingVocabularyResponse.from(rebuildMeetingVocabularyUseCase.rebuild(
                        new RebuildVocabularyCommand(me.getCompanyId(), meetingId, me.getMemberId()))));
    }
}
