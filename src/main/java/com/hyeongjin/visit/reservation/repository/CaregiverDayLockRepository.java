package com.hyeongjin.visit.reservation.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import com.hyeongjin.visit.reservation.domain.CaregiverDayLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaregiverDayLockRepository extends JpaRepository<CaregiverDayLock, Long> {

    // 잠금 행이 이미 있는 날짜 (없는 날짜만 새로 만들기 위해)
    @Query("""
            select l.visitDate from CaregiverDayLock l
            where l.caregiverId = :caregiverId and l.visitDate in :dates
            """)
    List<LocalDate> findExistingDates(@Param("caregiverId") Long caregiverId,
                                      @Param("dates") Collection<LocalDate> dates);

    // SELECT ... FOR UPDATE: 유니크 인덱스 한 행만 잠금 (READ COMMITTED 라 갭 락 없음 → 다른 날짜와 간섭 없음)
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from CaregiverDayLock l where l.caregiverId = :caregiverId and l.visitDate = :date")
    Optional<CaregiverDayLock> findForUpdate(@Param("caregiverId") Long caregiverId, @Param("date") LocalDate date);
}
