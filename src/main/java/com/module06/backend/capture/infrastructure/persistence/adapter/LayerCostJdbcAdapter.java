package com.module06.backend.capture.infrastructure.persistence.adapter;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.port.out.LayerCostRepository;

/*
 * QLTY-03 계층별 토큰 집계 어댑터다.
 *
 * <h2>meeting 을 조인해 회사를 가른다</h2>
 * analysis_layer 에는 company_id 가 없다(V5.6). 컬럼을 더하는 대신 조인을 택했다 — 회의당 행이
 * 최대 10개라 조인이 작고, 마이그레이션·백필 없이 끝나며, 무엇보다 **쓰는 쪽을 한 줄도 고치지
 * 않는다**(오케스트레이터가 계층을 쓸 때마다 회사를 알아야 하게 만들지 않는다).
 *
 * <h2>기간은 created_at 으로 자른다</h2>
 * finished_at 이 아니다. 실패해 끝나지 못한 계층도 **토큰은 이미 썼기 때문**이다 — 그걸 빼면
 * 비용이 실제보다 싸게 나오고, 그 숫자로 특화 모델 전환의 손익분기점을 계산하게 된다.
 */
@Component
@RequiredArgsConstructor
public class LayerCostJdbcAdapter implements LayerCostRepository {

    /*
     * calls 는 attempt_count 합이다 — **모델 호출 수가 아니라 계층 실행 수**다. L3 는 주제마다
     * 부르지만 이 표는 회의·계층당 한 행에 토큰을 누적하므로 주제별 호출 수가 남지 않는다.
     * 토큰 합은 정확하고, 호출 수만 이 한계를 갖는다(포트 주석에 적었다).
     */
    private static final String BY_LAYER_SQL = """
            SELECT al.layer                    AS layer,
                   SUM(al.attempt_count)       AS calls,
                   SUM(al.tokens_in)           AS tokens_in,
                   SUM(al.tokens_out)          AS tokens_out
              FROM analysis_layer al
              JOIN meeting m ON m.id = al.meeting_id
             WHERE m.company_id = ?
               AND al.created_at >= ?
               AND al.created_at < ?
             GROUP BY al.layer
             ORDER BY al.layer
            """;

    /*
     * 분석이 **돈** 회의 수다. meeting 전체가 아니다 — 녹음만 하고 분석하지 않은 회의를 분모에
     * 넣으면 회의당 비용이 실제보다 싸게 나온다.
     */
    private static final String MEETING_COUNT_SQL = """
            SELECT COUNT(DISTINCT al.meeting_id)
              FROM analysis_layer al
              JOIN meeting m ON m.id = al.meeting_id
             WHERE m.company_id = ?
               AND al.created_at >= ?
               AND al.created_at < ?
            """;

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Transactional(readOnly = true)
    public List<LayerCost> costsOf(long companyId, LocalDateTime startInclusive, LocalDateTime endExclusive) {
        return jdbcTemplate.query(BY_LAYER_SQL,
                (rs, rowNum) -> new LayerCost(
                        rs.getString("layer"),
                        rs.getInt("calls"),
                        rs.getLong("tokens_in"),
                        rs.getLong("tokens_out")),
                companyId, startInclusive, endExclusive);
    }

    @Override
    @Transactional(readOnly = true)
    public int analyzedMeetingCount(long companyId, LocalDateTime startInclusive, LocalDateTime endExclusive) {
        Integer count = jdbcTemplate.queryForObject(
                MEETING_COUNT_SQL, Integer.class, companyId, startInclusive, endExclusive);
        return count != null ? count : 0;
    }
}
