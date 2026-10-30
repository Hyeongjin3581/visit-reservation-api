package com.hyeongjin.visit.recipient.dto;

import com.hyeongjin.visit.common.entity.Gender;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

// preferredGender 는 선택 (null = 상관없음)
public record RecipientCreateRequest(
        @NotBlank @Size(max = 50) String name,
        Gender preferredGender,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
) {
}
