package com.module06.backend.capture.application.port.out;

import java.time.LocalDateTime;
import java.util.Optional;

import com.module06.backend.capture.domain.model.VocabularyStatus;

/* meeting_vocabulary(V5.19) 접근 포트다. STT-01(조회) · STT-02(재생성)가 쓴다. */
public interface MeetingVocabularyRepository {

    /* 회의당 하나다(UNIQUE). 아직 만든 적이 없으면 비어 있다. */
    Optional<VocabularyView> findByMeeting(long meetingId);

    /*
     * 재생성을 접수한다 — 없으면 만들고 있으면 PENDING 으로 되돌린다.
     *
     * <h2>phraseCount·builtAt 을 지우지 않는다</h2>
     * 재생성이 도는 동안에도 **제공자에는 이전 어휘가 그대로 살아 있다.** 여기서 0 으로
     * 비우면 화면이 "어휘 없음"으로 보여주는데 실제로는 지난 어휘가 쓰이고 있다 — 사람이
     * 인식률 문제를 어휘 탓으로 잘못 짚게 된다. 마지막으로 성공한 생성이 언제 몇 개였는지는
     * 그대로 두고 status 만 PENDING 으로 바꾼다.
     *
     * <h2>선점이다 — 이미 만드는 중이면 비어 있다</h2>
     * 같은 회의에 재생성 요청이 겹치면 둘 다 제공자를 불러 **어휘 리소스가 중복 생성되고 계정
     * 상한을 그만큼 갉아먹는다.** 이긴 요청만 제출하고, 진 요청은 진행 중인 작업을 그대로
     * 돌려준다.
     *
     * @return 선점에 성공하면 접수 뒤의 상태. 이미 PENDING 이면 비어 있다
     */
    Optional<VocabularyView> claimRebuild(long meetingId);

    /*
     * 제출한 리소스 이름을 **대기 칸에** 적어 둔다.
     *
     * **제출 뒤에 따로 적는다.** 이름은 제출이 성공해야 확정되는 값이라, 미리 적으면 만들어지지도
     * 않은 리소스 이름이 남는다 — 정리 작업이 그걸 지우려다 정작 계정 상한을 쓰는 진짜 리소스를
     * 놓친다.
     *
     * **활성 이름을 덮지 않는다.** 재생성이 도는 동안 제공자에는 이전 어휘가 살아 있고 실제로
     * 쓰인다. 덮으면 이전 리소스 이름이 사라져 그것만 영영 못 지우고, 재생성을 반복할수록
     * 지울 수 없는 리소스가 쌓인다. 승격(READY 확인 후 활성으로)은 후속이다.
     */
    void assignPendingName(long vocabularyId, String pendingVocabularyName);

    /*
     * 제출이 실패했다.
     *
     * **PENDING 으로 두지 않는다** — 그러면 화면이 영원히 "만드는 중"으로 보여주고, 선점이
     * PENDING 을 막으므로 사람이 다시 누를 수도 없다.
     */
    void markBuildFailed(long vocabularyId, String errorCode);

    /*
     * @param providerVocabularyName 제공자 리소스 이름. **삭제에 필요하다** — 계정당 어휘
     *                               개수 상한이 있어 정리하지 않으면 신규 회의가 어휘 없이
     *                               돌게 되는데, 지우려면 이름을 알아야 한다
     * @param builtAt                마지막으로 성공한 생성 시각. 재생성 중에도 남는다
     */
    record VocabularyView(
            long id,
            long meetingId,
            VocabularyStatus status,
            int phraseCount,
            String providerVocabularyName,
            LocalDateTime builtAt
    ) {
    }
}
