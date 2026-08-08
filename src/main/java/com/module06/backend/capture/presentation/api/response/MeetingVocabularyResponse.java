package com.module06.backend.capture.presentation.api.response;

import java.time.LocalDateTime;

import com.module06.backend.capture.application.port.out.MeetingVocabularyRepository.VocabularyView;

/*
 * STT-01 · STT-02 응답이다.
 *
 * <h2>providerVocabularyName 은 내려주지 않는다</h2>
 * 제공자 계정의 리소스 이름이다. 화면이 쓸 값이 아니고, 실어 보내면 우리 계정의 명명 규칙이
 * 응답에 드러난다 — 한 번 나간 계약은 되돌리기 어렵다.
 *
 * <h2>builtAt 은 재생성 중에도 남는다</h2>
 * **마지막으로 성공한 생성 시각**이다. 재생성이 도는 동안 제공자에는 이전 어휘가 그대로 살아
 * 있으므로, 이 값을 비우면 화면이 "어휘 없음"으로 보여주는데 실제로는 지난 어휘가 쓰이고 있다.
 * status=PENDING + builtAt 이 있으면 "지난 어휘로 돌면서 새로 만드는 중"이라는 뜻이다.
 */
public record MeetingVocabularyResponse(String status, int phraseCount, LocalDateTime builtAt) {

    public static MeetingVocabularyResponse from(VocabularyView view) {
        return new MeetingVocabularyResponse(
                view.status().name(), view.phraseCount(), view.builtAt());
    }
}
