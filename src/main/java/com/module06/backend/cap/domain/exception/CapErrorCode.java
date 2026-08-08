package com.module06.backend.cap.domain.exception;

import com.module06.backend.global.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.http.HttpStatus;

// CAP 도메인 전용 에러코드. BusinessException과 함께 던지면 GlobalExceptionHandler가 공통 처리한다.
@Getter
@AllArgsConstructor
public enum CapErrorCode implements ErrorCode {

    CAP_PART_SIZE_EXCEEDED(HttpStatus.BAD_REQUEST, "CAP-001", "청크 크기 상한을 초과했습니다."),
    CAP_REQUIRED_ID(HttpStatus.BAD_REQUEST, "CAP-006", "필수 식별자가 누락되었습니다."),
    CAP_REQUIRED_TEXT(HttpStatus.BAD_REQUEST, "CAP-007", "필수 텍스트가 누락되었습니다."),
    CAP_INVALID_PART_SIZE(HttpStatus.BAD_REQUEST, "CAP-008", "청크 크기가 올바르지 않습니다."),
    CAP_PART_KEY_MISMATCH(HttpStatus.BAD_REQUEST, "CAP-009", "청크 경로가 서버가 발급한 값과 일치하지 않습니다."),
    CAP_INVALID_SEQ(HttpStatus.BAD_REQUEST, "CAP-011", "청크 순번이 올바르지 않습니다."),
    CAP_RECORDING_KEY_MISMATCH(HttpStatus.BAD_REQUEST, "CAP-015", "녹음 파일 경로가 올바르지 않습니다."),
    CAP_PART_NOT_UPLOADED(HttpStatus.BAD_REQUEST, "CAP-019", "청크가 실제로 업로드되지 않았거나 크기가 일치하지 않습니다."),

    CAP_MEETING_NOT_FOUND(HttpStatus.NOT_FOUND, "CAP-002", "회의를 찾을 수 없습니다."),
    CAP_RECORDING_NOT_FOUND(HttpStatus.NOT_FOUND, "CAP-016", "녹음 파일이 존재하지 않습니다."),

    CAP_STORAGE_QUOTA_EXCEEDED(HttpStatus.FORBIDDEN, "CAP-003", "저장 용량 한도를 초과했습니다."),
    CAP_NOT_CURRENT_RECORDER(HttpStatus.FORBIDDEN, "CAP-004", "현재 녹음자가 아닙니다."),
    CAP_NOT_ATTENDEE(HttpStatus.FORBIDDEN, "CAP-010", "회의 참석자가 아닙니다."),
    CAP_NOT_HOST(HttpStatus.FORBIDDEN, "CAP-013", "회의 담당자만 요청할 수 있습니다."),

    CAP_PART_ALREADY_REGISTERED(HttpStatus.CONFLICT, "CAP-005", "이미 등록된 청크입니다."),
    CAP_ASSEMBLY_INCOMPLETE(HttpStatus.CONFLICT, "CAP-012", "녹음 파일 조립에 실패했습니다. 일부 구간이 누락되었습니다."),
    CAP_RECORDING_ALREADY_SUBMITTED(HttpStatus.CONFLICT, "CAP-014", "이미 제출된 녹음이 있습니다."),
    CAP_STT_NOT_CONFIRMED(HttpStatus.CONFLICT, "CAP-017", "미확인 STT 구간이 있습니다."),

    CAP_RMS_REQUIRED(HttpStatus.UNPROCESSABLE_ENTITY, "CAP-018", "rms 값이 필요합니다.");

    private final HttpStatus httpStatus;
    private final String code;
    private final String message;
}
