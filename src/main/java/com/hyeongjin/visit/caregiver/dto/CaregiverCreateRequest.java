package com.hyeongjin.visit.caregiver.dto;

import com.hyeongjin.visit.common.entity.Gender;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CaregiverCreateRequest(
        @NotBlank @Size(max = 50) String name,
        @NotNull Gender gender,
        @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
        @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude
) {
}
