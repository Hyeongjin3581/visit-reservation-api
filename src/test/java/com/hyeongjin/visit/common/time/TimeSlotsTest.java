package com.hyeongjin.visit.common.time;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalTime;

import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import org.junit.jupiter.api.Test;

class TimeSlotsTest {

    @Test
    void 정각과_30분_단위_시간은_통과한다() {
        assertThatCode(() -> TimeSlots.validateRange(LocalTime.of(9, 0), LocalTime.of(10, 30)))
                .doesNotThrowAnyException();
    }

    @Test
    void 시작이_종료보다_늦거나_같으면_예외() {
        assertThatThrownBy(() -> TimeSlots.validateRange(LocalTime.of(10, 0), LocalTime.of(10, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TIME_RANGE);
    }

    @Test
    void 시간이_30분_단위가_아니면_예외() {
        assertThatThrownBy(() -> TimeSlots.validateRange(LocalTime.of(9, 15), LocalTime.of(10, 0)))
                .isInstanceOf(BusinessException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.INVALID_TIME_SLOT);
    }
}
