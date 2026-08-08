package com.module06.backend.cap.application.service;

import com.module06.backend.cap.application.command.CompletePartUploadCommand;
import com.module06.backend.cap.application.command.IssuePartUploadUrlsCommand;
import com.module06.backend.cap.application.guard.CapMeetingAccessGuard;
import com.module06.backend.cap.application.port.out.CapObjectStoragePort;
import com.module06.backend.cap.application.port.out.CaptureHeartbeatPort;
import com.module06.backend.cap.application.usecase.CompletePartUploadUseCase;
import com.module06.backend.cap.application.usecase.IssuePartUploadUrlsUseCase;
import com.module06.backend.cap.domain.exception.CapErrorCode;
import com.module06.backend.cap.domain.model.CaptureUploadState;
import com.module06.backend.cap.domain.repository.CaptureUploadStateRepository;
import com.module06.backend.cap.domain.repository.MeetingReferenceRepository;
import com.module06.backend.global.exception.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

// presign(#4)+complete(#7)의 실제 로직을 담당하는 서비스. UseCase 인터페이스의 진짜 구현체 —
// 회의 존재 확인, 녹음자 배정/검증, 청크 저장, S3 키 조립, 하트비트 갱신을 전부 여기서 조율한다.
@Service
public class CaptureUploadService implements IssuePartUploadUrlsUseCase, CompletePartUploadUseCase {

    private final MeetingReferenceRepository meetingReferenceRepository;
    private final CapMeetingAccessGuard accessGuard;
    private final CaptureUploadStateRepository captureUploadStateRepository;
    private final CapObjectStoragePort capObjectStoragePort;
    private final CaptureHeartbeatPort captureHeartbeatPort;
    // completePartUpload의 실제 DB 쓰기(청크·상태 저장)만 짧은 트랜잭션으로 묶는 별도 협력자
    // (CodeRabbit 지적 — S3 HEAD 호출을 트랜잭션 밖에 두려고 분리). CaptureUploadService 자신을
    // this.xxx()로 불렀다면 @Transactional이 프록시를 못 거쳐 안 걸렸을 것이라, 별도 빈으로 뽑았다.
    private final CompletePartUploadWriter completePartUploadWriter;

    public CaptureUploadService(MeetingReferenceRepository meetingReferenceRepository,
                                CapMeetingAccessGuard accessGuard,
                                CaptureUploadStateRepository captureUploadStateRepository,
                                CapObjectStoragePort capObjectStoragePort,
                                CaptureHeartbeatPort captureHeartbeatPort,
                                CompletePartUploadWriter completePartUploadWriter) {
        this.meetingReferenceRepository = meetingReferenceRepository;
        this.accessGuard = accessGuard;
        this.captureUploadStateRepository = captureUploadStateRepository;
        this.capObjectStoragePort = capObjectStoragePort;
        this.captureHeartbeatPort = captureHeartbeatPort;
        this.completePartUploadWriter = completePartUploadWriter;
    }

    // 회의 존재 확인 → 참석자 확인 → 녹음자 배정/검증 → 하트비트 갱신 → presigned URL count개 발급
    // (presign 자체는 로컬 서명 계산이라 네트워크 호출이 없다 — 트랜잭션 안에 있어도 커넥션을 오래 붙들지 않는다.)
    @Override
    @Transactional
    public Result issuePartUploadUrls(IssuePartUploadUrlsCommand command) {
        Long companyId = meetingReferenceRepository.findCompanyId(command.meetingId())
                .orElseThrow(() -> new BusinessException(CapErrorCode.CAP_MEETING_NOT_FOUND));

        // 비참석자가 유효한 meetingId만으로 녹음자로 배정(첫 presign)되는 걸 막는다.
        // TEMP 헤더 브리지 구간에서도 회의 참석자 명단은 이미 검증 가능(V1 테이블 존재).
        requireAttendee(command.meetingId(), command.callerId());

        CaptureUploadState state = captureUploadStateRepository.findByMeetingId(command.meetingId())
                .orElseGet(() -> CaptureUploadState.startWithRecorder(command.meetingId(), command.callerId()));

        // 이미 녹음자가 있는 상태에서 다른 사람이 호출하면, 하트비트가 끊긴 경우(canTakeover)에만 교체 허용.
        boolean canTakeover = !captureHeartbeatPort.isAlive(command.meetingId());
        state.assignOrVerifyRecorder(command.callerId(), canTakeover);
        CaptureUploadState saved = captureUploadStateRepository.save(state);

        // presign도 녹음자의 살아있음 신호다 — 여기서 하트비트를 세워두지 않으면 첫 배정 직후
        // (아직 complete 전) 두 번째 호출자의 isAlive가 false로 떠서 즉시 takeover가 열린다.
        captureHeartbeatPort.refresh(command.meetingId());

        String extension = extensionFor(command.contentType());
        List<Part> parts = new ArrayList<>();
        // lastSeq 이후 번호로 발급 — 다음 배치 요청 시점엔 이전 배치의 complete()가 대부분 반영돼 있다는
        // 전제(문서: "소진 전에 다음 배치 요청"). 드물게 겹쳐도 seq는 DB UNIQUE 제약이 최종 방어선이다.
        for (int seq = saved.getLastSeq() + 1; parts.size() < command.count(); seq++) {
            String s3Key = buildS3Key(companyId, command.meetingId(), saved.getSegmentSeq(), seq, extension);
            CapObjectStoragePort.IssuedPartUploadUrl issued =
                    capObjectStoragePort.issuePartUploadUrl(s3Key, command.contentType());
            parts.add(new Part(seq, issued.presignedUrl(), issued.expiresInSeconds()));
        }
        return new Result(saved.getSegmentSeq(), parts);
    }

    // 회의 존재 확인 → 참석자 확인 → 녹음자 검증 → 세그먼트/키 검증 → S3 HEAD 확인 → 청크 저장(멱등)
    // → lastSeq/하트비트 갱신
    //
    // ⚠️ 이 메서드 자체는 @Transactional이 아니다(CodeRabbit 지적) — S3 objectMatches()가 동기
    // 네트워크 호출이라, 트랜잭션(=DB 커넥션 점유) 안에서 부르면 S3 지연·재시도 동안 커넥션을 오래
    // 붙든다. 읽기(findCompanyId/findByMeetingId)는 각자 자동 커밋되는 단건 조회라 트랜잭션이 굳이
    // 필요 없고, 실제 쓰기(청크·상태 저장)만 CompletePartUploadWriter로 좁혀서 짧게 묶는다.
    @Override
    public void completePartUpload(CompletePartUploadCommand command) {
        Long companyId = meetingReferenceRepository.findCompanyId(command.meetingId())
                .orElseThrow(() -> new BusinessException(CapErrorCode.CAP_MEETING_NOT_FOUND));

        requireAttendee(command.meetingId(), command.callerId());

        // capture_upload_state가 없다는 건 presign이 한 번도 호출 안 됐다는 뜻 — 즉 아무도 아직
        // 녹음자로 배정 안 됐으므로, 이 caller도 당연히 "현재 녹음자"가 아니다.
        CaptureUploadState state = captureUploadStateRepository.findByMeetingId(command.meetingId())
                .orElseThrow(() -> new BusinessException(CapErrorCode.CAP_NOT_CURRENT_RECORDER));

        // 녹음자 검증(아니면 여기서 CAP_NOT_CURRENT_RECORDER) 먼저, 그 다음에야 저장을 시도한다.
        // (아직 DB에 반영 안 된 순수 인메모리 변경 — 아래서 실패하면 저장 자체가 안 되니 그냥 버려진다.)
        state.recordUpload(command.callerId(), command.seq());

        // 요청 본문의 segmentSeq/s3Key를 그대로 믿지 않는다(IDOR 방지) — 현재 세그먼트와 일치해야 하고,
        // 키는 서버가 (companyId, meetingId, segmentSeq, seq)로 재구성한 값과 정확히 같아야 한다.
        // 확장자(webm/mp4)만 브라우저 코덱에 따라 달라질 수 있으므로 제출된 키에서 뽑아 쓴다.
        if (command.segmentSeq() != state.getSegmentSeq()) {
            throw new BusinessException(CapErrorCode.CAP_PART_KEY_MISMATCH);
        }
        String extension = extensionFromKey(command.s3Key());
        String expectedKey = buildS3Key(companyId, command.meetingId(), state.getSegmentSeq(),
                command.seq(), extension);
        if (!expectedKey.equals(command.s3Key())) {
            throw new BusinessException(CapErrorCode.CAP_PART_KEY_MISMATCH);
        }

        // 요청 본문 sizeBytes를 그대로 믿지 않는다(#155) — 실제로 그 크기로 업로드가 됐는지 S3
        // HEAD로 확인한다(트랜잭션 밖, DB 커넥션 미점유). 없거나 크기가 다르면 클라이언트가 업로드도
        // 안 하고(또는 다른 크기로) 완료를 통보한 것이므로, 없는 객체를 UPLOADED로 기록해 조립이
        // 깨지는 것을 막는다.
        if (!capObjectStoragePort.objectMatches(command.s3Key(), command.sizeBytes())) {
            throw new BusinessException(CapErrorCode.CAP_PART_NOT_UPLOADED);
        }

        // recording_part.content_type(NOT NULL)을 채운다 — 확장자에서 역산(webm→audio/webm, mp4→audio/mp4).
        completePartUploadWriter.write(state, expectedKey, contentTypeForExtension(extension), command);

        // TODO: 10분(40청크) 누적 시 블록 조립 베스트에포트 트리거.
        // 이태연 capture/STT 도메인(develop의 V5.x·stt_block)과 연동해야 하나, 조립 트리거 배선은
        // 이번 PR 범위 밖 — 포트 계약을 이태연과 맞춘 뒤 별도로 배선 예정.
    }

    // 회의 참석자 명단에 없으면 CAP_NOT_ATTENDEE(403). presign/complete는 참석자만 호출 가능.
    private void requireAttendee(Long meetingId, Long callerId) {
        if (!accessGuard.isAttendee(meetingId, callerId)) {
            throw new BusinessException(CapErrorCode.CAP_NOT_ATTENDEE);
        }
    }

    // 제출된 s3Key의 확장자 추출 — 허용된 코덱(webm/mp4)만, 그 외는 키 불일치로 거부.
    private String extensionFromKey(String s3Key) {
        int dot = s3Key == null ? -1 : s3Key.lastIndexOf('.');
        String extension = dot >= 0 ? s3Key.substring(dot + 1).toLowerCase() : "";
        if (!"webm".equals(extension) && !"mp4".equals(extension)) {
            throw new BusinessException(CapErrorCode.CAP_PART_KEY_MISMATCH);
        }
        return extension;
    }

    // 청크 하나의 S3 저장 경로(키) 조립
    private String buildS3Key(Long companyId, Long meetingId, int segmentSeq, int seq, String extension) {
        // 문서 예시 경로(stt-temp/org-{orgId}/meeting-{meetingId}/parts/{seq}.webm)에 segmentSeq를
        // 끼워넣었다 — 문서 그대로면 이어받기(세그먼트 증가) 시 이전 세그먼트의 같은 seq와 경로가
        // 충돌한다(예: 세그먼트0의 seq=3과 세그먼트1의 seq=3이 같은 키). 팀 확인 필요.
        return "stt-temp/org-%d/meeting-%d/segments/%d/parts/%04d.%s"
                .formatted(companyId, meetingId, segmentSeq, seq, extension);
    }

    // Content-Type → 파일 확장자 (Safari는 mp4, 그 외는 webm)
    private String extensionFor(String contentType) {
        return "audio/mp4".equalsIgnoreCase(contentType) ? "mp4" : "webm";
    }

    // 파일 확장자 → Content-Type (extensionFor의 역함수). recording_part.content_type 저장용.
    private String contentTypeForExtension(String extension) {
        return "mp4".equals(extension) ? "audio/mp4" : "audio/webm";
    }
}
