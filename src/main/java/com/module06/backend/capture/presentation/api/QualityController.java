package com.module06.backend.capture.presentation.api;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.usecase.GetQualityCostUseCase;
import com.module06.backend.capture.application.usecase.GetQualityMetricsUseCase;
import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase;
import com.module06.backend.capture.application.usecase.RegisterGoldSetUseCase.RegisterGoldSetCommand;
import com.module06.backend.capture.presentation.api.request.RegisterGoldSetRequest;
import com.module06.backend.capture.presentation.api.response.GoldSetRegisteredResponse;
import com.module06.backend.capture.presentation.api.response.QualityCostResponse;
import com.module06.backend.capture.presentation.api.response.QualityMetricsResponse;
import com.module06.backend.capture.exception.CaptureErrorCode;
import com.module06.backend.global.exception.BusinessException;
import com.module06.backend.global.response.ApiResponse;
import com.module06.backend.global.security.AuthPrincipal;

/*
 * QLTY — 품질 측정 장치.
 *
 * <h2>회의 경로가 아니다</h2>
 * 다른 캡처 API 는 `/api/meetings/{meetingId}` 아래인데 이쪽은 `/api/quality` 다. 회의 하나를
 * 다루는 것이 아니라 **여러 회의를 가로질러 재는** 자리이기 때문이다(QLTY-02·03 은 아예
 * meetingId 를 받지 않는다).
 *
 * <h2>관리자 기능이다</h2>
 * 정답지 동결은 되돌릴 수 없고, 그걸로 잰 수치가 프롬프트·모델 판단의 근거가 된다. 참석자
 * 아무나 얼릴 수 있으면 표본이 어떻게 뽑혔는지 아무도 모르게 된다.
 */
@Tag(name = "Quality", description = "품질 측정(gold set·지표·비용) API")
@RestController
@RequestMapping("/api/quality")
@RequiredArgsConstructor
public class QualityController {

    private final RegisterGoldSetUseCase registerGoldSetUseCase;
    private final GetQualityMetricsUseCase getQualityMetricsUseCase;
    private final GetQualityCostUseCase getQualityCostUseCase;

    /*
     * QLTY-01 · gold set 등록.
     *
     * **측정 장치는 데이터가 쌓이기 전에 있어야 한다.** 없으면 프롬프트를 바꿔도 나아졌는지 알
     * 수 없고 정확도 개선이 감으로만 남는다. 3주 스코프에서는 5~10건으로 시작한다 —
     * 없는 것보다 작은 게 훨씬 낫다.
     */
    @Operation(
            summary = "gold set 등록 (QLTY-01)",
            description = "사람이 전량 검토한 회의를 정답지로 동결한다. 정답 라벨을 따로 받지 않는다 — "
                    + "그 회의의 지금 상태가 곧 정답이다. 미검토 액션이 남아 있으면 409 다: "
                    + "AI 출력이 정답지에 섞이면 모델이 자기 자신을 채점하게 된다. "
                    + "재라벨링은 기존 행을 고치지 않고 version 을 올린 새 행으로 쌓인다."
    )
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @PostMapping("/gold-set")
    public ApiResponse<GoldSetRegisteredResponse> registerGoldSet(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal me,
            @Valid @RequestBody RegisterGoldSetRequest request
    ) {
        return ApiResponse.created(
                "등록되었습니다.",
                GoldSetRegisteredResponse.from(registerGoldSetUseCase.register(
                        new RegisterGoldSetCommand(
                                me.getCompanyId(), request.meetingId(), me.getMemberId(), request.note()))));
    }

    /*
     * QLTY-02 · 품질 지표 조회.
     *
     * <h2>표본은 gold set 이 정한다</h2>
     * 채점에 필요한 값은 review_log 에 이미 다 있다. gold set 의 역할은 **어느 회의로 잴지를
     * 고정하는 것**이다 — 표본이 매번 달라지면 지표가 올라도 모델이 좋아진 것인지 쉬운 회의가
     * 섞인 것인지 알 수 없다.
     *
     * ⚠ 정답지가 아직 없으면 비율이 **null 이다.** 0.0 으로 채우지 않는다 — "다 틀렸다"와
     * "못 잰다"는 해야 할 일이 정반대다.
     */
    @Operation(
            summary = "품질 지표 조회 (QLTY-02)",
            description = "gold set 표본 위에서 precision·recall·게이트 오류율·검토 필요 비율을 낸다. "
                    + "needsReviewRate 는 비용 지표이지 품질 지표가 아니다 — 줄어드는 이유가 "
                    + "'정확해졌다'와 '모델이 과신한다' 둘이라 목표로 걸면 임계값을 낮춰 숫자를 "
                    + "맞추려는 유인이 생긴다. metricKind 로 분류를 함께 준다. "
                    + "표본이 없으면 비율은 null 이다(0.0 이 아니다)."
    )
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @GetMapping("/metrics")
    public ApiResponse<QualityMetricsResponse> getMetrics(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal me
    ) {
        return ApiResponse.success(
                "조회 성공",
                QualityMetricsResponse.from(getQualityMetricsUseCase.getMetrics(me.getCompanyId())));
    }

    /*
     * QLTY-03 · 비용 조회.
     *
     * **기준선이 없으면 특화 모델 전환의 절감 효과를 증명할 수 없다.** 상시 서빙 인스턴스 값이
     * 회의당 호출 비용보다 비싼 구간이 넓어서, 재봤더니 전환이 무의미한 결과도 가능하다 —
     * 그 판단을 하려면 지금 얼마인지가 먼저 있어야 한다.
     */
    @Operation(
            summary = "비용 조회 (QLTY-03)",
            description = "기간(YYYY-MM)의 계층별 토큰과 비용을 낸다. 생략하면 이번 달이다. "
                    + "요금제가 없으면 costKrw 는 null 이다(0 이 아니다) — 0 으로 채우면 공짜로 "
                    + "읽혀 특화 모델 전환이 언제나 이득으로 나온다. sttCostKrw 는 아직 낼 수 "
                    + "없어 항상 null 이다(stt_block 에 요금 데이터가 없다). "
                    + "calls 는 모델 호출 수가 아니라 계층 실행 수다."
    )
    @PreAuthorize("hasAnyRole('OWNER', 'ADMIN')")
    @GetMapping("/cost")
    public ApiResponse<QualityCostResponse> getCost(
            @Parameter(hidden = true) @AuthenticationPrincipal AuthPrincipal me,
            @Parameter(description = "YYYY-MM. 생략하면 이번 달(KST)")
            @RequestParam(required = false) String period
    ) {
        return ApiResponse.success(
                "조회 성공",
                QualityCostResponse.from(
                        getQualityCostUseCase.getCost(me.getCompanyId(), parsePeriod(period))));
    }

    /*
     * 형식이 틀리면 422 다. **이번 달로 대신 답하지 않는다** — 사람은 지난달을 물었는데 이번 달
     * 숫자를 받으면 그게 어느 달인지 모른 채로 비용을 판단하게 된다.
     */
    private YearMonth parsePeriod(String period) {
        if (period == null || period.isBlank()) {
            return null;
        }
        try {
            return YearMonth.parse(period);
        } catch (DateTimeParseException e) {
            throw new BusinessException(CaptureErrorCode.QUALITY_PERIOD_INVALID);
        }
    }
}
