package com.module06.backend.capture.domain.model;

/*
 * 회의별 커스텀 어휘의 생성 상태다(meeting_vocabulary.status · V5.19).
 *
 * **이 값은 녹음을 막지 않는다.** READY 가 아니어도 회의는 시작할 수 있고, 고유명사 인식률만
 * 낮아진다 — "모모시티"·"인수인계서" 같은 단어가 틀리게 받아쓰인다. 그래서 화면에서도 경고이지
 * 차단이 아니다.
 */
public enum VocabularyStatus {

    /*
     * 만드는 중이다. **아직 시작하지 않은 회의도 이 값으로 답한다** — 사람이 할 일이 같기
     * 때문이다(기다리거나 재생성을 누른다). 명세의 상태값이 셋뿐이라 새 값을 지어내지 않는다.
     */
    PENDING,

    READY,

    /* 생성이 실패했다. STT-02 로 다시 만들 수 있다. */
    FAILED
}
