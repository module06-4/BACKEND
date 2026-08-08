package com.module06.backend.handover.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.module06.backend.handover.application.command.FinalizeHandoverInsightsCommand;
import com.module06.backend.handover.application.port.out.ActionReassignPort;
import com.module06.backend.handover.application.port.out.HandoverInsightPort;
import com.module06.backend.handover.application.port.out.MeetingQueryPort;
import com.module06.backend.handover.application.port.out.OrgQueryPort;
import com.module06.backend.handover.domain.model.HandoverInsight;
import com.module06.backend.handover.domain.model.HandoverInsightKind;
import com.module06.backend.handover.domain.model.HandoverType;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

class HandoverInsightFinalizeServiceTest {

    private static final Long HANDOVER_ID = 1L;
    private static final Long DEPARTURE_MEMBER_ID = 10L;

    @Test
    void finalizeInsightsAssemblesFourSnapshotKinds() throws Exception {
        CapturingInsightPort insightPort = new CapturingInsightPort();
        ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        HandoverInsightFinalizeService service = new HandoverInsightFinalizeService(
            insightPort,
            provider(new FakeActionReassignPort()),
            provider(new FakeMeetingQueryPort()),
            provider(new FakeOrgQueryPort())
        );

        service.finalizeInsights(new FinalizeHandoverInsightsCommand(HANDOVER_ID, DEPARTURE_MEMBER_ID));

        assertThat(insightPort.handoverId).isEqualTo(HANDOVER_ID);
        assertThat(insightPort.insights)
            .extracting(HandoverInsight::getKind)
            .containsExactly(
                HandoverInsightKind.OWNERSHIP,
                HandoverInsightKind.ORPHAN_ALERT,
                HandoverInsightKind.ASK_WHOM,
                HandoverInsightKind.CONTEXT_TIMELINE
            );

        Map<String, Object> ownership = readFirstPayload(objectMapper, insightPort.insights.get(0));
        assertThat(ownership)
            .containsEntry("projectId", 101)
            .containsEntry("hostedMeetingCount", 1)
            .containsEntry("attendedMeetingCount", 2)
            .containsEntry("assignedActionCount", 1);
        assertThat(ownership).doesNotContainKey("orderingWeight");

        Map<String, Object> orphan = readFirstPayload(objectMapper, insightPort.insights.get(1));
        assertThat(orphan)
            .containsEntry("actionId", 200)
            .containsEntry("basis", "source_meeting_hosted")
            .containsEntry("recommendation", "successor_assignment_recommended");

        Map<String, Object> askWhom = readPayload(objectMapper, insightPort.insights.get(2));
        assertThat(askWhom).containsEntry("actionId", 100);
        assertThat((List<?>) askWhom.get("originators")).hasSize(2);
        assertThat((List<?>) askWhom.get("executors")).hasSize(2);

        Map<String, Object> timeline = readPayload(objectMapper, insightPort.insights.get(3));
        assertThat(timeline)
            .containsEntry("actionId", 100)
            .containsEntry("sourceMeetingId", 1002);
        assertThat((List<?>) timeline.get("meetings")).hasSize(2);
    }

    private static Map<String, Object> readPayload(ObjectMapper objectMapper, HandoverInsight insight) throws Exception {
        return objectMapper.readValue(insight.getPayload(), new TypeReference<>() {
        });
    }

    private static Map<String, Object> readFirstPayload(ObjectMapper objectMapper, HandoverInsight insight) throws Exception {
        List<Map<String, Object>> payload = objectMapper.readValue(insight.getPayload(), new TypeReference<>() {
        });
        return payload.get(0);
    }

    private static <T> ObjectProvider<T> provider(T value) {
        return new ObjectProvider<>() {
            @Override
            public T getObject() {
                return value;
            }

            @Override
            public T getObject(Object... args) {
                return value;
            }

            @Override
            public T getIfAvailable() {
                return value;
            }

            @Override
            public T getIfUnique() {
                return value;
            }
        };
    }

    private static class CapturingInsightPort implements HandoverInsightPort {

        private Long handoverId;
        private List<HandoverInsight> insights = List.of();

        @Override
        public void replaceAllForHandover(Long handoverId, List<HandoverInsight> insights) {
            this.handoverId = handoverId;
            this.insights = List.copyOf(insights);
        }

        @Override
        public List<HandoverInsight> findByHandoverId(Long handoverId) {
            return insights;
        }
    }

    private static class FakeActionReassignPort implements ActionReassignPort {

        @Override
        public List<HandoverableAction> findHandoverableActions(Long fromMemberId) {
            assertThat(fromMemberId).isEqualTo(DEPARTURE_MEMBER_ID);
            return List.of(new HandoverableAction(100L, "Personal action", "PRJ", 101L, "PERSONAL", "TODO",
                    null, null, 1002L, "Review", "Description"));
        }

        @Override
        public List<TeamActionForDeparture> findTeamActionsForDeparture(Long memberId) {
            assertThat(memberId).isEqualTo(DEPARTURE_MEMBER_ID);
            return List.of(new TeamActionForDeparture(200L, "Team action", 101L, 1002L, "IN_PROGRESS", 7L));
        }

        // 베이스 병합으로 합류한 계약 — 인사이트 테스트에서는 사용하지 않음.
        @Override
        public List<HandoverableAction> findHandoverableActions(Long memberId, HandoverType type) {
            return List.of();
        }

        @Override
        public void reassign(Long actionId, Long fromMemberId, Long toMemberId) {
        }
    }

    private static class FakeMeetingQueryPort implements MeetingQueryPort {

        @Override
        public List<ProjectMeeting> findProjectMeetingsOrdered(Long projectId) {
            assertThat(projectId).isEqualTo(101L);
            return List.of(
                new ProjectMeeting(1001L, 101L, 20L, "Kickoff", LocalDateTime.of(2026, 3, 2, 10, 0)),
                new ProjectMeeting(1002L, 101L, DEPARTURE_MEMBER_ID, "Review", LocalDateTime.of(2026, 3, 15, 10, 0))
            );
        }

        @Override
        public List<MeetingTopic> findMeetingTopics(List<Long> meetingIds) {
            assertThat(meetingIds).containsExactly(1001L, 1002L);
            return List.of(
                new MeetingTopic(1001L, 501L, null, "MAIN", "Background discussion", 0),
                new MeetingTopic(1002L, 502L, null, "MAIN", "Implementation context", 0)
            );
        }

        @Override
        public List<MeetingAttendee> findMeetingAttendees(List<Long> meetingIds) {
            assertThat(meetingIds).containsExactly(1001L, 1002L);
            List<MeetingAttendee> attendees = new ArrayList<>();
            attendees.add(new MeetingAttendee(1001L, DEPARTURE_MEMBER_ID));
            attendees.add(new MeetingAttendee(1001L, 20L));
            attendees.add(new MeetingAttendee(1001L, 21L));
            attendees.add(new MeetingAttendee(1002L, DEPARTURE_MEMBER_ID));
            attendees.add(new MeetingAttendee(1002L, 30L));
            attendees.add(new MeetingAttendee(1002L, 31L));
            return attendees;
        }

        // 베이스 병합으로 합류한 계약 — 인사이트 테스트에서는 사용하지 않음.
        @Override
        public MeetingHistory findMeeting(Long meetingId) {
            return null;
        }
    }

    private static class FakeOrgQueryPort implements OrgQueryPort {

        @Override
        public List<MemberSummary> findMembers(List<Long> memberIds) {
            assertThat(memberIds).containsExactly(20L, 21L, 30L, 31L);
            return memberIds.stream()
                .map(memberId -> new MemberSummary(memberId, "Member " + memberId, "Position " + memberId))
                .toList();
        }

        // 베이스 병합으로 합류한 계약 — 인사이트 테스트에서는 사용하지 않음.
        @Override
        public Long findTeamLeaderId(Long teamId) {
            return null;
        }

        @Override
        public MemberSnapshot findMember(Long memberId) {
            return null;
        }

        @Override
        public List<ReassignCandidate> findReassignCandidates(Long teamId, Long excludeMemberId) {
            return List.of();
        }

        @Override
        public List<Long> findMemberIdsByCompany(Long companyId) {
            return List.of();
        }
    }
}
