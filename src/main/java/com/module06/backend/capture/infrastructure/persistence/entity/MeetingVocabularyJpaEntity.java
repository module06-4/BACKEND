package com.module06.backend.capture.infrastructure.persistence.entity;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

import com.module06.backend.capture.domain.model.VocabularyStatus;

/*
 * meeting_vocabulary(V5.19) 매핑이다. 회의당 하나다(UNIQUE).
 *
 * <h2>재생성이 이전 결과를 지우지 않는다</h2>
 * 재생성이 도는 동안에도 **제공자에는 이전 어휘가 그대로 살아 있다.** phraseCount·builtAt 을
 * 0·null 로 비우면 화면이 "어휘 없음"으로 보여주는데 실제로는 지난 어휘가 쓰이고 있고, 사람이
 * 인식률 문제를 어휘 탓으로 잘못 짚게 된다. 그래서 status 만 PENDING 으로 바꾼다.
 */
@Entity
@Table(name = "meeting_vocabulary")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MeetingVocabularyJpaEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "meeting_id", nullable = false)
    private Long meetingId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private VocabularyStatus status;

    /* 마지막으로 **성공한** 생성의 단어 수. 재생성 중에도 남는다. */
    @Column(name = "phrase_count", nullable = false)
    private int phraseCount;

    /*
     * 제공자 리소스 이름. **계정당 어휘 개수 상한이 있어 정리가 필수**인데, 지우려면 이름을
     * 알아야 한다. STT 제출 때 참조하는 값이기도 하다 — 규칙으로 매번 다시 만들면 규칙이
     * 바뀌는 순간 예전 회의의 어휘를 못 찾는다.
     */
    @Column(name = "provider_vocabulary_name", length = 200)
    private String providerVocabularyName;

    /*
     * 재생성 중인 리소스 이름.
     *
     * **활성 이름을 덮지 않는 이유** — 재생성이 도는 동안 제공자에는 이전 어휘가 살아 있고
     * 실제로 그게 쓰인다. 새 이름으로 덮으면 이전 리소스 이름이 사라져 그것만 영영 못 지우고,
     * 재생성을 반복할수록 지울 수 없는 리소스가 쌓여 **계정 상한이 누수된다**(CodeRabbit PR #241).
     *
     * 승격(READY 확인 후 활성으로 옮기고 이전 것을 삭제)은 후속이다 — 완료 확인 경로가 아직 없다.
     * 그때까지도 두 이름이 다 남아 있어 **정리 작업이 둘 다 지울 수 있다.**
     */
    @Column(name = "pending_vocabulary_name", length = 200)
    private String pendingVocabularyName;

    @Column(name = "error_code", length = 50)
    private String errorCode;

    @Column(name = "built_at")
    private LocalDateTime builtAt;

    /* 제공자 리소스를 정리한 시각. NULL 이면 아직 계정 상한을 쓰고 있다. */
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

    public static MeetingVocabularyJpaEntity pending(long meetingId) {
        MeetingVocabularyJpaEntity entity = new MeetingVocabularyJpaEntity();
        entity.meetingId = meetingId;
        entity.status = VocabularyStatus.PENDING;
        entity.phraseCount = 0;
        return entity;
    }

    /*
     * 재생성을 **선점한다.** 이미 PENDING 이면 false 다.
     *
     * 선점이 필요한 이유 — 같은 회의에 재생성 요청이 겹치면 둘 다 제공자를 불러 **어휘 리소스가
     * 중복 생성되고 계정 상한을 그만큼 갉아먹는다**(CodeRabbit PR #241). 이긴 요청만 제출하고
     * 진 요청은 진행 중인 작업을 그대로 돌려준다.
     *
     * 이전 실패 흔적(errorCode)은 지운다 — 남겨 두면 지금 만드는 중인데도 화면이 지난 실패를
     * 이번 것으로 보여준다. 반대로 phraseCount·builtAt 은 **그대로 둔다**(클래스 주석).
     */
    public boolean claimRebuild() {
        if (this.status == VocabularyStatus.PENDING && this.id != null) {
            return false;
        }
        this.status = VocabularyStatus.PENDING;
        this.errorCode = null;
        this.pendingVocabularyName = null;
        // 새 리소스를 만들기 시작했으므로 이전 것을 지운 적은 없다.
        this.deletedAt = null;
        return true;
    }

    /*
     * 제출한 리소스 이름을 **대기 칸에** 적는다. 활성 이름은 건드리지 않는다 — 그 리소스가
     * 아직 쓰이고 있고, 덮으면 지울 방법이 사라진다.
     */
    public void assignPendingName(String pendingVocabularyName) {
        this.pendingVocabularyName = pendingVocabularyName;
    }

    /*
     * 제출이 실패했다. **PENDING 으로 두지 않는다** — 그러면 화면이 영원히 "만드는 중"으로
     * 보여주고 사람이 다시 누를 수도 없다(선점이 PENDING 을 막는다).
     */
    public void markBuildFailed(String errorCode) {
        this.status = VocabularyStatus.FAILED;
        this.errorCode = errorCode;
        this.pendingVocabularyName = null;
    }
}
