package com.module06.backend.handover.presentation.api;

import com.module06.backend.handover.application.command.CreateHandoverCommand;
import com.module06.backend.handover.application.command.ReassignItemCommand;
import com.module06.backend.handover.application.command.RejectHandoverCommand;
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
import com.module06.backend.handover.domain.model.Handover;
import com.module06.backend.handover.domain.model.HandoverInsight;
import com.module06.backend.handover.domain.model.HandoverInsightKind;
import com.module06.backend.handover.domain.model.HandoverItem;
import com.module06.backend.handover.domain.model.HandoverStatus;
import com.module06.backend.handover.domain.model.HandoverType;
import com.module06.backend.global.security.AuthPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(HandoverController.class)
@AutoConfigureMockMvc(addFilters = false) // 순수 매핑 슬라이스 검증 — 시큐리티 필터(CSRF/인증) 비활성화. 인증은 B(auth) 배선 후 별도 테스트.
class HandoverControllerTest {

    private static final Long HANDOVER_ID = 1000L;
    private static final Long WRITER = 1L;
    private static final Long TEAM = 10L;
    private static final Long ACTION = 100L;
    private static final Long TARGET = 2L;
    private static final Long APPROVER = 9L;
    private static final Long COMPANY = 1L;
    private static final LocalDateTime START = LocalDateTime.of(2026, 8, 10, 9, 0);
    private static final LocalDateTime END = LocalDateTime.of(2026, 8, 20, 18, 0);
    private static final LocalDateTime ACTION_CREATED_AT = LocalDateTime.of(2026, 7, 20, 9, 0);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private CreateHandoverUseCase createHandoverUseCase;

    @MockitoBean
    private ReassignHandoverItemUseCase reassignHandoverItemUseCase;

    @MockitoBean
    private CompleteHandoverUseCase completeHandoverUseCase;

    @MockitoBean
    private FinalizeHandoverUseCase finalizeHandoverUseCase;

    @MockitoBean
    private RejectHandoverUseCase rejectHandoverUseCase;

    @MockitoBean
    private GetHandoverListUseCase getHandoverListUseCase;

    @MockitoBean
    private GetHandoverUseCase getHandoverUseCase;

    @MockitoBean
    private GetHandoverPackageUseCase getHandoverPackageUseCase;

    @MockitoBean
    private GetHandoverInsightsUseCase getHandoverInsightsUseCase;

    @MockitoBean
    private OrgQueryPort orgQueryPort;

    /*
     * 신청자·팀은 본문이 아니라 토큰에서 온다. 본문에 남의 사번/팀을 넣어도 무시되고,
     * 커맨드에는 토큰의 memberId/teamId가 실린다 — 남의 명의 대리 신청 차단.
     */
    @Test
    void createTakesWriterAndTeamFromTokenIgnoringBody() throws Exception {
        authenticateAs(WRITER, COMPANY, "MEMBER", false, TEAM);
        when(createHandoverUseCase.create(any(CreateHandoverCommand.class))).thenReturn(submitted());

        mockMvc.perform(post("/api/handovers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "writerMemberId": 999,
                                  "teamId": 888,
                                  "handoverType": "VACATION",
                                  "leaveStartAt": "2026-08-10T09:00:00",
                                  "leaveEndAt": "2026-08-20T18:00:00",
                                  "selectedActionIds": [100]
                                }
                                """))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.httpStatus").value(201))
                .andExpect(jsonPath("$.data.id").value(HANDOVER_ID))
                .andExpect(jsonPath("$.data.items[0].actionId").value(ACTION));

        ArgumentCaptor<CreateHandoverCommand> captor = ArgumentCaptor.forClass(CreateHandoverCommand.class);
        verify(createHandoverUseCase).create(captor.capture());
        assertThat(captor.getValue().writerMemberId()).isEqualTo(WRITER);
        assertThat(captor.getValue().teamId()).isEqualTo(TEAM);
        assertThat(captor.getValue().handoverType()).isEqualTo(HandoverType.VACATION);
        assertThat(captor.getValue().selectedActionIds()).containsExactly(ACTION);
    }

    @Test
    void reassignMapsPathAndBodyToCommand() throws Exception {
        when(reassignHandoverItemUseCase.reassignItem(any(ReassignItemCommand.class))).thenReturn(submitted());

        mockMvc.perform(patch("/api/handovers/{id}/items/{actionId}/reassign", HANDOVER_ID, ACTION)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"toMemberId": 2}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.httpStatus").value(200));

        ArgumentCaptor<ReassignItemCommand> captor = ArgumentCaptor.forClass(ReassignItemCommand.class);
        verify(reassignHandoverItemUseCase).reassignItem(captor.capture());
        assertThat(captor.getValue().handoverId()).isEqualTo(HANDOVER_ID);
        assertThat(captor.getValue().actionId()).isEqualTo(ACTION);
        assertThat(captor.getValue().toMemberId()).isEqualTo(TARGET);
        assertThat(captor.getValue().reassignedAt()).isNotNull();
    }

    @Test
    void completeUsesTokenMemberIdAndCurrentTime() throws Exception {
        authenticateAs(APPROVER);
        when(completeHandoverUseCase.complete(eq(HANDOVER_ID), eq(APPROVER), any(LocalDateTime.class)))
                .thenReturn(reassigned());

        mockMvc.perform(patch("/api/handovers/{id}/complete", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REASSIGNED"));

        verify(completeHandoverUseCase).complete(eq(HANDOVER_ID), eq(APPROVER), any(LocalDateTime.class));
    }

    /*
     * 승인자를 헤더로 받으면 남의 사번을 적어 그 사람 이름으로 승인할 수 있다. 승인은 감사 기록이
     * 남는 행위라(finalApproverNameSnap) 신분 위조가 곧 기록 위조가 된다. 토큰만 봐야 한다.
     */
    @Test
    void completeIgnoresSpoofedMemberIdHeader() throws Exception {
        authenticateAs(APPROVER);
        when(completeHandoverUseCase.complete(eq(HANDOVER_ID), eq(APPROVER), any(LocalDateTime.class)))
                .thenReturn(reassigned());

        mockMvc.perform(patch("/api/handovers/{id}/complete", HANDOVER_ID)
                        .header("X-Member-Id", 999L))
                .andExpect(status().isOk());

        verify(completeHandoverUseCase).complete(eq(HANDOVER_ID), eq(APPROVER), any(LocalDateTime.class));
    }

    @Test
    void finalizeResolvesApproverNameFromOrgPort() throws Exception {
        authenticateAs(APPROVER);
        when(orgQueryPort.findMember(APPROVER)).thenReturn(new OrgQueryPort.MemberSnapshot(APPROVER, "Park", "Leader"));
        when(finalizeHandoverUseCase.finalize(eq(HANDOVER_ID), eq(APPROVER), eq("Park"), any(LocalDateTime.class)))
                .thenReturn(finalized());

        mockMvc.perform(patch("/api/handovers/{id}/finalize", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FINALIZED"))
                .andExpect(jsonPath("$.data.finalApproverNameSnap").value("Park"));

        verify(orgQueryPort).findMember(APPROVER);
        verify(finalizeHandoverUseCase).finalize(eq(HANDOVER_ID), eq(APPROVER), eq("Park"), any(LocalDateTime.class));
    }

    @Test
    void finalizeIgnoresSpoofedMemberIdHeader() throws Exception {
        authenticateAs(APPROVER);
        when(orgQueryPort.findMember(APPROVER)).thenReturn(new OrgQueryPort.MemberSnapshot(APPROVER, "Park", "Leader"));
        when(finalizeHandoverUseCase.finalize(eq(HANDOVER_ID), eq(APPROVER), eq("Park"), any(LocalDateTime.class)))
                .thenReturn(finalized());

        mockMvc.perform(patch("/api/handovers/{id}/finalize", HANDOVER_ID)
                        .header("X-Member-Id", 999L))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.finalApproverNameSnap").value("Park"));

        verify(orgQueryPort).findMember(APPROVER);
    }

    @Test
    void rejectMapsReasonToCommand() throws Exception {
        when(rejectHandoverUseCase.reject(any(RejectHandoverCommand.class))).thenReturn(rejected());

        mockMvc.perform(patch("/api/handovers/{id}/reject", HANDOVER_ID)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reason": "needs more detail"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        ArgumentCaptor<RejectHandoverCommand> captor = ArgumentCaptor.forClass(RejectHandoverCommand.class);
        verify(rejectHandoverUseCase).reject(captor.capture());
        assertThat(captor.getValue().handoverId()).isEqualTo(HANDOVER_ID);
        assertThat(captor.getValue().reason()).isEqualTo("needs more detail");
    }

    /*
     * 스코프는 클라이언트 파라미터가 아니라 토큰에서 결정한다. 멤버는 본인 것만 — 쿼리파라미터로
     * 남의 사번/팀을 넣어도 무시되고, 토큰의 memberId로 SELF 스코프가 강제된다.
     */
    @Test
    void listAsMemberScopesToSelfFromToken() throws Exception {
        authenticateAs(WRITER, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverListUseCase.list(any(GetHandoverListUseCase.HandoverListQuery.class)))
                .thenReturn(List.of(summary()));

        // 남의 사번/팀을 파라미터로 밀어넣어도 스코프에 영향을 주지 못한다.
        mockMvc.perform(get("/api/handovers")
                        .param("writerMemberId", "999")
                        .param("teamId", "888"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(HANDOVER_ID));

        GetHandoverListUseCase.HandoverListQuery query = capturedQuery();
        assertThat(query.scope()).isEqualTo(GetHandoverListUseCase.Scope.SELF);
        assertThat(query.memberId()).isEqualTo(WRITER);
        assertThat(query.teamId()).isNull();
        assertThat(query.companyId()).isNull();
    }

    @Test
    void listAsLeaderScopesToOwnTeamFromToken() throws Exception {
        authenticateAs(WRITER, COMPANY, "LEADER", false, TEAM);
        when(getHandoverListUseCase.list(any(GetHandoverListUseCase.HandoverListQuery.class)))
                .thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/handovers"))
                .andExpect(status().isOk());

        GetHandoverListUseCase.HandoverListQuery query = capturedQuery();
        assertThat(query.scope()).isEqualTo(GetHandoverListUseCase.Scope.TEAM);
        assertThat(query.teamId()).isEqualTo(TEAM);
        assertThat(query.memberId()).isNull();
    }

    @Test
    void listAsOwnerScopesToCompanyFromToken() throws Exception {
        authenticateAs(APPROVER, COMPANY, "OWNER", false, null);
        when(getHandoverListUseCase.list(any(GetHandoverListUseCase.HandoverListQuery.class)))
                .thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/handovers"))
                .andExpect(status().isOk());

        GetHandoverListUseCase.HandoverListQuery query = capturedQuery();
        assertThat(query.scope()).isEqualTo(GetHandoverListUseCase.Scope.COMPANY);
        assertThat(query.companyId()).isEqualTo(COMPANY);
    }

    @Test
    void listAsAdminScopesToCompanyRegardlessOfBaseRole() throws Exception {
        // isAdmin은 기본 역할과 무관한 교차 플래그 — 멤버여도 어드민이면 회사 전체.
        authenticateAs(WRITER, COMPANY, "MEMBER", true, TEAM);
        when(getHandoverListUseCase.list(any(GetHandoverListUseCase.HandoverListQuery.class)))
                .thenReturn(List.of(summary()));

        mockMvc.perform(get("/api/handovers"))
                .andExpect(status().isOk());

        GetHandoverListUseCase.HandoverListQuery query = capturedQuery();
        assertThat(query.scope()).isEqualTo(GetHandoverListUseCase.Scope.COMPANY);
        assertThat(query.companyId()).isEqualTo(COMPANY);
    }

    private GetHandoverListUseCase.HandoverListQuery capturedQuery() {
        ArgumentCaptor<GetHandoverListUseCase.HandoverListQuery> captor =
                ArgumentCaptor.forClass(GetHandoverListUseCase.HandoverListQuery.class);
        verify(getHandoverListUseCase).list(captor.capture());
        return captor.getValue();
    }

    @Test
    void getReturnsDetailForWriterSelfScope() throws Exception {
        authenticateAs(WRITER, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(HANDOVER_ID))
                .andExpect(jsonPath("$.data.teamNameSnap").value("Platform Team"))
                .andExpect(jsonPath("$.data.items[0].actionId").value(ACTION));
    }

    @Test
    void getDeniesMemberReadingAnotherWriterHandover() throws Exception {
        authenticateAs(999L, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("HO-026"));
    }

    @Test
    void getReturnsDetailForLeaderOfSameTeam() throws Exception {
        authenticateAs(999L, COMPANY, "LEADER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(HANDOVER_ID));
    }

    @Test
    void getDeniesLeaderOfAnotherTeam() throws Exception {
        authenticateAs(999L, COMPANY, "LEADER", false, 77L);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("HO-026"));
    }

    @Test
    void getReturnsDetailForOwnerWhenWriterInCompany() throws Exception {
        authenticateAs(999L, COMPANY, "OWNER", false, 77L);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());
        when(orgQueryPort.findMemberIdsByCompany(1L)).thenReturn(List.of(WRITER));

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(HANDOVER_ID));
    }

    @Test
    void getDeniesOwnerWhenWriterOutsideCompany() throws Exception {
        authenticateAs(999L, COMPANY, "OWNER", false, 77L);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());
        when(orgQueryPort.findMemberIdsByCompany(1L)).thenReturn(List.of(12345L));

        mockMvc.perform(get("/api/handovers/{id}", HANDOVER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("HO-026"));
    }

    @Test
    void getPackageReturnsRichDetailForId() throws Exception {
        authenticateAs(WRITER, COMPANY, "LEADER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());
        when(getHandoverPackageUseCase.getPackage(eq(HANDOVER_ID), any(LocalDate.class)))
                .thenReturn(handoverPackage());

        mockMvc.perform(get("/api/handovers/{id}/package", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basicInfo.writerName").value("Kim"))
                .andExpect(jsonPath("$.data.basicInfo.note").value("인계 서술"))
                .andExpect(jsonPath("$.data.gapSummary.totalItems").value(1))
                .andExpect(jsonPath("$.data.items[0].actionId").value(ACTION))
                .andExpect(jsonPath("$.data.items[0].startAt").value("2026-07-20"))
                .andExpect(jsonPath("$.data.reassigneeGroups[0].reassigneeName").value("미배정"));

        verify(getHandoverPackageUseCase).getPackage(eq(HANDOVER_ID), any(LocalDate.class));
    }

    @Test
    void getInsightsReturnsGroupedInsightsForId() throws Exception {
        authenticateAs(WRITER, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());
        when(getHandoverInsightsUseCase.getInsights(HANDOVER_ID)).thenReturn(List.of(
                insight(1L, HandoverInsightKind.OWNERSHIP, 1),
                insight(2L, HandoverInsightKind.ORPHAN_ALERT, 2),
                insight(3L, HandoverInsightKind.ASK_WHOM, 3),
                insight(4L, HandoverInsightKind.CONTEXT_TIMELINE, 4)
        ));

        mockMvc.perform(get("/api/handovers/{id}/insights", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownership[0].kind").value("OWNERSHIP"))
                .andExpect(jsonPath("$.data.ownership[0].payload").value("{\"rank\":1}"))
                .andExpect(jsonPath("$.data.orphanAlert[0].kind").value("ORPHAN_ALERT"))
                .andExpect(jsonPath("$.data.askWhom[0].kind").value("ASK_WHOM"))
                .andExpect(jsonPath("$.data.contextTimeline[0].kind").value("CONTEXT_TIMELINE"));

        verify(getHandoverInsightsUseCase).getInsights(HANDOVER_ID);
    }

    @Test
    void getInsightsDeniesMemberReadingAnotherWriterHandover() throws Exception {
        authenticateAs(999L, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());

        mockMvc.perform(get("/api/handovers/{id}/insights", HANDOVER_ID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errorCode").value("HO-026"));
    }

    @Test
    void getInsightsReturnsEmptyGroupedResponse() throws Exception {
        authenticateAs(WRITER, COMPANY, "MEMBER", false, TEAM);
        when(getHandoverUseCase.get(HANDOVER_ID)).thenReturn(submitted());
        when(getHandoverInsightsUseCase.getInsights(HANDOVER_ID)).thenReturn(List.of());

        mockMvc.perform(get("/api/handovers/{id}/insights", HANDOVER_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ownership").isEmpty())
                .andExpect(jsonPath("$.data.orphanAlert").isEmpty())
                .andExpect(jsonPath("$.data.askWhom").isEmpty())
                .andExpect(jsonPath("$.data.contextTimeline").isEmpty());
    }

    private static GetHandoverPackageUseCase.HandoverPackage handoverPackage() {
        GetHandoverPackageUseCase.Item item = new GetHandoverPackageUseCase.Item(
                ACTION, "Action", "TODO", LocalDate.of(2026, 8, 30), LocalDate.of(2026, 7, 20), "PRJ", "Meeting");
        return new GetHandoverPackageUseCase.HandoverPackage(
                new GetHandoverPackageUseCase.BasicInfo("Kim", "Manager", TEAM, HandoverType.VACATION,
                        START, END, null, "인계 서술"),
                new GetHandoverPackageUseCase.GapSummary(1, 1, 1),
                List.of(item),
                List.of(new GetHandoverPackageUseCase.ContextCard(ACTION, "Action", "Content")),
                List.of(new GetHandoverPackageUseCase.MeetingHistory(
                        500L, LocalDate.of(2026, 8, 1), List.of("Kim"), "decision", "actions")),
                List.of(new GetHandoverPackageUseCase.ReassigneeGroup(null, "미배정", List.of(item))));
    }

    private static HandoverInsight insight(Long id, HandoverInsightKind kind, int sortOrder) {
        return HandoverInsight.restore(id, HANDOVER_ID, ACTION, kind, "{\"rank\":" + sortOrder + "}", sortOrder,
                LocalDateTime.of(2026, 8, 1, 9, 0), LocalDateTime.of(2026, 8, 1, 9, 0));
    }

    private static GetHandoverListUseCase.HandoverSummary summary() {
        return new GetHandoverListUseCase.HandoverSummary(HANDOVER_ID, WRITER, "Kim", "Manager", TEAM,
                HandoverType.VACATION, HandoverStatus.SUBMITTED, START, END, null, 1, 1, 0);
    }

    private static Handover submitted() {
        return Handover.restore(HANDOVER_ID, WRITER, TEAM, "Platform Team", HandoverType.VACATION, HandoverStatus.SUBMITTED,
                START, END, null, "Kim", "Manager", null, null, null, null, null, null,
                null, null, 1L, List.of(item()));
    }

    private static Handover reassigned() {
        return Handover.restore(HANDOVER_ID, WRITER, TEAM, "Platform Team", HandoverType.VACATION, HandoverStatus.REASSIGNED,
                START, END, null, "Kim", "Manager", null, APPROVER, "Park", LocalDateTime.now(), null,
                null, null, null, 1L, List.of(item()));
    }

    private static Handover finalized() {
        return Handover.restore(HANDOVER_ID, WRITER, TEAM, "Platform Team", HandoverType.VACATION, HandoverStatus.FINALIZED,
                START, END, null, "Kim", "Manager", null, APPROVER, "Park", LocalDateTime.now(), null,
                LocalDateTime.now(), APPROVER, "Park", 1L, List.of(item()));
    }

    private static Handover rejected() {
        return Handover.restore(HANDOVER_ID, WRITER, TEAM, "Platform Team", HandoverType.VACATION, HandoverStatus.REJECTED,
                START, END, null, "Kim", "Manager", null, null, null, null, "needs more detail",
                null, null, null, 1L, List.of(item()));
    }

    private static HandoverItem item() {
        return HandoverItem.create(ACTION, "Action", "TODO", "PRJ", "TEAM",
                LocalDate.of(2026, 8, 30), ACTION_CREATED_AT, 500L, "Meeting", "Content", true);
    }

    /** 필터를 끈 슬라이스라 컨텍스트를 직접 심는다 — 다른 도메인의 컨트롤러 테스트와 같은 방식. */
    private void authenticateAs(Long memberId) {
        authenticateAs(memberId, COMPANY, "LEADER", false, TEAM);
    }

    private void authenticateAs(Long memberId, Long companyId, String role, boolean isAdmin, Long teamId) {
        AuthPrincipal principal = new AuthPrincipal(memberId, companyId, role, isAdmin, teamId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of()));
    }

    @AfterEach
    void clearAuthentication() {
        SecurityContextHolder.clearContext();
    }
}
