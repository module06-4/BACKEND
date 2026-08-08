package com.module06.backend.capture.infrastructure.persistence.adapter;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.port.out.QualityMetricsRepository;

/*
 * QLTY-02 **게이트 지표**의 원재료를 세는 어댑터다.
 *
 * precision·recall 은 여기서 세지 않는다 — 그 둘은 동결된 gold set 라벨로 채점한다. 현재
 * review_log 로 세면 동결 뒤 판정을 바꾸는 순간 과거 표본의 수치가 달라진다(포트 주석).
 *
 * <h2>판정은 액션마다 마지막 것만 본다</h2>
 * review_log 는 이력이다 — 사람이 반려했다가 다시 확인할 수 있다. 전부 세면 한 액션이 여러 번
 * 채점되어 **판정을 많이 바꾼 액션일수록 지표에 크게 반영된다.**
 */
@Component
@RequiredArgsConstructor
public class QualityMetricsJdbcAdapter implements QualityMetricsRepository {

    /* 게이트 성적. 자동 확정한 것 중 사람이 고치거나 반려한 수를 함께 센다. */
    private static final String GATE_TALLY_SQL = """
            SELECT COUNT(*)                                                        AS tuple_count,
                   SUM(CASE WHEN t.gate_auto_confirmed = TRUE THEN 1 ELSE 0 END)   AS auto_confirmed,
                   SUM(CASE WHEN t.gate_auto_confirmed = TRUE
                             AND rl.decision IS NOT NULL
                             AND rl.decision <> 'CONFIRM' THEN 1 ELSE 0 END)       AS auto_confirmed_wrong
              FROM meeting_assignment_tuple t
              LEFT JOIN review_log rl
                     ON rl.target_type = 'ACTION'
                    AND rl.target_id = t.action_id
                    AND rl.id = (SELECT MAX(last.id)
                                   FROM review_log last
                                  WHERE last.target_type = 'ACTION'
                                    AND last.target_id = t.action_id)
             WHERE t.company_id = ?
               AND t.meeting_id IN (%s)
            """;

    /*
     * 채점 대상이 어느 모델·프롬프트의 출력인가.
     *
     * 최신 하나를 본다. 버전이 섞인 표본은 지표를 비교할 수 없게 만드는데, **섞였는지까지
     * 답하려면 응답 모양이 바뀐다**(후속). 지금은 값 하나로 보여주는 것이 최선이다.
     */
    private static final String VERSION_SQL = """
            SELECT t.model_name, t.prompt_version
              FROM meeting_assignment_tuple t
             WHERE t.company_id = ?
               AND t.meeting_id IN (%s)
               AND t.model_name IS NOT NULL
             ORDER BY t.id DESC
             LIMIT 1
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public GateTally gateTally(long companyId, List<Long> meetingIds) {
        if (meetingIds == null || meetingIds.isEmpty()) {
            /*
             * 표본이 없다. **0 으로 채운 결과를 준다** — 여기서 예외를 올리면 "아직 정답지를
             * 안 만들었다"가 오류로 보인다. 비율은 서비스가 null 로 만든다(못 잰다는 뜻).
             */
            return GateTally.empty();
        }

        String placeholders = String.join(",", java.util.Collections.nCopies(meetingIds.size(), "?"));
        Object[] args = argsOf(companyId, meetingIds);

        int[] counts = jdbcTemplate.query(GATE_TALLY_SQL.formatted(placeholders), rs -> {
            if (!rs.next()) {
                return new int[] {0, 0, 0};
            }
            return new int[] {
                    rs.getInt("tuple_count"),
                    rs.getInt("auto_confirmed"),
                    rs.getInt("auto_confirmed_wrong")};
        }, args);

        String[] version = jdbcTemplate.query(VERSION_SQL.formatted(placeholders), rs -> {
            if (!rs.next()) {
                return new String[] {null, null};
            }
            return new String[] {rs.getString("model_name"), rs.getString("prompt_version")};
        }, args);

        return new GateTally(counts[0], counts[1], counts[2], version[0], version[1]);
    }

    /* companyId 뒤에 회의 id 를 이어 붙인다 — 두 쿼리가 같은 인자 순서를 쓴다. */
    private Object[] argsOf(long companyId, List<Long> meetingIds) {
        Object[] args = new Object[meetingIds.size() + 1];
        args[0] = companyId;
        for (int i = 0; i < meetingIds.size(); i++) {
            args[i + 1] = meetingIds.get(i);
        }
        return args;
    }
}
