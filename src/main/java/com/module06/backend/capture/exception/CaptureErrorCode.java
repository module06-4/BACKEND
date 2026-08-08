package com.module06.backend.capture.exception;

import org.springframework.http.HttpStatus;

import lombok.AllArgsConstructor;
import lombok.Getter;

import com.module06.backend.global.exception.ErrorCode;

/*
 * 캡처 파이프라인(도메인 A) 전용 에러 코드다.
 *
 * 도메인별 enum 으로 분리해 담당자 간 파일 충돌을 막는다(MR-·PJ- 규약과 같은 방식).
 * 코드 문자열은 API 명세의 표를 그대로 따른다.
 */
@Getter
@AllArgsConstructor
public enum CaptureErrorCode implements ErrorCode {

    /* ANLZ-01 — 같은 회의의 분석이 이미 돌고 있다. 오류처럼 보이지만 중복 방어가 동작한 것이다. */
    ANALYSIS_ALREADY_RUNNING(HttpStatus.CONFLICT, "MEETING_409_3", "분석이 이미 진행 중입니다."),

    /* ANLZ-01 — 이미 완료된 분석을 force 없이 다시 돌리려 한 경우다. 재과금을 막는다. */
    ANALYSIS_ALREADY_DONE(HttpStatus.CONFLICT, "MEETING_409_4", "이미 분석이 완료된 회의입니다."),

    /* CAP-06 · ANLZ-03 — 아직 분석하지 않았거나 다른 회사 회의다. 타 회사 리소스는 403이 아니라 404로 존재를 숨긴다. */
    SUMMARY_NOT_FOUND(HttpStatus.NOT_FOUND, "ANLZ-001", "회의 요약이 없습니다."),

    /*
     * 캡처 파이프라인 공통 — 그 회사에 속한 회의가 아니다(없는 회의도 여기로 온다).
     *
     * 403 이 아니라 404 다. 403 이면 "이 회의는 존재하지만 당신 것이 아니다"가 새어 나가고,
     * id 를 훑어 남의 회사 회의 개수를 셀 수 있다. 없는 회의와 접근 불가를 같은 응답으로 덮는다.
     */
    MEETING_NOT_ACCESSIBLE(HttpStatus.NOT_FOUND, "MEETING_404_1", "회의를 찾을 수 없습니다."),

    /*
     * 계층 호출이 실패해 분석이 멈췄다.
     *
     * 502 인 이유 — 우리 요청이 잘못된 것이 아니라 뒤에 있는 AI 서버가 응답하지 못한 것이다.
     * 500 으로 내리면 이 저장소의 버그와 구분되지 않고, 알람이 엉뚱한 사람에게 간다.
     */
    ANALYSIS_LAYER_FAILED(HttpStatus.BAD_GATEWAY, "ANLZ-002", "분석 계층 호출에 실패했습니다."),

    /*
     * RVW-02 — 그 회의의 액션이 아니다(없는 액션도 여기로 온다).
     *
     * MEETING_NOT_ACCESSIBLE 과 같은 이유로 404 다. 관문은 회의까지만 보므로, 회의는 내 것인데
     * actionId 만 남의 것을 넣는 경로가 남는다 — 그 시도에 403 을 주면 "그 액션은 존재한다"가
     * 새어 나가고 id 를 훑어 남의 회사 액션 개수를 셀 수 있다.
     */
    REVIEW_ACTION_NOT_FOUND(HttpStatus.NOT_FOUND, "MEETING_404_2", "액션을 찾을 수 없습니다."),

    /*
     * RVW-02 — 수정·반려에 사유 코드가 없다(또는 CONFIRM 에 사유가 붙었다).
     *
     * 422 로 막는 이유 — 사유 없는 라벨은 **어느 계층을 고쳐야 할지 가리키지 못해 라벨로서
     * 쓸모가 없다.** 그런 행이 섞이면 정확도 조사를 처음부터 다시 해야 하고, 지나간 회의는
     * 다시 만들 수 없으므로 그 조사는 불가능하다. DB CHECK(CK_REVIEW_LOG_REASON)가 같은 규칙을
     * 강제하지만, 여기서 먼저 막아야 사용자에게 이유가 보인다 — DB 까지 내려가면 500 이 된다.
     *
     * ⚠ UNPROCESSABLE_ENTITY 가 이 스프링 버전에서 deprecated 경고를 낸다. 대체 상수
     * (UNPROCESSABLE_CONTENT)가 아직 없어 그대로 쓴다 — 명세가 요구하는 코드가 422 다.
     */
    REVIEW_REASON_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_3", "수정·반려에는 사유 코드가 필요합니다."),

    /*
     * RVW-02 — 참석자 명단에 없는 담당자로 고치려 했다.
     *
     * 사람이 고른 값이라 신뢰할 만해 보이지만, 드롭다운 밖의 id 를 직접 보내는 경로가 남아
     * 있다. 명단 밖 담당자를 넣으면 그 액션은 회의에 참석하지 않은 사람의 보드로 가고,
     * **그 값이 정답 라벨로 학습된다** — 틀린 배정을 AI 에게 가르치는 셈이다.
     */
    REVIEW_ASSIGNEE_NOT_IN_ROSTER(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_1", "참석자 명단에 없는 담당자입니다."),

    /*
     * RVW-02 — CONFIRM 에 담당자·기한을 함께 보냈다.
     *
     * CONFIRM 은 "AI 값이 그대로 정답"이라는 뜻이고, 그래서 라벨의 human_value 가 null 이다
     * (llm_output 과 같다는 표시). 값을 함께 받아 반영하면 **액션은 바뀌는데 라벨에는 그 변경이
     * 남지 않는다** — 나중에 그 행을 보면 "AI 가 맞혔다"고 읽히지만 실제 정답은 사람이 고친
     * 다른 값이다. 그 라벨은 틀린 값을 정답으로 가르치고, 정확도 숫자도 실제보다 높게 나온다.
     *
     * 값을 무시하지 않고 422 로 되돌리는 이유 — 무시하면 사람이 고친 담당자가 조용히 사라져
     * 화면과 DB 가 갈린다. 값을 고쳤다면 MODIFY 로 사유와 함께 보내야 한다.
     */
    REVIEW_CONFIRM_WITH_VALUE(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_4",
            "무수정 승인에는 담당자·기한을 함께 보낼 수 없습니다."),

    /*
     * RVW-05 — 확인되지 않은 STT 구간이나 미검토 액션이 남아 있다.
     *
     * **분배는 되돌리기 어렵다.** 액션이 사람들 보드에 꽂히고 나면 회수 경로가 없다(재분석
     * 결과가 액션에 반영되지 않는 것과 같은 이유다). 그래서 구멍이 남은 채로는 막고,
     * ?confirm=true 로만 강행하게 한다 — 강행 여부를 사람이 눈으로 보고 정하는 자리다.
     */
    REVIEW_CONFIRM_BLOCKED(HttpStatus.CONFLICT, "MEETING_409_5",
            "확인되지 않은 STT 구간이나 검토하지 않은 액션이 남아 있습니다."),

    /*
     * RVW-05 — 회의 담당자가 아니다.
     *
     * 403 이다. 다른 회사 회의는 404 로 존재를 숨기지만(MEETING_NOT_ACCESSIBLE), 이건 같은
     * 회사 안에서 **권한**이 갈리는 자리다 — 회의가 있다는 사실은 이미 검토 화면으로 보고 있다.
     */
    REVIEW_CONFIRM_HOST_ONLY(HttpStatus.FORBIDDEN, "MEETING_403_1", "회의 담당자만 요청할 수 있습니다."),
    /*
     * RVW-04 — AI 가 만든 액션을 삭제하려 했다.
     *
     * **AI 생성 액션은 지우는 것이 아니라 반려(RVW-02 REJECT)한다.** 지우면 라벨이 사라진다 —
     * "AI 가 이런 걸 뽑았고 사람이 아니라고 했다"는 쌍이 개선의 재료인데, 행이 없어지면 그
     * 사실 자체가 없던 일이 된다. 지나간 회의는 다시 만들 수 없어 복구도 불가능하다.
     */
    REVIEW_DELETE_AI_ACTION(HttpStatus.CONFLICT, "MEETING_409_7", "AI 생성 액션은 반려로 처리해야 합니다."),
    /*
     * RVW-03 — 직접 추가에 담당자·기한이 없다.
     *
     * **수동 추가 경로는 담당자를 강제한다**(C 도메인과 2026-08-07 합의). AI 분배 경로는
     * 담당자 미정을 허용하는데, 그건 검토 화면에서 사람이 고르는 후속 단계가 있기 때문이다.
     * 직접 추가는 사람이 이미 그 화면 앞에 있으므로 그 자리에서 정하는 것이 맞고, 비워두면
     * 아무의 보드에도 가지 않는 액션이 조용히 쌓인다.
     *
     * 기한도 같다. action.due_date 가 NOT NULL 이고, AI 경로처럼 프로젝트 마감일로 대신
     * 채우지 않는다 — 사람이 직접 넣는 자리에서 서버가 날짜를 지어내면 그게 사용자가 정한
     * 기한인지 기본값인지 화면에서 구분되지 않는다.
     */
    REVIEW_MANUAL_FIELD_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_5",
            "직접 추가에는 담당자와 기한이 필요합니다."),

    /*
     * RVW-03 — 근거 발화가 이 회의의 것이 아니다.
     *
     * **다른 회의(다른 회사)의 발화 id 를 넣는 경로를 막는다.** 검증 없이 저장하면 그 id 가
     * 액션에 박히고, 검토 화면(RVW-01)은 그 id 로 원문을 조인해 보여준다 — 남의 회의 발화
     * 내용이 우리 화면에 인용된다. 화면에 뿌려지는 순간 유출이라 저장 전에 막는다(#100).
     *
     * 근거를 아예 넣지 않는 것(null)은 정상이다. 회의록에서 집어 오지 않고 사람이 새로 쓴
     * 할 일이 그 모양이고, 명세의 요청 예시도 evidenceTranscriptId 가 null 이다.
     */
    REVIEW_EVIDENCE_NOT_IN_MEETING(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_6",
            "이 회의의 발화가 아닙니다."),

    /*
     * RVW-02 — 담당자 없는 PERSONAL 액션을 확정하려 했다.
     *
     * AI 분배 경로는 담당자 미정을 허용한다(2026-08-07 합의 · ActionTypeShapePolicy#checkDistribution).
     * 회의에서 담당자가 정해지지 않은 할 일이 검토 화면에서 통째로 사라지지 않게 하기 위해서이고,
     * **채우는 자리가 이 검토 화면**이다. 그래서 담당자를 안 채운 채로 지나가는 것을 여기서 막는다.
     *
     * 왜 막아야 하나 — 두 가지가 함께 망가진다.
     *   ① 라벨   확정은 review_log 에 {AI 입력 → 사람이 인정한 정답} 으로 남고 few-shot 예시로도
     *            예약된다. 담당자 null 을 정답으로 적으면 **담당자를 비우는 것이 정답이라고 AI 에게
     *            가르친다**(명단 밖 담당자를 422 로 막는 것과 같은 이유다).
     *   ② 분배   RVW-05 는 담당자 없는 액션을 NO_ASSIGNEE 로 남기고 내보내지 않는데, 그 관문은
     *            미검토(PENDING)만 보므로 **확정까지 끝난 이 액션은 강행 없이 조용히 빠진다.**
     *            사람은 검토를 마쳤다고 생각하는데 그 할 일은 아무의 보드에도 가지 않는다.
     *
     * TEAM 액션은 대상이 아니다 — 담당자 개념 자체가 없다(ActionTypeShapePolicy). 함께 막으면
     * 팀 액션은 영원히 확정되지 않는다.
     *
     * 무수정 승인(CONFIRM)에는 담당자를 실을 수 없으므로(MEETING_422_4) 담당자를 정해 수정(MODIFY)으로
     * 다시 보내야 한다. 메시지가 그 다음 행동을 가리켜야 사람이 화면에서 막힌 채로 남지 않는다.
     */
    REVIEW_ASSIGNEE_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_7",
            "담당자 없이 확정할 수 없습니다. 담당자를 지정해 수정으로 보내주세요."),

    /*
     * STT-04 — 그 회의의 블록이 아니다(없는 blockSeq 도 여기로 온다).
     *
     * REVIEW_ACTION_NOT_FOUND 와 같은 이유로 404 다. 관문(MeetingAccessGuard)은 회의까지만
     * 보므로 회의는 내 것인데 blockSeq 만 다른 것을 넣는 경로가 남는데, 그 시도에 다른 응답을
     * 주면 "그 블록은 존재한다"가 새어 나간다.
     */
    STT_BLOCK_NOT_FOUND(HttpStatus.NOT_FOUND, "MEETING_404_3", "STT 블록을 찾을 수 없습니다."),

    /*
     * STT-04 — 실패하지 않은 블록을 다시 돌리려 했다.
     *
     * **재처리 대상은 FAILED 뿐이다.** 성공했거나 아직 도는 블록을 다시 돌리면 같은 구간에
     * STT 요금이 두 번 나가고, 이미 들어온 발화 위에 같은 결과가 덮인다. 화면이 버튼을 가려도
     * blockSeq 를 직접 넣는 경로가 남으므로 서버에서 막는다.
     *
     * 409 인 이유 — 요청이 잘못된 것이 아니라 **그 블록의 지금 상태와 맞지 않는다.** 잠시 뒤
     * 실패로 바뀌면 같은 요청이 성립한다.
     */
    STT_BLOCK_NOT_RETRYABLE(HttpStatus.CONFLICT, "MEETING_409_8", "실패한 블록만 다시 처리할 수 있습니다."),

    /*
     * STT-04 — 다시 돌릴 오디오가 없다.
     *
     * 두 EC2 사이 파일 전달은 S3 경유만이라(V5.4 주석) audio_s3_key 가 없으면 제출할 대상 자체가
     * 없다. 그대로 보내면 제공자가 실패로 답하고 **시도 횟수만 올라간 채 같은 자리로 돌아온다** —
     * 사람이 버튼을 눌러도 아무것도 나아지지 않는 상태가 반복된다.
     */
    STT_BLOCK_AUDIO_MISSING(HttpStatus.CONFLICT, "MEETING_409_9",
            "블록 오디오가 없어 다시 처리할 수 없습니다."),

    /*
     * QLTY-01 — 같은 버전을 동시에 등록했다(UNIQUE(meeting_id, version) 충돌).
     *
     * 재시도해 다음 버전을 만들지 않는다. 먼저 얼린 쪽이 이 회의의 그 버전이고, 이쪽이 또
     * 만들면 **같은 라벨의 정답지가 두 벌** 생겨 어느 것으로 잰 수치인지 알 수 없게 된다.
     */
    GOLD_SET_ALREADY_FROZEN(HttpStatus.CONFLICT, "MEETING_409_6", "이미 동결된 gold set 입니다."),

    /*
     * QLTY-01 — 검토가 끝나지 않은 회의를 정답지로 얼리려 했다.
     *
     * PENDING 인 액션은 **AI 가 낸 값 그대로**다. 함께 얼리면 모델의 출력이 정답지에 들어가
     * **자기 자신을 채점하게 된다** — precision 이 실제보다 높게 나오고, 그 숫자로 프롬프트
     * 개선을 판단하게 된다. 측정 장치가 측정 대상을 베끼는 셈이다.
     */
    GOLD_SET_NOT_FULLY_REVIEWED(HttpStatus.CONFLICT, "MEETING_409_11",
            "검토가 끝나지 않은 회의는 정답지로 동결할 수 없습니다."),

    /*
     * QLTY-01 — 얼릴 액션이 하나도 없다.
     *
     * 빈 정답지는 precision 의 분모를 0 으로 만들어 지표를 못 낸다. 그런데도 동결은 되므로,
     * 막지 않으면 "정답지 8건" 안에 아무 내용 없는 행이 섞인다.
     */
    GOLD_SET_NO_ACTIONS(HttpStatus.CONFLICT, "MEETING_409_12", "정답지로 얼릴 액션이 없습니다."),

    /*
     * QLTY-01 — 정답 라벨을 JSON 으로 만들지 못했다.
     *
     * 여기서 빈 값으로 대체하지 않는다. 라벨이 깨진 정답지는 **정답지가 아니고**, 빈 배열로
     * 얼리면 그걸로 잰 precision 이 조용히 0 이 된다. 동결은 되돌릴 수 없으므로 차라리 실패한다.
     */
    GOLD_SET_LABEL_SERIALIZATION_FAILED(HttpStatus.INTERNAL_SERVER_ERROR, "MEETING_500_1",
            "정답 라벨을 저장할 수 없습니다."),

    /*
     * QLTY-03 — period 형식이 YYYY-MM 이 아니다.
     *
     * **이번 달로 대신 답하지 않는다.** 사람은 지난달 비용을 물었는데 이번 달 숫자를 받으면,
     * 그게 어느 달인지 모른 채로 비용을 판단하게 된다 — 이 API 는 특화 모델 전환의 손익분기점을
     * 계산하는 근거라 달이 어긋나면 결론이 통째로 바뀐다.
     */
    QUALITY_PERIOD_INVALID(HttpStatus.UNPROCESSABLE_ENTITY, "MEETING_422_8",
            "기간 형식이 올바르지 않습니다(YYYY-MM).");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
