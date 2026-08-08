package com.module06.backend.capture.application.port.out;

/*
 * STT 제공자에 커스텀 어휘 리소스를 만든다(AWS Transcribe).
 *
 * <h2>왜 미리 만들어야 하나</h2>
 * Transcribe 는 어휘를 **리소스로 등록하고 이름으로 참조**한다. 요청마다 단어 목록을 실어
 * 보낼 수 없어서, 회의 예약 시점에 만들어 두는 것 말고 방법이 없다. 그래서 이 포트가 있고,
 * 그래서 STT-01 이 "만드는 중"이라는 상태를 화면에 보여준다.
 *
 * <h2>삭제가 선택이 아니다</h2>
 * **계정당 어휘 개수 상한이 있다.** 회의가 끝난 뒤 정리하지 않으면 상한에 걸려 **신규 회의가
 * 어휘 없이 돌게 된다** — 그 회의들은 아무 오류 없이 그냥 인식률만 낮아지므로, 상한에 걸렸다는
 * 사실이 한참 뒤에야 드러난다. 그래서 생성과 삭제가 같은 포트에 있다.
 */
public interface CustomVocabularyPort {

    /*
     * 어휘를 만든다(비동기). 제공자 쪽 생성은 몇 분 걸리므로 **접수만 하고 돌아온다** —
     * 완료는 제공자 콜백이나 폴링이 확인한다.
     *
     * @return 만들어질 리소스 이름. 저장해 두어야 나중에 참조·삭제할 수 있다
     */
    String requestBuild(BuildRequest request);

    /*
     * 어휘 리소스를 지운다. 계정 상한을 되돌리는 유일한 방법이다.
     *
     * 없는 이름을 지우려 해도 실패로 보지 않는다 — 이미 지워진 것과 애초에 없던 것을 구분해도
     * 할 일이 같고, 여기서 터뜨리면 정리 작업이 그 하나 때문에 멈춘다.
     */
    void delete(String providerVocabularyName);

    /*
     * @param phrases 어휘에 넣을 단어. **이 목록이 곧 인식률이다** — 비어 있으면 만들 이유가 없다.
     *                지금은 **참석자 이름뿐**이다. 명세의 프로젝트 태그는 프로젝트 이름을 읽는
     *                포트가 생긴 뒤에 붙는다(MeetingProjectProvider 는 id 만 준다) — 여기 적어
     *                두면 구현이 없는 값을 지어내 제공자에 보내게 된다(CodeRabbit PR #241)
     */
    record BuildRequest(long meetingId, java.util.List<String> phrases) {
    }
}
