package com.hyeongjin.visit.common.seed;

import static org.assertj.core.api.Assertions.assertThat;

import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.repository.VisitRequestGroupRepository;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.repository.CaregiverDayLockRepository;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// seed 프로필로 띄우면 샘플 데이터가 들어가고, 두 번째 실행에서는 중복으로 넣지 않는지
@SpringBootTest(properties = "seed.caregivers=40")
@ActiveProfiles("seed")
class SampleDataSeederTest {

    @Autowired private SampleDataSeeder seeder;
    @Autowired private CaregiverRepository caregiverRepository;
    @Autowired private CaregiverAvailabilityRepository availabilityRepository;
    @Autowired private RecipientRepository recipientRepository;
    @Autowired private VisitRequestRepository visitRequestRepository;
    @Autowired private VisitRequestGroupRepository visitRequestGroupRepository;
    @Autowired private ReservationRepository reservationRepository;
    @Autowired private CaregiverDayLockRepository caregiverDayLockRepository;

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAllInBatch();
        visitRequestRepository.deleteAllInBatch();
        // visit_request.group_id 가 묶음을 가리키므로 요청을 지운 다음에 지움
        visitRequestGroupRepository.deleteAllInBatch();
        availabilityRepository.deleteAllInBatch();
        // caregiver_day_lock.caregiver_id 가 보호사를 가리키므로 보호사보다 먼저 지움
        caregiverDayLockRepository.deleteAllInBatch();
        caregiverRepository.deleteAllInBatch();
        recipientRepository.deleteAllInBatch();
    }

    @Test
    void 앱이_뜰_때_샘플_데이터를_넣고_다시_실행해도_중복으로_넣지_않는다() {
        // 앱이 뜰 때 이미 한 번 실행됨
        assertThat(caregiverRepository.count()).isEqualTo(40);
        assertThat(availabilityRepository.count()).isEqualTo(40 * 7);
        assertThat(reservationRepository.count()).isEqualTo(40 * 2);

        seeder.run(new DefaultApplicationArguments());

        assertThat(caregiverRepository.count()).isEqualTo(40);
    }
}
