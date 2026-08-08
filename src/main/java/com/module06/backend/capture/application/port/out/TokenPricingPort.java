package com.module06.backend.capture.application.port.out;

import java.util.OptionalLong;

/*
 * 토큰을 원화로 바꾼다(QLTY-03).
 *
 * <h2>단가는 미터링 도메인이 갖는다</h2>
 * 회사마다 요금제가 다르고 그 값의 주인은 미터링(company_token_plan)이다. 여기서 상수로 들고
 * 있으면 요금제를 바꿔도 이 화면만 옛 단가로 계산하게 되는데, **비용 지표는 그 숫자를 믿고
 * 특화 모델 전환을 판단하는 자리**라 조용히 갈리면 안 된다.
 *
 * 포트로 두는 이유 — 결합을 인프라 가장자리에 둔다. 서비스가 미터링 저장소를 직접 부르면
 * 캡처 도메인이 그쪽 스키마 변경에 묶인다.
 */
public interface TokenPricingPort {

    /*
     * @return 원화 금액. **요금제가 없으면 비어 있다** — 0 을 돌려주면 "공짜"로 읽히고,
     *         그 값으로 손익분기점을 계산하면 전환이 언제나 이득으로 나온다
     */
    OptionalLong krwOf(long companyId, long tokens);
}
