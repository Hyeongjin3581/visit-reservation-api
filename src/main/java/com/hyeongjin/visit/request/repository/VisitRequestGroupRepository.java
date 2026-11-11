package com.hyeongjin.visit.request.repository;

import java.util.Optional;

import com.hyeongjin.visit.request.domain.VisitRequestGroup;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VisitRequestGroupRepository extends JpaRepository<VisitRequestGroup, Long> {

    // 응답에 수급자 id 를 쓰므로 함께 조회
    @Query("select g from VisitRequestGroup g join fetch g.recipient where g.id = :id")
    Optional<VisitRequestGroup> findWithRecipientById(@Param("id") Long id);
}
