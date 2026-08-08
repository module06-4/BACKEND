package com.module06.backend.capture.infrastructure.persistence.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/*
 * quality_gold_set(V5.11) 매핑이다.
 *
 * <h2>고치는 메서드가 없다 — 일부러다</h2>
 * 동결된 정답지를 수정하면 그걸로 잰 이전 측정치가 전부 무의미해진다. 재라벨링은 version 을
 * 올린 **새 행**이고, 그래서 이 엔티티에는 값을 바꾸는 메서드가 하나도 없다. MySQL 로는
 * "동결 행 UPDATE 금지"를 제약으로 표현할 수 없어(트리거가 필요하다) 그 규칙을 여기서 지킨다.
 */
@Entity
@Table(name = "quality_gold_set")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class QualityGoldSetJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "company_id", nullable = false)
    private Long companyId;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    /* 재라벨링마다 +1. 기존 버전은 지우지 않는다 — 과거 측정치의 정답지다. */
    @Column(name = "version", nullable = false)
    private int version;

    /* 사람이 전량 라벨링한 정답 tuple 목록(담당자·기한·근거 발화). */
    @Column(name = "labeled_actions", nullable = false, columnDefinition = "json")
    private String labeledActions;

    /* 결정·논의·블로커 정답. L3·L3.5 채점용이라 아직 비어 있을 수 있다. */
    @Column(name = "labeled_items", columnDefinition = "json")
    private String labeledItems;

    @Column(name = "frozen_at", nullable = false)
    private LocalDateTime frozenAt;

    @Column(name = "frozen_by", nullable = false)
    private Long frozenBy;

    @Column(name = "note", length = 500)
    private String note;

    public static QualityGoldSetJpaEntity frozen(long companyId, long meetingId, int version,
                                                 String labeledActions, String labeledItems,
                                                 long frozenBy, String note, LocalDateTime now) {
        QualityGoldSetJpaEntity entity = new QualityGoldSetJpaEntity();
        entity.companyId = companyId;
        entity.meetingId = meetingId;
        entity.version = version;
        entity.labeledActions = labeledActions;
        entity.labeledItems = labeledItems;
        entity.frozenAt = now;
        entity.frozenBy = frozenBy;
        entity.note = note;
        return entity;
    }
}
