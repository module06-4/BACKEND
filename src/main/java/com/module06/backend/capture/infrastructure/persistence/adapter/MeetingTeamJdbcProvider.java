package com.module06.backend.capture.infrastructure.persistence.adapter;

import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.service.MeetingTeamProvider;

/*
 * meeting 에서 팀(부서)을 읽는다.
 *
 * JdbcTemplate 을 쓰는 이유는 {@link MeetingProjectJdbcProvider} 와 같다 — meeting 은 D(회의)
 * 도메인 소유이고, JPA 엔티티로 매핑하면 같은 테이블에 매핑이 여럿 생긴다(2026-08-05 에 테스트
 * 9건을 깨뜨린 사고다). 읽기 쿼리 하나로 끝낸다.
 *
 * 회사 스코프를 조건에 넣지 않는 이유도 MeetingProjectJdbcProvider 와 같다 — 호출 경로가 이미
 * 회사 스코프 안에서 도는 분석 실행(A)이고, 여기서 다시 회사를 요구하면 쓰지 않는 인자를 들고
 * 다닌다. 대신 **이 클래스를 그 경로 밖에서 부르지 않는다.**
 */
@Component
@RequiredArgsConstructor
public class MeetingTeamJdbcProvider implements MeetingTeamProvider {

    private static final String SQL = """
            SELECT m.team_id AS team_id
              FROM meeting m
             WHERE m.id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public Optional<Long> teamIdOf(long meetingId) {
        return jdbcTemplate.query(SQL,
                rs -> {
                    if (!rs.next()) {
                        return Optional.<Long>empty();
                    }
                    long value = rs.getLong("team_id");
                    // team_id 는 nullable(OWNER 개설 회의는 NULL) — 원시형 게터는 NULL 을 0 으로
                    // 돌려주므로 wasNull() 로 갈라야 teamId=0 인 유령 팀이 원장에 새지 않는다.
                    return rs.wasNull() ? Optional.<Long>empty() : Optional.of(value);
                },
                meetingId);
    }
}
