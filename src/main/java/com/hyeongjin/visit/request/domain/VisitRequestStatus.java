package com.hyeongjin.visit.request.domain;

public enum VisitRequestStatus {
    REQUESTED,  // 신청 → 추천·배정 가능
    ASSIGNED,   // 배정 완료
    CANCELED    // 취소 (행은 보존)
}
