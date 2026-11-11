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
    INVALID_RECOMMENDATION_SIZE(HttpStatus.BAD_REQUEST, "추천 인원은 1명 이상 10명 이하로 요청해야 합니다."),
    // 묶음 신청에 같은 날짜가 두 번 오면 같은 보호사에게 스스로 겹치는 예약을 만들게 됨
    DUPLICATE_VISIT_DATE(HttpStatus.BAD_REQUEST, "같은 날짜를 두 번 신청할 수 없습니다."),

    CAREGIVER_NOT_FOUND(HttpStatus.NOT_FOUND, "요양보호사를 찾을 수 없습니다."),
    RECIPIENT_NOT_FOUND(HttpStatus.NOT_FOUND, "수급자를 찾을 수 없습니다."),
    VISIT_REQUEST_NOT_FOUND(HttpStatus.NOT_FOUND, "방문 요청을 찾을 수 없습니다."),
    VISIT_REQUEST_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "묶음 신청을 찾을 수 없습니다."),
    RESERVATION_NOT_FOUND(HttpStatus.NOT_FOUND, "예약을 찾을 수 없습니다."),

    // 이미 배정됐거나 취소된 요청은 추천하지 않음 (온케어는 빈 목록을 돌려줘서 원인을 알 수 없었음)
    VISIT_REQUEST_NOT_ASSIGNABLE(HttpStatus.CONFLICT, "배정할 수 없는 상태의 방문 요청입니다."),

    // 예약 확정 직전 재검증 실패 (근무 중이 아님, 선호 성별 불일치, 근무 가능 시간 밖)
    CAREGIVER_NOT_AVAILABLE(HttpStatus.CONFLICT, "이 요양보호사는 해당 시간에 배정할 수 없습니다."),
    RESERVATION_CONFLICT(HttpStatus.CONFLICT, "이미 예약된 시간대입니다."),
    RESERVATION_ALREADY_CANCELED(HttpStatus.CONFLICT, "이미 취소된 예약입니다."),
    // 다른 요청과 동시에 같은 데이터를 바꿔서 실패 (낙관적 락 충돌, 락 대기 시간 초과 등)
    CONCURRENT_MODIFICATION(HttpStatus.CONFLICT, "다른 요청과 동시에 처리되어 실패했습니다. 다시 시도해 주세요."),
    // Redis 락을 정해진 시간 안에 못 잡음
    RESERVATION_BUSY(HttpStatus.SERVICE_UNAVAILABLE, "요청이 몰려 처리하지 못했습니다. 잠시 후 다시 시도해 주세요.");

    private final HttpStatus status;
    private final String message;
}
