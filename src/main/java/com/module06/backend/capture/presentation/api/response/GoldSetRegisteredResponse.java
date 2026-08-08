package com.module06.backend.capture.presentation.api.response;

import java.time.LocalDateTime;

import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase.GoldSetRegistered;

/*
 * QLTY-01 응답이다.
 *
 * actionCount 를 내려주는 이유 — **몇 건짜리 정답지인지가 지표의 신뢰 구간을 정한다.**
 * 5건으로 잰 precision 0.8 과 100건으로 잰 0.8 은 같은 값이 아니다.
 *
 * version 은 명세에 없지만 함께 준다. 재라벨링이 새 버전으로 쌓이는 구조라(V5.11), 방금 얼린
 * 것이 몇 번째인지 모르면 화면이 "덮어썼는지 새로 쌓았는지"를 말할 수 없다.
 */
public record GoldSetRegisteredResponse(long goldSetId, int version, int actionCount, LocalDateTime frozenAt) {

    public static GoldSetRegisteredResponse from(GoldSetRegistered registered) {
        return new GoldSetRegisteredResponse(
                registered.goldSetId(), registered.version(),
                registered.actionCount(), registered.frozenAt());
    }
}
