-- =====================================================================
-- V5.19 : meeting_vocabulary — 회의별 STT 커스텀 어휘 (STT-01 조회 · STT-02 재생성)
-- ---------------------------------------------------------------------
-- AWS Transcribe 는 어휘를 **미리 리소스로 만들어 이름으로 참조**한다. 요청마다
-- 단어 목록을 실어 보낼 수 없어서, 회의 예약 시점에 참석자 이름과 프로젝트 태그로
-- 만들어 두어야 한다. 만드는 데 몇 분 걸리므로 그 진행 상태를 여기 둔다.
--
-- 어휘가 없어도 녹음은 시작할 수 있다. 고유명사 인식률만 낮아진다 — "모모시티",
-- "인수인계서" 같은 단어가 틀리게 받아쓰인다. 그래서 이 표는 **막는 값이 아니라
-- 알려주는 값**이다.
--
-- provider_vocabulary_name 을 저장하는 이유가 둘이다.
--   ① 삭제 — 계정당 어휘 개수 상한이 있다. 회의가 끝난 뒤 정리하지 않으면 상한에
--      걸려 **신규 회의가 어휘 없이 돌게 된다.** 지우려면 이름을 알아야 한다.
--   ② 참조 — STT 제출 때 이 이름을 실어 보낸다. 이름을 매번 규칙으로 다시 만들면
--      규칙이 바뀌는 순간 예전 회의의 어휘를 못 찾는다.
--
-- 활성 이름과 대기 이름을 나눠 둔다(pending_vocabulary_name).
--   재생성이 도는 동안 제공자에는 **이전 어휘가 그대로 살아 있고 실제로 쓰인다.** 새 이름을
--   활성 칸에 곧바로 덮으면 이전 리소스 이름이 사라져 그것만 영영 못 지운다 — 재생성을
--   반복할수록 지울 수 없는 리소스가 쌓여 계정 상한이 누수된다. 새 어휘가 READY 로 확인된
--   뒤에 활성으로 승격하고 이전 것을 지우는 것이 맞는 순서다(승격은 후속).
--
-- UNIQUE(meeting_id) : 회의당 하나다. 재생성은 새 행이 아니라 같은 행을 갱신한다 —
-- 여러 행이 생기면 어느 것이 지금 리소스인지 알 수 없고, 정리에서 빠진 이름이 상한을
-- 갉아먹는다.
-- =====================================================================

CREATE TABLE `meeting_vocabulary` (
    `id`                       BIGINT       NOT NULL AUTO_INCREMENT,
    `meeting_id`               BIGINT       NOT NULL,
    `status`                   ENUM('PENDING', 'READY', 'FAILED') NOT NULL DEFAULT 'PENDING',
    `phrase_count`             INT          NOT NULL DEFAULT 0 COMMENT '마지막으로 성공한 생성의 단어 수',
    `provider_vocabulary_name` VARCHAR(200) NULL COMMENT '계정 내 유일. 삭제와 STT 제출에 모두 필요하다',
    `pending_vocabulary_name`  VARCHAR(200) NULL COMMENT '재생성 중인 리소스. READY 확인 후 활성으로 승격(후속). 활성 이름을 덮으면 이전 리소스를 영영 못 지운다',
    `error_code`               VARCHAR(50)  NULL COMMENT '사용자에게 그대로 노출하지 않는다',
    `built_at`                 DATETIME     NULL COMMENT '마지막으로 성공한 생성 시각. 재생성 중에도 남는다',
    `deleted_at`               DATETIME     NULL COMMENT '제공자 리소스를 정리한 시각. NULL 이면 아직 계정 상한을 쓰고 있다',
    `created_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`               DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `UK_MEETING_VOCABULARY_MEETING` (`meeting_id`),
    -- 정리 대상(끝났는데 아직 안 지운 것)을 찾는 조회가 붙을 자리다. 계정 상한이 있어
    -- 그 조회는 반드시 생긴다 — 그때 인덱스를 따로 붙이지 않게 지금 넣는다.
    KEY `IX_MEETING_VOCABULARY_CLEANUP` (`deleted_at`, `status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
