package com.hyeongjin.visit.caregiver.domain;

public enum CaregiverStatus {
    ACTIVE,     // 근무 중 → 추천 대상
    INACTIVE    // 휴직·퇴사 → 추천 제외 (행은 지우지 않음)
}
