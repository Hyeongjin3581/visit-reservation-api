package com.hyeongjin.visit.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    // 비즈니스 규칙 위반 → ErrorCode 에 정한 상태 코드로 응답
    @ExceptionHandler(BusinessException.class)
    public ResponseEntity<ErrorResponse> handleBusiness(BusinessException e) {
        ErrorCode errorCode = e.getErrorCode();
        return ResponseEntity.status(errorCode.getStatus()).body(ErrorResponse.of(errorCode));
    }

    // @Valid 검증 실패 → 첫 번째 필드 오류를 메시지로 전달
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        FieldError fieldError = e.getBindingResult().getFieldError();
        String message = fieldError == null
                ? ErrorCode.INVALID_INPUT.getMessage()
                : fieldError.getField() + ": " + fieldError.getDefaultMessage();
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT, message));
    }

    // JSON 형식 오류, enum 값 오타 등
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(ErrorCode.INVALID_INPUT));
    }

    // 서비스에서 잡지 못한 동시 수정 충돌 (예: 같은 방문 요청을 두 곳에서 동시에 배정 → VisitRequest 버전 불일치)
    @ExceptionHandler(ConcurrencyFailureException.class)
    public ResponseEntity<ErrorResponse> handleConcurrency(ConcurrencyFailureException e) {
        log.info("동시 수정 충돌: {}", e.getMessage());
        return ResponseEntity.status(ErrorCode.CONCURRENT_MODIFICATION.getStatus())
                .body(ErrorResponse.of(ErrorCode.CONCURRENT_MODIFICATION));
    }

    // 경로·쿼리 파라미터 타입 오류 (예: ?size=abc) → 500 대신 400
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.badRequest()
                .body(ErrorResponse.of(ErrorCode.INVALID_INPUT, e.getName() + ": 형식이 올바르지 않습니다."));
    }
}
