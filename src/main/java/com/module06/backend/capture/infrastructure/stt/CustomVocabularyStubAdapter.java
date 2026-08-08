package com.module06.backend.capture.infrastructure.stt;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;

import com.module06.backend.capture.application.port.out.CustomVocabularyPort;

/*
 * 커스텀 어휘 제공자의 스텁이다. AWS Transcribe 호출은 후속이다.
 *
 * <h2>이름은 여기서 정한다</h2>
 * 실 어댑터도 같은 자리에서 정할 값이다 — 제공자마다 허용 문자와 길이가 다르므로, 이름 규칙이
 * 서비스로 새면 제공자를 바꿀 때 도메인 코드를 고쳐야 한다. 회의당 하나라 회의 id 로 충분하다.
 *
 * ⚠ **이 스텁이 도는 동안 상태는 PENDING 에서 더 나아가지 않는다.** READY 로 옮기는 것은
 * 제공자 완료 확인(콜백·폴링)의 몫이고 그게 아직 없다 — 상태가 안 바뀌는 것이 버그로 보이지
 * 않게 여기 적어 둔다. 그래도 화면은 막히지 않는다. READY 가 아니어도 녹음은 시작할 수 있다.
 */
@Slf4j
@Component
public class CustomVocabularyStubAdapter implements CustomVocabularyPort {

    @Override
    public String requestBuild(BuildRequest request) {
        String name = "meeting-" + request.meetingId() + "-vocab";
        log.info("커스텀 어휘 생성 요청(stub) — meetingId={} 단어={}개 resource={}. "
                        + "실제 Transcribe 어휘 생성·완료 확인(READY 전이)은 후속 STT 인프라에서 수행.",
                request.meetingId(), request.phrases().size(), name);
        return name;
    }

    @Override
    public void delete(String providerVocabularyName) {
        /*
         * 계정당 어휘 개수 상한을 되돌리는 유일한 방법이다. 아직 부르는 쪽이 없다 —
         * 회의 종료 시 정리 트리거가 후속이라, **지금은 만든 어휘가 계속 쌓인다.**
         * 실 어댑터가 붙기 전이라 당장 상한을 쓰지는 않지만, 붙는 순간 정리가 함께 필요하다.
         */
        log.info("커스텀 어휘 삭제 요청(stub) — resource={}. 계정 상한 정리는 후속.", providerVocabularyName);
    }
}
