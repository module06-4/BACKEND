package com.module06.backend.capture.infrastructure.pricing;

import java.util.OptionalLong;

import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

import com.module06.backend.capture.application.port.out.TokenPricingPort;
import com.module06.backend.metering.domain.repository.CompanyTokenPlanRepository;

/*
 * 미터링의 회사 요금제로 토큰을 원화로 바꾼다(QLTY-03).
 *
 * <h2>여기가 두 도메인이 만나는 유일한 자리다</h2>
 * 캡처는 계층별 토큰을 갖고 미터링은 단가를 갖는다. 둘을 합치는 지점을 어댑터 하나로 좁혀,
 * 서비스가 미터링 스키마 변경에 묶이지 않게 한다.
 *
 * ⚠ 미터링 도메인 저장소를 **읽는다**(쓰지 않는다). 그쪽 소유자와 공유가 필요한 결합이다.
 */
@Component
@RequiredArgsConstructor
public class CompanyTokenPlanPricingAdapter implements TokenPricingPort {

    private final CompanyTokenPlanRepository companyTokenPlanRepository;

    @Override
    public OptionalLong krwOf(long companyId, long tokens) {
        return companyTokenPlanRepository.findByCompanyId(companyId)
                // 요금제가 없으면 비운다. 0 을 주면 "공짜"로 읽히고, 그 값으로 손익분기점을
                // 계산하면 특화 모델 전환이 언제나 이득으로 나온다.
                .map(plan -> OptionalLong.of(plan.usageAmountKrw(tokens)))
                .orElseGet(OptionalLong::empty);
    }
}
