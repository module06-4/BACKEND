package com.module06.backend.capture.application.usecase;

import java.time.LocalDateTime;

/*
 * QLTY-01 · gold set 등록.
 *
 * 사람이 전량 라벨링한 회의를 정답지로 **동결한다.** 측정 장치는 데이터가 쌓이기 전에 있어야
 * 한다 — 없으면 프롬프트를 바꿔도 나아졌는지 알 수 없고 정확도 개선이 감으로만 남는다.
 *
 * ⚠ **애매한 사례만 고르면 분포가 왜곡된다.** 무작위로 뽑아야 한다는 것은 코드가 강제할 수
 * 없는 규칙이라(어느 회의를 고를지는 사람이 정한다) note 에 선정 사유를 남기게 해 둔다.
 */
public interface RegisterGoldSetUseCase {

    GoldSetRegistered register(RegisterGoldSetCommand command);

    record RegisterGoldSetCommand(long companyId, long meetingId, long requestedBy, String note) {
    }

    record GoldSetRegistered(long goldSetId, int version, int actionCount, LocalDateTime frozenAt) {
    }
}
