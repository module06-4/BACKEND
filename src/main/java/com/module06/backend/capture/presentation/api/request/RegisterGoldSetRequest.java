package com.module06.backend.capture.presentation.api.request;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/*
 * QLTY-01 요청이다.
 *
 * 정답 라벨을 받지 않는다 — **그 회의의 지금 상태가 곧 정답**이다. 사람이 검토 화면에서 전량
 * 확인한 값을 그 순간 그대로 떠서 얼린다.
 */
public record RegisterGoldSetRequest(
        @Schema(description = "정답지로 얼릴 회의", example = "500")
        @NotNull(message = "meetingId 는 필수입니다.")
        Long meetingId,

        /*
         * 선정 사유. **무작위로 뽑았는지는 코드가 강제할 수 없다** — 어느 회의를 고를지는
         * 사람이 정하기 때문이다. 애매한 사례만 고르면 분포가 왜곡되므로, 나중에 지표를
         * 의심할 때 표본이 어떻게 뽑혔는지 되짚을 근거를 여기 남긴다.
         */
        @Schema(description = "선정 사유·특이사항", example = "무작위 표본 #4")
        @Size(max = 500, message = "note 는 500자를 넘을 수 없습니다.")
        String note
) {
}
