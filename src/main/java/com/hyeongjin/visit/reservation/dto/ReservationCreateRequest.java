package com.hyeongjin.visit.reservation.dto;

import jakarta.validation.constraints.NotNull;

public record ReservationCreateRequest(@NotNull Long caregiverId) {
}
