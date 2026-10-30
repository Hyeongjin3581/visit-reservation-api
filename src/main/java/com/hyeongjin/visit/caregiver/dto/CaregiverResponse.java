package com.hyeongjin.visit.caregiver.dto;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverStatus;
import com.hyeongjin.visit.common.entity.Gender;

public record CaregiverResponse(
        Long id,
        String name,
        Gender gender,
        CaregiverStatus status,
        double latitude,
        double longitude
) {

    public static CaregiverResponse from(Caregiver caregiver) {
        return new CaregiverResponse(
                caregiver.getId(),
                caregiver.getName(),
                caregiver.getGender(),
                caregiver.getStatus(),
                caregiver.getLatitude(),
                caregiver.getLongitude());
    }
}
