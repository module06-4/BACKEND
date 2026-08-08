package com.module06.backend.capture.infrastructure.persistence.repository;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;

import com.module06.backend.capture.infrastructure.persistence.entity.MeetingVocabularyJpaEntity;

/* meeting_vocabulary 접근. 회의당 하나다(UNIQUE(meeting_id)). */
public interface SpringDataMeetingVocabularyRepository
        extends JpaRepository<MeetingVocabularyJpaEntity, Long> {

    Optional<MeetingVocabularyJpaEntity> findByMeetingId(long meetingId);

    /*
     * 재생성 선점용. **쓰기 잠금을 걸고 읽는다** — 잠금 없이 읽으면 동시 요청이 둘 다
     * "PENDING 아님"을 보고 둘 다 선점해, 제공자에 어휘가 두 벌 만들어진다(계정 상한 낭비).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Optional<MeetingVocabularyJpaEntity> findWithLockByMeetingId(long meetingId);
}
