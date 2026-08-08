package com.module06.backend.capture.application.usecase;

import com.module06.backend.capture.application.port.out.MeetingVocabularyRepository.VocabularyView;

/*
 * STT-02 · 커스텀 어휘 재생성. 회의 입장 전(개설자)이 누른다.
 *
 * 참석자가 추가·변경됐거나 생성이 FAILED 일 때 다시 만든다. **회의 담당자만** 부를 수 있다
 * (명세 403) — 어휘 생성은 제공자 계정의 한정된 자원을 쓰고, 참석자 아무나 반복해 누르면
 * 그 상한을 갉아먹는다.
 */
public interface RebuildMeetingVocabularyUseCase {

    VocabularyView rebuild(RebuildVocabularyCommand command);

    record RebuildVocabularyCommand(long companyId, long meetingId, long requestedBy) {
    }
}
