package com.hyeongjin.visit.recipient.dto;

import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;

public record RecipientResponse(
        Long id,
        String name,
        Gender preferredGender,
        double latitude,
        double longitude
) {

    public static RecipientResponse from(Recipient recipient) {
        return new RecipientResponse(
                recipient.getId(),
                recipient.getName(),
                recipient.getPreferredGender(),
                recipient.getLatitude(),
                recipient.getLongitude());
    }
}
