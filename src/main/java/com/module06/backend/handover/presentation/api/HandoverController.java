package com.module06.backend.handover.presentation.api;

import com.module06.backend.global.response.ApiResponse;
import com.module06.backend.global.exception.BusinessException;
import com.module06.backend.global.security.AuthPrincipal;
import com.module06.backend.handover.application.port.out.OrgQueryPort;
import com.module06.backend.handover.application.usecase.CompleteHandoverUseCase;
import com.module06.backend.handover.application.usecase.CreateHandoverUseCase;
import com.module06.backend.handover.application.usecase.FinalizeHandoverUseCase;
import com.module06.backend.handover.application.usecase.GetHandoverInsightsUseCase;
import com.module06.backend.handover.application.usecase.GetHandoverListUseCase;
import com.module06.backend.handover.application.usecase.GetHandoverPackageUseCase;
import com.module06.backend.handover.application.usecase.GetHandoverUseCase;
import com.module06.backend.handover.application.usecase.ReassignHandoverItemUseCase;
import com.module06.backend.handover.application.usecase.RejectHandoverUseCase;
import com.module06.backend.handover.domain.exception.HandoverErrorCode;
import com.module06.backend.handover.domain.model.Handover;
import com.module06.backend.handover.domain.model.HandoverStatus;
import com.module06.backend.handover.presentation.api.dto.request.CreateHandoverRequest;
import com.module06.backend.handover.presentation.api.dto.request.ReassignItemRequest;
import com.module06.backend.handover.presentation.api.dto.request.RejectHandoverRequest;
import com.module06.backend.handover.presentation.api.dto.response.HandoverInsightResponse;
import com.module06.backend.handover.presentation.api.dto.response.HandoverPackageResponse;
import com.module06.backend.handover.presentation.api.dto.response.HandoverResponse;
import com.module06.backend.handover.presentation.api.dto.response.HandoverSummaryResponse;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/handovers")
public class HandoverController {

    private final CreateHandoverUseCase createHandoverUseCase;
    private final ReassignHandoverItemUseCase reassignHandoverItemUseCase;
    private final CompleteHandoverUseCase completeHandoverUseCase;
    private final FinalizeHandoverUseCase finalizeHandoverUseCase;
    private final RejectHandoverUseCase rejectHandoverUseCase;
    private final GetHandoverListUseCase getHandoverListUseCase;
    private final GetHandoverUseCase getHandoverUseCase;
    private final GetHandoverPackageUseCase getHandoverPackageUseCase;
    private final GetHandoverInsightsUseCase getHandoverInsightsUseCase;
    private final OrgQueryPort orgQueryPort;

    public HandoverController(CreateHandoverUseCase createHandoverUseCase,
                              ReassignHandoverItemUseCase reassignHandoverItemUseCase,
                              CompleteHandoverUseCase completeHandoverUseCase,
                              FinalizeHandoverUseCase finalizeHandoverUseCase,
                              RejectHandoverUseCase rejectHandoverUseCase,
                              GetHandoverListUseCase getHandoverListUseCase,
                              GetHandoverUseCase getHandoverUseCase,
                              GetHandoverPackageUseCase getHandoverPackageUseCase,
                              GetHandoverInsightsUseCase getHandoverInsightsUseCase,
                              OrgQueryPort orgQueryPort) {
        this.createHandoverUseCase = createHandoverUseCase;
        this.reassignHandoverItemUseCase = reassignHandoverItemUseCase;
        this.completeHandoverUseCase = completeHandoverUseCase;
        this.finalizeHandoverUseCase = finalizeHandoverUseCase;
        this.rejectHandoverUseCase = rejectHandoverUseCase;
        this.getHandoverListUseCase = getHandoverListUseCase;
        this.getHandoverUseCase = getHandoverUseCase;
        this.getHandoverPackageUseCase = getHandoverPackageUseCase;
        this.getHandoverInsightsUseCase = getHandoverInsightsUseCase;
        this.orgQueryPort = orgQueryPort;
    }

    /*
     * 조회 범위는 클라이언트 파라미터가 아니라 토큰에서 서버측으로 결정한다. 예전엔 writerMemberId/teamId를
     * 쿼리파라미터로 받아, 남의 사번/팀을 적으면 그 사람의 인수인계를 볼 수 있는 구멍이 있었다.
     * 규칙: 멤버→본인, 리더→자기 팀, 오너·어드민→회사 전체. status만 클라이언트 필터로 남긴다.
     */
    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<List<HandoverSummaryResponse>> list(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
            @RequestParam(required = false) HandoverStatus status) {
        List<HandoverSummaryResponse> data = getHandoverListUseCase
                .list(scopeFor(principal, status)).stream()
                .map(HandoverSummaryResponse::from)
                .toList();
        return ApiResponse.success("인수인계 목록을 조회했습니다.", data);
    }

    private GetHandoverListUseCase.HandoverListQuery scopeFor(AuthPrincipal principal, HandoverStatus status) {
        if (principal.isAdmin() || "OWNER".equals(principal.authority())) {
            return GetHandoverListUseCase.HandoverListQuery.company(principal.companyId(), status);
        }
        if ("LEADER".equals(principal.authority())) {
            return GetHandoverListUseCase.HandoverListQuery.team(principal.teamId(), status);
        }
        return GetHandoverListUseCase.HandoverListQuery.self(principal.memberId(), status);
    }

    // 상세 = PDF용 전 필드. 스코프 게이트(assertCanRead)로 본인/자기팀/회사 밖 접근을 코드 레벨에서 막는다.
    @GetMapping("/{id}")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HandoverResponse> get(@PathVariable Long id,
                                             @Parameter(hidden = true)
                                             @AuthenticationPrincipal AuthPrincipal principal) {
        Handover handover = getHandoverUseCase.get(id);
        assertCanRead(handover, principal);
        return ApiResponse.success("인수인계 상세를 조회했습니다.", HandoverResponse.from(handover));
    }

    /*
     * 상세 "패키지" = FE 인수인계 상세 화면 전용 리치 뷰(액션 타임라인·인계 패키지·회의 맥락).
     * 단순 aggregate를 주는 GET /{id}와 별개 경로로 공존한다. 스코프 게이트는 get()과 동일(assertCanRead).
     * dueSoon(마감 임박) 기준일 = 요청 시점(서버 오늘).
     */
    @GetMapping("/{id}/package")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HandoverPackageResponse> getPackage(@PathVariable Long id,
                                                           @Parameter(hidden = true)
                                                           @AuthenticationPrincipal AuthPrincipal principal) {
        assertCanRead(getHandoverUseCase.get(id), principal);
        GetHandoverPackageUseCase.HandoverPackage pkg = getHandoverPackageUseCase.getPackage(id, LocalDate.now());
        return ApiResponse.success("인수인계 상세 패키지를 조회했습니다.", HandoverPackageResponse.from(pkg));
    }

    @GetMapping("/{id}/insights")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HandoverInsightResponse> getInsights(@PathVariable Long id,
                                                            @Parameter(hidden = true)
                                                            @AuthenticationPrincipal AuthPrincipal principal) {
        assertCanRead(getHandoverUseCase.get(id), principal);
        return ApiResponse.success("인수인계 인사이트를 조회했습니다.",
                HandoverInsightResponse.from(getHandoverInsightsUseCase.getInsights(id)));
    }

    // 신청자·팀은 본문이 아니라 토큰에서 — 남의 명의로 대신 신청하는 것을 원천 차단.
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HandoverResponse> create(@Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal principal,
                                                @Valid @RequestBody CreateHandoverRequest request) {
        Handover handover = createHandoverUseCase.create(
                request.toCommand(principal.getMemberId(), principal.getTeamId()));
        return ApiResponse.created("Handover created.", HandoverResponse.from(handover));
    }

    @PatchMapping("/{id}/items/{actionId}/reassign")
    @PreAuthorize("isAuthenticated()")
    public ApiResponse<HandoverResponse> reassignItem(@PathVariable Long id,
                                                      @PathVariable Long actionId,
                                                      @Valid @RequestBody ReassignItemRequest request) {
        Handover handover = reassignHandoverItemUseCase.reassignItem(
                request.toCommand(id, actionId, LocalDateTime.now())
        );
        return ApiResponse.success("Handover item reassigned.", HandoverResponse.from(handover));
    }

    /*
     * 승인자를 토큰에서 꺼낸다. 헤더로 받으면 남의 사번을 적어 그 사람 이름으로 승인할 수 있고,
     * 승인은 감사 기록이 남는 행위라(finalApproverNameSnap) 신분 위조가 곧 기록 위조가 된다.
     */
    // 중간 승인(재분배 확정)은 팀장 단계. 어느 팀 팀장인지(작성자 팀리더 일치)는 서비스가 볼 몫 — B(findTeamLeaderId) 배선 후.
    @PatchMapping("/{id}/complete")
    @PreAuthorize("hasAnyRole('LEADER', 'OWNER', 'ADMIN')")
    public ApiResponse<HandoverResponse> complete(@PathVariable Long id,
                                                  @Parameter(hidden = true)
                                                  @AuthenticationPrincipal(expression = "memberId") Long memberId) {
        Handover handover = completeHandoverUseCase.complete(id, memberId, LocalDateTime.now());
        return ApiResponse.success("Handover completed.", HandoverResponse.from(handover));
    }

    // 최종 승인 = 오너·어드민. 같은 회사 건인지(크로스컴퍼니 차단)는 서비스가 볼 몫 — B(findMember) 배선 후.
    @PatchMapping("/{id}/finalize")
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    public ApiResponse<HandoverResponse> finalize(@PathVariable Long id,
                                                  @Parameter(hidden = true)
                                                  @AuthenticationPrincipal(expression = "memberId") Long memberId) {
        OrgQueryPort.MemberSnapshot approver = orgQueryPort.findMember(memberId);
        Handover handover = finalizeHandoverUseCase.finalize(id, memberId, approver.name(), LocalDateTime.now());
        return ApiResponse.success("Handover finalized.", HandoverResponse.from(handover));
    }

    // 반려는 승인 라인(팀장·오너·어드민)만.
    @PatchMapping("/{id}/reject")
    @PreAuthorize("hasAnyRole('LEADER', 'OWNER', 'ADMIN')")
    public ApiResponse<HandoverResponse> reject(@PathVariable Long id,
                                                @Valid @RequestBody RejectHandoverRequest request) {
        Handover handover = rejectHandoverUseCase.reject(request.toCommand(id));
        return ApiResponse.success("Handover rejected.", HandoverResponse.from(handover));
    }

    /*
     * 상세 조회 스코프 게이트 — list의 scopeFor 규칙과 동일: 오너·어드민=회사 전체, 리더=자기 팀, 그 외=본인.
     * findById를 그냥 반환하면 id만 바꿔 남의 퇴사 사유·재분배 명단을 열람할 수 있어 코드 레벨로 막는다.
     */
    private void assertCanRead(Handover handover, AuthPrincipal principal) {
        if (principal == null) {
            throw new BusinessException(HandoverErrorCode.HO_ACCESS_DENIED);
        }
        if (hasCompanyScope(principal)
                && principal.companyId() != null
                && orgQueryPort.findMemberIdsByCompany(principal.companyId()).contains(handover.getWriterMemberId())) {
            return;
        }
        if ("LEADER".equals(principal.authority()) && handover.getTeamId().equals(principal.teamId())) {
            return;
        }
        if (handover.getWriterMemberId().equals(principal.memberId())) {
            return;
        }
        throw new BusinessException(HandoverErrorCode.HO_ACCESS_DENIED);
    }

    private boolean hasCompanyScope(AuthPrincipal principal) {
        return principal.isAdmin() || "OWNER".equals(principal.authority());
    }
}
