package com.hyeongjin.visit.request.repository;

import java.util.List;
import java.util.Optional;

import com.hyeongjin.visit.request.domain.VisitRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRequestRepository extends JpaRepository<VisitRequest, Long> {

    // 추천에서 수급자 좌표·선호 성별을 바로 쓰므로 함께 조회 (지연 로딩 쿼리 1번 절약)
    @Query("select v from VisitRequest v join fetch v.recipient where v.id = :id")
    Optional<VisitRequest> findWithRecipientById(@Param("id") Long id);

    // 묶음의 하위 요청 전체 (날짜순). 추천·확정에서 수급자를 바로 쓰므로 함께 조회
    @Query("select v from VisitRequest v join fetch v.recipient where v.group.id = :groupId order by v.visitDate")
    List<VisitRequest> findAllWithRecipientByGroupId(@Param("groupId") Long groupId);
}
