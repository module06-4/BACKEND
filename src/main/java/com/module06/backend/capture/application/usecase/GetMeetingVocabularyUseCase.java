package com.module06.backend.capture.application.usecase;

import com.module06.backend.capture.application.port.out.MeetingVocabularyRepository.VocabularyView;

/*
 * STT-01 · 커스텀 어휘 상태 조회. 회의 입장 전(개설자)이 본다.
 *
 * **막는 값이 아니라 알려주는 값이다.** READY 가 아니어도 녹음은 시작할 수 있고, 고유명사
 * 인식률만 낮아진다 — 화면은 그 사실을 알려주고 재생성(STT-02)을 권한다.
 */
public interface GetMeetingVocabularyUseCase {

    VocabularyView getVocabulary(long companyId, long meetingId);
}
