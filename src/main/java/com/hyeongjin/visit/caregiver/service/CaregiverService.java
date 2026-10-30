package com.hyeongjin.visit.caregiver.service;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.dto.AvailabilityCreateRequest;
import com.hyeongjin.visit.caregiver.dto.AvailabilityResponse;
import com.hyeongjin.visit.caregiver.dto.CaregiverCreateRequest;
import com.hyeongjin.visit.caregiver.dto.CaregiverResponse;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.exception.BusinessException;
import com.hyeongjin.visit.common.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CaregiverService {

    private final CaregiverRepository caregiverRepository;
    private final CaregiverAvailabilityRepository availabilityRepository;

    @Transactional
    public CaregiverResponse register(CaregiverCreateRequest request) {
        Caregiver caregiver = Caregiver.create(
                request.name(), request.gender(), request.latitude(), request.longitude());
        return CaregiverResponse.from(caregiverRepository.save(caregiver));
    }

    public CaregiverResponse get(Long caregiverId) {
        return CaregiverResponse.from(findCaregiver(caregiverId));
    }

    @Transactional
    public AvailabilityResponse addAvailability(Long caregiverId, AvailabilityCreateRequest request) {
        Caregiver caregiver = findCaregiver(caregiverId);
        CaregiverAvailability availability = CaregiverAvailability.create(
                caregiver, request.dayOfWeek(), request.startTime(), request.endTime());
        return AvailabilityResponse.from(availabilityRepository.save(availability));
    }

    private Caregiver findCaregiver(Long caregiverId) {
        return caregiverRepository.findById(caregiverId)
                .orElseThrow(() -> new BusinessException(ErrorCode.CAREGIVER_NOT_FOUND));
    }
}
