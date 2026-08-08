package com.module06.backend.cap.application.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.module06.backend.cap.application.command.CompletePartUploadCommand;
import com.module06.backend.cap.application.port.out.CaptureHeartbeatPort;
import com.module06.backend.cap.domain.model.CaptureUploadState;
import com.module06.backend.cap.domain.model.RecordingPart;
import com.module06.backend.cap.domain.repository.CaptureUploadStateRepository;
import com.module06.backend.cap.domain.repository.RecordingPartRepository;

/*
 * completePartUpload(CaptureUploadService)의 실제 DB 쓰기만 담당하는 짧은 트랜잭션 경계(#155,
 * CodeRabbit 지적). S3 objectMatches()(동기 네트워크 호출)가 끝난 뒤에만 호출돼야 한다 — 별도
 * 클래스로 뽑은 이유는 자기 자신을 this.xxx()로 불러 @Transactional이 프록시를 못 거치는
 * self-invocation 문제를 피하고, 테스트에서도 이 조각만 독립적으로 조립할 수 있게 하기 위함이다.
 */
@Component
class CompletePartUploadWriter {

    private final RecordingPartRepository recordingPartRepository;
    private final CaptureUploadStateRepository captureUploadStateRepository;
    private final CaptureHeartbeatPort captureHeartbeatPort;

    CompletePartUploadWriter(RecordingPartRepository recordingPartRepository,
                             CaptureUploadStateRepository captureUploadStateRepository,
                             CaptureHeartbeatPort captureHeartbeatPort) {
        this.recordingPartRepository = recordingPartRepository;
        this.captureUploadStateRepository = captureUploadStateRepository;
        this.captureHeartbeatPort = captureHeartbeatPort;
    }

    @Transactional
    void write(CaptureUploadState state, String expectedKey, String contentType, CompletePartUploadCommand command) {
        RecordingPart part = RecordingPart.create(command.meetingId(), state.getSegmentSeq(), command.seq(),
                expectedKey, contentType, command.sizeBytes(), command.callerId());
        // UNIQUE(meeting_id, segment_seq, seq) 위반 시 어댑터가 CAP_PART_ALREADY_REGISTERED(409)로 변환.
        recordingPartRepository.save(part);

        captureUploadStateRepository.save(state);
        captureHeartbeatPort.refresh(command.meetingId());
    }
}
