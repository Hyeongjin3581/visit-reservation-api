package com.hyeongjin.visit.caregiver.dto;

import java.time.DayOfWeek;
import java.time.LocalTime;

import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;

public record AvailabilityResponse(
        Long id,
        Long caregiverId,
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime
) {

    public static AvailabilityResponse from(CaregiverAvailability availability) {
        return new AvailabilityResponse(
                availability.getId(),
                availability.getCaregiver().getId(),
                availability.getDayOfWeek(),
                availability.getStartTime(),
                availability.getEndTime());
    }
}
