package com.hyeongjin.visit.common.seed;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import com.hyeongjin.visit.caregiver.domain.Caregiver;
import com.hyeongjin.visit.caregiver.domain.CaregiverAvailability;
import com.hyeongjin.visit.caregiver.repository.CaregiverAvailabilityRepository;
import com.hyeongjin.visit.caregiver.repository.CaregiverRepository;
import com.hyeongjin.visit.common.entity.Gender;
import com.hyeongjin.visit.recipient.domain.Recipient;
import com.hyeongjin.visit.recipient.repository.RecipientRepository;
import com.hyeongjin.visit.request.domain.VisitRequest;
import com.hyeongjin.visit.request.repository.VisitRequestRepository;
import com.hyeongjin.visit.reservation.domain.Reservation;
import com.hyeongjin.visit.reservation.repository.ReservationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

// k6 부하 테스트·시연용 샘플 데이터. seed 프로필로 띄울 때만 동작
//   ./gradlew bootRun --args='--spring.profiles.active=seed'
// 보호사가 한 명이라도 있으면 아무것도 하지 않음 (다시 넣으려면 DB 를 지우고 실행)
@Slf4j
@Component
@Profile("seed")
@RequiredArgsConstructor
public class SampleDataSeeder implements ApplicationRunner {

    private static final int RECIPIENTS = 20;
    private static final int COMPLETED_VISITS_PER_CAREGIVER = 2;

    private final CaregiverRepository caregiverRepository;
    private final CaregiverAvailabilityRepository availabilityRepository;
    private final RecipientRepository recipientRepository;
    private final VisitRequestRepository visitRequestRepository;
    private final ReservationRepository reservationRepository;
    private final Clock clock;

    @Value("${seed.caregivers:1000}")
    private int caregiverCount;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (caregiverRepository.count() > 0) {
            log.info("[seed] 이미 보호사 데이터가 있어 샘플 데이터를 넣지 않습니다.");
            return;
        }
        Random random = new Random(42);   // 매번 같은 데이터가 나오도록 고정
        LocalDate today = LocalDate.now(clock);

        List<Recipient> recipients = new ArrayList<>();
        for (int i = 0; i < RECIPIENTS; i++) {
            recipients.add(Recipient.create("수급자" + i, null, seoulLat(random), seoulLng(random)));
        }
        recipientRepository.saveAll(recipients);

        List<Caregiver> caregivers = new ArrayList<>();
        List<CaregiverAvailability> availabilities = new ArrayList<>();
        for (int i = 0; i < caregiverCount; i++) {
            Caregiver caregiver = Caregiver.create("보호사" + i, i % 2 == 0 ? Gender.FEMALE : Gender.MALE,
                    seoulLat(random), seoulLng(random));
            if (i % 20 == 0) {
                caregiver.deactivate();   // 5% 는 휴직 → 추천에서 빠져야 함
            }
            caregivers.add(caregiver);

            // 3명 중 2명은 09~18시, 1명은 13~21시 근무 (모든 요일)
            LocalTime start = i % 3 == 2 ? LocalTime.of(13, 0) : LocalTime.of(9, 0);
            LocalTime end = i % 3 == 2 ? LocalTime.of(21, 0) : LocalTime.of(18, 0);
            for (DayOfWeek day : DayOfWeek.values()) {
                availabilities.add(CaregiverAvailability.create(caregiver, day, start, end));
            }
        }
        caregiverRepository.saveAll(caregivers);
        availabilityRepository.saveAll(availabilities);

        // 최근 30일 안의 완료 방문 → 근무량·연속 담당 점수가 0 이 아니게
        List<VisitRequest> pastRequests = new ArrayList<>();
        List<Reservation> history = new ArrayList<>();
        for (int i = 0; i < caregiverCount; i++) {
            for (int v = 0; v < COMPLETED_VISITS_PER_CAREGIVER; v++) {
                Recipient recipient = recipients.get(random.nextInt(RECIPIENTS));
                LocalDate date = today.minusDays(1 + v * 7L + random.nextInt(7));
                VisitRequest past = VisitRequest.create(recipient, date, LocalTime.of(14, 0), LocalTime.of(16, 0));
                past.assign();
                Reservation reservation = Reservation.confirm(caregivers.get(i), past);
                reservation.complete();
                pastRequests.add(past);
                history.add(reservation);
            }
        }
        visitRequestRepository.saveAll(pastRequests);
        reservationRepository.saveAll(history);

        log.info("[seed] 샘플 데이터 생성 완료: 보호사 {}명, 근무 가능 시간 {}건, 수급자 {}명, 완료 방문 {}건",
                caregivers.size(), availabilities.size(), recipients.size(), history.size());
    }

    // 서울 시내 대략 범위 (위도 37.45~37.70, 경도 126.80~127.15)
    private double seoulLat(Random random) {
        return 37.45 + random.nextDouble() * 0.25;
    }

    private double seoulLng(Random random) {
        return 126.80 + random.nextDouble() * 0.35;
    }
}
