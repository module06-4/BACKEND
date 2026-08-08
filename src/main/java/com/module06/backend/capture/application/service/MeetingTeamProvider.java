package com.module06.backend.capture.application.service;

import java.util.Optional;

/*
 * 회의가 속한 팀(부서)을 읽는다. 토큰 미터링 원장을 부서 단위로 집계하려면 이 값이 필요하다 —
 * 원장에 teamId 가 없으면 대시보드는 회사 실측만 보여주고 부서 breakdown 을 만들 수 없다.
 *
 * 회의 정보의 주인은 D(회의) 도메인이다. 여기서는 읽기만 한다 —
 * {@link MeetingDateProvider} · {@link MeetingProjectProvider} 와 같은 방식이다.
 * (보안 관문 MeetingAccessPort 에 얹지 않는 이유: 그쪽은 "이 회의가 그 회사 것인가"만 묻는
 *  관문이고, 여기에 조회 책임을 섞으면 관문의 단일 책임이 흐려진다.)
 */
public interface MeetingTeamProvider {

    /*
     * @return 회의가 속한 팀 id. **비어 있으면 회사 단위로만 기록한다.**
     *         meeting.team_id 는 nullable 이고(OWNER 개설 회의는 NULL), 회의 행을 못 읽어도
     *         비어 있다 — 둘 다 부서 breakdown 없이 회사 실측만 남기는 정상 경로다.
     */
    Optional<Long> teamIdOf(long meetingId);
}
