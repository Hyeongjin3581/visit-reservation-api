package com.hyeongjin.visit.common.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;

// 에러 코드를 한곳에서 관리 → 클라이언트는 code 값으로 분기
// (온케어에서 예외가 500 으로 그대로 나가던 문제 개선)
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

    INVALID_INPUT(HttpStatus.BAD_REQUEST, "입력값이 올바르지 않습니다."),
    INVALID_TIME_RANGE(HttpStatus.BAD_REQUEST, "시작 시간은 종료 시간보다 빨라야 합니다."),
    INVALID_TIME_SLOT(HttpStatus.BAD_REQUEST, "시간은 30분 단위로 입력해야 합니다."),

    CAREGIVER_NOT_FOUND(HttpStatus.NOT_FOUND, "요양보호사를 찾을 수 없습니다."),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수급자를 찾을 수 없습니다."),
    VISIT_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "방문 요청을 찾을 수 없습니다."),

    // 3주차 예약 확정에서 사용
    RESERVATION_CONFLICT(HttpStatus.CONFLICT, "이미 예약된 시간대입니다.");

    private final HttpStatus status;
    private final String message;
}
