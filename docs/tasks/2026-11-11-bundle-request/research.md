# Research: 묶음 신청 (여러 날짜 방문 요청을 한 번에 신청·추천·배정)

## 1. 범위
- 조사한 경로
  - `request/**` (방문 요청 등록·조회)
  - `recommendation/**` (추천 서비스·필터·점수·랭커)
  - `reservation/**` (확정·취소, 락 전략 4종, 리포지토리)
  - `caregiver/domain`, `caregiver/repository`, `recipient/domain`
  - `common/exception`, `common/time/TimeSlots`, `common/config/RedissonConfig`
  - 테스트: `reservation/*Test`, `ConcurrencyTestSupport`, `recommendation/*Test`, `RecommendationLatencyTest`
  - 문서: `docs/oncare-review.md` 4절, `README.md` "한계와 남은 일"
- 조사 목적: 지금은 방문 요청 하나가 날짜 하나다. 여러 날짜를 한 번에 신청하고, **모든 날짜에 갈 수 있는 보호사**를 추천하고, 한 번에 **같은 보호사로 전부 배정**하는 기능을 넣기 위해 기존 흐름에서 재사용할 것과 깨지는 곳을 파악한다.

### 원래 기능(온케어)의 정의 — `docs/oncare-review.md:68`
> 묶음 신청(`requestGroup`): 여러 날짜를 한 번에 신청하면 모든 날짜에 갈 수 있는 보호사를 우선 추천하고, 배정할 때 나머지 날짜도 같은 보호사로 배정한다.
> 온케어는 날짜마다 필터 전체를 다시 돌려 **날짜 수 × 2N 쿼리**가 나오고, 이중 for 문으로 교집합을 구했다. 가져온다면 날짜 목록을 한 번에 조회하는 방식으로.

또 `docs/oncare-review.md:58`: 온케어는 **관리자가 고른 날짜는 배정 직전에 다시 검증하지 않고** 묶음의 다른 날짜만 `isAvailable`로 확인했다. 이 빈틈을 다시 만들면 안 된다.

---

## 2. 전체 흐름 (현재, 단일 날짜)

### 2.1 방문 요청 등록 `POST /api/visit-requests`
1. `VisitRequestController.register` — `request/controller/VisitRequestController.java:27-31`. `@Valid` → 201 + `Location: /api/visit-requests/{id}`
2. `VisitRequestService.register` — `request/service/VisitRequestService.java:23-30`. 클래스는 `@Transactional(readOnly = true)`, 메서드에 `@Transactional`
   - 수급자 조회, 없으면 `RECIPIENT_NOT_FOUND`(404) (`:25-26`)
   - `VisitRequest.create(...)` → `TimeSlots.validateRange`로 시작 < 종료와 30분 단위를 검증 (`request/domain/VisitRequest.java:67-70`, `common/time/TimeSlots.java:17-24`)
   - `save` 후 `VisitRequestResponse.from` (`request/dto/VisitRequestResponse.java:18-26`)
3. DTO 검증 — `VisitRequestCreateRequest` (`request/dto/VisitRequestCreateRequest.java:9-14`): `@NotNull` 4개, `visitDate`에 `@FutureOrPresent`
- 쿼리: 수급자 SELECT 1 + INSERT 1

### 2.2 추천 `GET /api/visit-requests/{id}/recommendations?size=3`
`RecommendationService.recommend` — `recommendation/service/RecommendationService.java:52-65`, `@Transactional(readOnly = true)`
1. size 1~10 검증 → `INVALID_RECOMMENDATION_SIZE`(400) (`:53-55`)
2. `findWithRecipientById` (join fetch recipient) (`request/repository/VisitRequestRepository.java:13-14`) → 없으면 404, `isAssignable()`이 아니면 409 `VISIT_REQUEST_NOT_ASSIGNABLE` (`:56-60`)
3. `filterCandidates` (`:68-92`)
   - 1차 후보: `findByStatus(ACTIVE)` 또는 `findByStatusAndGender(ACTIVE, 선호 성별)` (`:95-100`, `caregiver/repository/CaregiverRepository.java:14-16`)
   - 후보 전체의 **방문 요일** 근무 가능 시간을 IN으로 1번 조회: `findByCaregiverIdInAndDayOfWeek(ids, dayOfWeek)` (`:76-78`, `CaregiverAvailabilityRepository.java:15`)
   - 후보 전체의 **방문 날짜** 예약(취소 제외)을 IN으로 1번 조회: `findByCaregiverIdInAndVisitDateAndStatusNot(ids, date, CANCELED)` (`:79-81`, `ReservationRepository.java:21-22`)
   - 보호사별로 `groupingBy` → `CaregiverSchedule(caregiver, availabilities, reservations)` → 모든 `CandidateFilter`가 `allMatch`인 보호사만 남김 (`:83-91`)
4. `toCandidates` (`:103-124`): 거리(`GeoDistance.km`), 최근 30일 완료 수(`countByCaregiverAndPeriod`), 이 수급자 완료 수(`countByCaregiverAndRecipient`) — GROUP BY 집계 2번
5. `RecommendationRanker.rank` (`recommendation/service/RecommendationRanker.java:32-38`): 정책별 점수 합 → `RANKING`(총점 desc → 거리 asc → 근무량 asc → id asc, `:23-27`) → limit
- **쿼리 수: 후보 수와 상관없이 최대 6번** (`RecommendationService.java:35`), `RecommendationQueryCountTest`로 검증 중

### 2.3 예약 확정 `POST /api/visit-requests/{id}/reservations` `{caregiverId}`
1. `ReservationController.confirm` (`reservation/controller/ReservationController.java:24-29`) → 201
2. `ReservationService.confirm` (`reservation/service/ReservationService.java:34-36`) — **`@Transactional`을 일부러 붙이지 않음** (`:14`). 낙관적 락 재시도와 Redis 락은 트랜잭션 **바깥**을 감싸야 하기 때문
3. 생성 시 `reservation.lock.strategy`로 전략 1개를 고름. 이름이 틀리면 앱이 뜰 때 `IllegalStateException` (`:21-32`)
4. 전략 → 모두 `ReservationWriter.confirm(visitRequestId, caregiverId, LockModeType)`를 호출

| 전략 | 파일 | 트랜잭션 밖에서 하는 일 | 보호사 행 락 모드 |
|---|---|---|---|
| unique (A) | `lock/UniqueConstraintStrategy.java:23-25` | 없음 | `NONE` |
| pessimistic (B, 기본) | `lock/PessimisticLockStrategy.java:24-26` | 없음 | `PESSIMISTIC_WRITE` (SELECT … FOR UPDATE) |
| optimistic (C) | `lock/OptimisticLockStrategy.java:31-40` | `ConcurrencyFailureException`이면 최대 5번 재시도, 다 실패하면 `CONCURRENT_MODIFICATION` | `OPTIMISTIC_FORCE_INCREMENT` |
| redisson (D) | `lock/RedissonLockStrategy.java:35-58` | 요청을 먼저 읽어 날짜를 얻음 → `lock:caregiver:{id}:{date}` `tryLock(5s)` → writer 호출 → `finally unlock` | `NONE` |

5. `ReservationWriter.confirm` (`reservation/service/ReservationWriter.java:44-79`), **`@Transactional(isolation = READ_COMMITTED)`**
   1. `findWithRecipientById` → 404 / `isAssignable` 아니면 409 (`:46-50`)
   2. `em.find(Caregiver.class, id, lockMode)` → null이면 404 (`:53-56`)
   3. 재검증: `caregiver.isActive()`, `recipient.accepts(gender)` → `CAREGIVER_NOT_AVAILABLE` (`:59-61`)
   4. `loadSchedule` (`:93-99`): **보호사 1명 × 그 요일** 근무 가능 시간, **보호사 1명 × 그 날짜** 예약(취소 제외)
   5. `WorkingHoursFilter.accept` 실패 → `CAREGIVER_NOT_AVAILABLE`, `ScheduleConflictFilter.accept` 실패 → `RESERVATION_CONFLICT` (`:63-69`)
   6. `Reservation.confirm(caregiver, request)`, `request.assign()` (`:71-72`)
   7. `saveAndFlush` → `DataIntegrityViolationException`을 `RESERVATION_CONFLICT`로 바꿈 (유니크 제약은 마지막 안전장치, `:73-78`)
   - 커밋할 때 `VisitRequest.@Version`이 올라감 → 같은 요청을 동시에 다른 보호사로 배정하면 늦게 커밋한 쪽이 실패 (`VisitRequest.java:52-57`)
- **READ COMMITTED가 필수인 이유** (`ReservationWriter.java:39-43`): MySQL REPEATABLE READ는 첫 SELECT 때 스냅샷을 고정한다. 요청을 먼저 읽고 보호사 락을 기다리면, 락을 얻은 뒤에도 앞사람이 방금 커밋한 예약이 안 보인다. 커밋 `c5d2466`에서 고친 버그다.
- 쿼리(비관적 락 기준): 요청+수급자 1, 보호사 FOR UPDATE 1, 근무 가능 시간 1, 그날 예약 1, INSERT 1, 요청 UPDATE(version) 1

### 2.4 예약 취소 `DELETE /api/reservations/{id}`
- `ReservationWriter.cancel` (`ReservationWriter.java:81-91`), `@Transactional` (기본 격리)
- 없으면 404, 이미 취소면 409 `RESERVATION_ALREADY_CANCELED`
- `reservation.cancel()`: status = CANCELED, `activeSlot = null` (`Reservation.java:96-99`) → 유니크 인덱스에서 빠져서 같은 슬롯에 다시 예약 가능
- `visitRequest.unassign()`: 다시 REQUESTED (`VisitRequest.java:81-83`)
- 락 없음. `Reservation.@Version`이 같은 예약의 동시 변경을 막음 (`Reservation.java:73-76`)

---

## 3. 주요 파일과 책임

| 파일 | 역할 | 묶음 신청과의 관계 |
|---|---|---|
| `request/domain/VisitRequest.java` | 요청 1건 = 날짜 1개 + 시간대, 상태, `@Version` | 묶음 소속을 나타낼 필드가 없음. 추가 대상 |
| `request/domain/VisitRequestStatus.java` | REQUESTED / ASSIGNED / CANCELED | 묶음 상태는 하위 요청들에서 계산 가능 |
| `request/service/VisitRequestService.java` | 단건 등록·조회 | 묶음 등록 메서드 추가 후보 |
| `request/repository/VisitRequestRepository.java` | `findWithRecipientById` | 묶음 id로 하위 요청 일괄 조회 메서드 필요 |
| `recommendation/service/RecommendationService.java` | 6쿼리 추천 | 날짜 여러 개를 한 번에 조회하도록 확장할 핵심 |
| `recommendation/domain/CaregiverSchedule.java` | 보호사 1명의 근무 시간·예약 묶음 | **여러 날짜 데이터를 그대로 담을 수 있음** (4.1 참고) |
| `recommendation/filter/*` | 근무 시간·겹침 필터 | **수정 없이 날짜별로 재사용 가능** |
| `recommendation/service/RecommendationRanker.java` | 점수·정렬 (DB 안 씀) | 그대로 재사용 |
| `reservation/service/ReservationWriter.java` | 확정·취소 트랜잭션 | 여러 요청을 한 트랜잭션에서 확정하는 메서드 필요 |
| `reservation/service/ReservationService.java` | 전략 선택, 트랜잭션 없음 | 묶음 확정 진입점 |
| `reservation/lock/*` | 락 전략 4종 | 인터페이스가 `confirm(Long, Long)` 단건뿐. 묶음용 메서드 필요 |
| `common/exception/ErrorCode.java` | 에러 코드 | 묶음 전용 코드 추가 후보 |
| `common/config/RedissonConfig.java` | D 전략 빈 등록 | 생성자 인자가 바뀌면 같이 수정 |

---

## 4. 따라야 할 기존 패턴

- **레이어**: controller → service → repository. DTO는 `record` + 정적 `from`. 엔티티는 `@NoArgsConstructor(PROTECTED)` + 정적 팩토리(`create`/`confirm`) + 상태 변경 메서드
- **예외**: `BusinessException(ErrorCode)` → `GlobalExceptionHandler`가 `{code, message}`로 응답 (`common/exception/*`). `@Valid` 실패는 400 `INVALID_INPUT`, 메시지는 "필드: 메시지"
- **검증 위치**: 형식은 DTO 애너테이션, 도메인 규칙(시간 슬롯)은 엔티티 팩토리, 상태·가용성은 writer에서 다시 검증
- **조회 원칙**: "후보 전체를 한 번에" IN 조회 → `groupingBy`로 나눔. 보호사마다 조회하지 않음 (`ReservationRepository.java:14`)
- **필터·점수 확장 방식**: `CandidateFilter`, `ScorePolicy` 구현체를 `@Component` + `@Order`로 추가하면 서비스가 자동으로 주입받음
- **시간**: `Clock` 빈 주입 (`common/config/ClockConfig.java`), `LocalDate.now(clock)`
- **트랜잭션 경계**: 락 전략·재시도는 트랜잭션 밖(`ReservationService`, `lock/*`), 실제 쓰기는 트랜잭션 안(`ReservationWriter`)
- **주석 스타일**: 한국어, "왜"와 온케어와의 차이를 적음

### 4.1 그대로 재사용 가능한 필터 동작 (중요)
- `ScheduleConflictFilter.accept` (`recommendation/filter/ScheduleConflictFilter.java:15-20`)는 `reservation.getVisitDate().equals(request.getVisitDate())`로 **날짜를 먼저 거른다**. 그래서 `CaregiverSchedule.reservations`에 **여러 날짜의 예약이 섞여 있어도** 요청 날짜 것만 본다.
- `WorkingHoursFilter.accept` (`WorkingHoursFilter.java:14-18`) → `CaregiverAvailability.covers(day, start, end)` (`CaregiverAvailability.java:63-65`)는 `dayOfWeek == day`를 확인한다. 그래서 `availabilities`에 **여러 요일**이 섞여 있어도 된다.
- 결론: 보호사별로 "묶음의 모든 요일 근무 시간 + 모든 날짜 예약"을 담은 `CaregiverSchedule` 하나를 만들고, **하위 요청마다 `filters.allMatch`를 돌리면** 필터 코드를 고치지 않고 "모든 날짜 가능" 판정이 된다.

---

## 5. 재사용할 수 있는 기존 로직

| 로직 | 위치 | 묶음에서의 쓰임 |
|---|---|---|
| `VisitRequest.create` + `TimeSlots.validateRange` | `VisitRequest.java:67-70` | 날짜마다 하위 요청 생성 |
| `findActiveCaregivers` (상태·성별 DB 필터) | `RecommendationService.java:95-100` | 묶음도 수급자는 하나라 그대로 |
| `CandidateFilter` 2종 | `recommendation/filter/*` | 4.1처럼 날짜별 반복 |
| `toCandidates` (거리·근무량·연속 담당) | `RecommendationService.java:103-124` | 수급자 기준이라 날짜와 무관. 그대로 |
| `RecommendationRanker` + `RecommendationResponse` | 각 파일 | 그대로 |
| `ReservationWriter`의 재검증 순서와 예외 매핑 | `ReservationWriter.java:44-79` | 날짜마다 같은 검증을 반복. private 메서드로 뽑으면 단건·묶음이 공유 가능 |
| `Reservation.confirm`, `cancel`, `overlaps` | `Reservation.java` | 그대로 |
| `ReservationLockStrategy` 패턴 | `lock/*` | 묶음 확정에도 같은 "전략 바깥, writer 안" 구조 |
| `ConcurrencyTestSupport.runConcurrently`, `overlappingPairs` | 테스트 | 묶음 동시성 테스트에 재사용 |

### 지금 없는 리포지토리 메서드 (추가 필요)
- 근무 가능 시간 **여러 요일** 한 번에: `findByCaregiverIdInAndDayOfWeekIn(Collection<Long>, Collection<DayOfWeek>)`. 인덱스 `idx_availability_caregiver_day(caregiver_id, day_of_week)`를 그대로 탐 (`CaregiverAvailability.java:28`)
- 예약 **여러 날짜** 한 번에: `findByCaregiverIdInAndVisitDateInAndStatusNot(Collection<Long>, Collection<LocalDate>, ReservationStatus)`. 인덱스 `idx_reservation_caregiver_date(caregiver_id, visit_date)`를 그대로 탐 (`Reservation.java:38`)
- 묶음의 하위 요청 한 번에(수급자 함께): `select v from VisitRequest v join fetch v.recipient where v.group.id = :groupId order by v.visitDate`

이 세 개를 쓰면 묶음 추천 쿼리는 **날짜 수와 상관없이 6번**으로 유지할 수 있다 (온케어: 날짜 수 × 2N).

---

## 6. 쿼리·동시성·스키마

### 6.1 쿼리 수 예상
| 작업 | 현재 단건 | 묶음 (K일, 후보 N명) — 일괄 조회 시 | 온케어 방식 |
|---|---|---|---|
| 추천 | 6 | 6 (요청 목록 1 + 보호사 1 + 근무 시간 1 + 예약 1 + 집계 2) | K × 2N + α |
| 확정 (비관적) | 요청 1 + 보호사 락 1 + 근무 1 + 예약 1 + INSERT 1 + UPDATE 1 | 요청 목록 1 + 보호사 락 **1** + 근무 1 + 예약 1 + INSERT K + UPDATE K | — |

- 확정할 때 날짜마다 `loadSchedule`을 따로 부르면 2K번이 된다. 묶음 전용으로 한 번에 읽을지는 plan에서 결정할 일
- `saveAndFlush`를 K번 하면 flush도 K번. INSERT 자체는 IDENTITY 전략이라 JDBC 배치가 안 됨 (Hibernate는 IDENTITY에서 insert 배치를 끔)

### 6.2 락 전략별 묶음 확정 시 고려점
| 전략 | 묶음에서 그대로 되는가 | 주의점 |
|---|---|---|
| pessimistic (B) | **됨.** 보호사 행 하나에 FOR UPDATE를 한 번 걸면 묶음 전체 날짜가 보호됨 (락이 보호사 단위라서) | README의 한계(다른 날짜끼리도 기다림)는 그대로. 묶음은 락을 쥔 시간이 K배로 길어짐 |
| optimistic (C) | **됨.** 보호사 version을 트랜잭션당 1번 올림 → 충돌하면 묶음 전체 재시도 | 재시도 단위가 묶음 전체. K가 크면 재시도 비용이 큼 |
| unique (A) | 같은 시작 시간만 막힘 (단건과 같은 한계) | 묶음 중 한 날짜가 유니크 위반이면 전체 롤백돼야 함 |
| redisson (D) | **그대로는 안 됨.** 키가 `lock:caregiver:{id}:{date}` 날짜 단위 (`RedissonLockStrategy.java:40`) | 날짜 K개 락을 모두 잡아야 함 → `RedissonClient.getMultiLock(...)`. 서로 다른 묶음이 같은 날짜들을 다른 순서로 잡으면 데드락이 생길 수 있으니 **날짜 정렬** 필요. 지금 생성자는 단건 요청 날짜만 읽음 (`:37-39`) |

### 6.3 원자성 (all-or-nothing vs 부분 배정)
- 단건 writer가 `@Transactional`(REQUIRED)이라, 묶음 메서드가 같은 빈 안에서 한 트랜잭션으로 반복하면 **하나라도 실패하면 전부 롤백**된다.
- `ReservationWriter.confirm`을 바깥 빈에서 K번 호출하면 각각 별도 트랜잭션이 되어 **부분 성공**이 남는다. 같은 클래스 안에서 self-invocation하면 프록시를 안 타서 트랜잭션 설정(READ_COMMITTED)이 적용되지 않으니 주의
- 온케어의 의도("나머지 날짜도 같은 보호사로")와 추천 조건("모든 날짜 가능")을 보면 **전부 아니면 전무**가 자연스럽다. 다만 "일부 날짜만 가능한 보호사"를 허용할지는 정책 결정이 필요 (9절)
- `BusinessException`은 RuntimeException이라 롤백됨. 다만 트랜잭션 안에서 `DataIntegrityViolationException`을 잡아 변환하면, 그 트랜잭션은 이미 rollback-only일 수 있다 → 지금처럼 예외를 다시 던지면 문제없음

### 6.4 스키마
- 묶음을 표현하는 방법 두 가지
  1. 새 엔티티 `VisitRequestGroup`(id, recipient, 생성 시각) + `VisitRequest.group`(`@ManyToOne`, **nullable**). 단건 요청은 group = null
  2. 새 테이블 없이 `VisitRequest.groupId`(UUID 등) 컬럼만 추가
- `ddl-auto: update` (`src/main/resources/application.yml:15`)는 **컬럼·테이블 추가는 하지만 제약을 지우거나 NOT NULL로 바꾸지 않는다.** 기존 `visit_request` 행이 있으므로 새 FK 컬럼은 반드시 nullable이어야 로컬 DB에서 문제없이 뜬다
- 테스트는 `create-drop` (`src/test/resources/application.yml`, `application-mysql.yml`) → 테스트에는 영향 없음
- 인덱스: 그룹으로 하위 요청을 찾으므로 `visit_request(group_id)` 인덱스가 필요. `@Table(indexes=...)` 추가는 update가 만들어 줌
- `Reservation`에는 그룹 정보가 필요 없음 (`visitRequest.group`으로 거슬러 올라감). 묶음 취소에서 그룹의 예약을 한 번에 찾으려면 `reservation.visitRequest.group.id` 조인 쿼리

### 6.5 같은 묶음 안의 날짜끼리
- 하위 요청끼리 날짜가 같으면, 같은 보호사 같은 날짜에 겹치는 예약을 스스로 만들 수 있다. 확정 시 앞 날짜 INSERT를 flush한 뒤 다음 날짜의 겹침 조회에서 보이긴 하지만(같은 트랜잭션), **등록 단계에서 날짜 중복을 막는 게** 깔끔함 (DTO 검증 또는 서비스 검증)

---

## 7. 테스트 현황

| 테스트 | 방식 | 묶음과의 관계 |
|---|---|---|
| `RegistrationApiTest` | `@SpringBootTest` + `@Transactional` + MockMvc | 묶음 등록 API 테스트를 같은 방식으로 추가 |
| `RecommendationApiTest` | 같은 방식, `completedVisit` 헬퍼 (`:159`) | 묶음 추천 테스트 추가. "한 날짜라도 불가하면 제외" 케이스 |
| `RecommendationQueryCountTest` | Hibernate 통계로 쿼리 수 확인 (`:33`, `:45`) | **묶음 추천도 날짜 수와 상관없이 쿼리 수가 일정**한지 같은 방식으로 검증 가능 |
| `ReservationApiTest` | 단건 확정·취소 규칙 8개 (`:62-139`) | 묶음 확정: 전부 성공 / 한 날짜 충돌 시 전체 실패·상태 원복 / 묶음 취소 |
| `ReservationConcurrencyTest` `@Tag("concurrency")` | 실제 커밋, 32스레드 100건 (`ConcurrencyTestSupport`) | 묶음끼리, 묶음과 단건이 겹칠 때 겹친 쌍 0 확인. mysqlTest 대상 |
| `RedissonConcurrencyTest` | Redis 필요 시에만 | redisson 묶음 지원 여부에 따라 |
| `CandidateFilterTest`, `ScorePolicyTest` | 단위 | 필터를 안 고치면 변경 없음 |

빠진 케이스(지금 없음): 여러 날짜 관련 테스트 전부.

### 테스트 정리 순서 (건드리면 깨지는 곳)
`ConcurrencyTestSupport.cleanUp` (`:48-56`)와 `RecommendationLatencyTest` (`:60-64`)는 `deleteAllInBatch`를 **FK 순서대로** 호출한다: reservation → visit_request → availability → caregiver → recipient. 그룹 테이블을 추가하면 **visit_request 다음, recipient 전에** 그룹을 지워야 한다. 안 그러면 FK 위반으로 정리가 실패한다. `SampleDataSeederTest`도 확인 필요.

---

## 8. 위험 요소 (건드리면 깨지는 곳)

1. **`ReservationWriter.confirm`의 격리 수준**: 묶음 확정 메서드도 반드시 `READ_COMMITTED`여야 한다. 빠뜨리면 MySQL에서만 겹친 예약이 들어가는 버그(`c5d2466`)가 다시 생기고, H2 테스트로는 안 보일 수 있다 (H2 테스트는 REPEATABLE READ로 맞춰 두긴 함, `src/test/resources/application.yml`)
2. **self-invocation**: 묶음 메서드가 같은 클래스의 `confirm`을 부르면 `@Transactional` 설정이 적용되지 않는다. 공통 검증 로직은 트랜잭션 애너테이션 없는 private 메서드로 빼야 한다
3. **`ReservationLockStrategy` 인터페이스 변경**: 메서드를 추가하면 구현체 4개 + `RedissonConfig` 빈 등록 + 테스트에서 전략을 직접 부르는 곳(`ReservationConcurrencyTest.strategy(name)`)이 영향을 받는다. default 메서드로 추가하면 영향이 줄어든다
4. **Redisson 다중 락 순서**: 날짜 정렬 없이 MultiLock을 쓰면 서로 다른 묶음끼리 데드락이 날 수 있다. tryLock 대기 시간(5초)을 넘으면 `RESERVATION_BUSY`(503)
5. **`VisitRequest.@Version`**: 묶음 하위 요청을 K개 수정하면 UPDATE K번에 버전 검사 K번. 같은 묶음을 두 관리자가 동시에 확정하면 늦게 커밋한 쪽이 `ObjectOptimisticLockingFailureException` → `GlobalExceptionHandler`가 409 `CONCURRENT_MODIFICATION`으로 응답 (`GlobalExceptionHandler.java:41-46`). 원하는 동작
6. **비관적 락 보유 시간**: 묶음 K일을 한 트랜잭션에서 처리하는 동안 보호사 행 락을 계속 쥔다. 같은 보호사의 다른 확정이 그만큼 기다림 (H2 `LOCK_TIMEOUT=10000`)
7. **기존 단건 API**: 그룹에 속한 하위 요청을 단건 API로 따로 확정·취소할 수 있게 둘지 막을지 정해야 한다. 그냥 두면 묶음 일부만 다른 보호사로 배정되는 상태가 생길 수 있음
8. **`@FutureOrPresent`는 `List<LocalDate>` 원소에 바로 안 붙음** → `List<@NotNull @FutureOrPresent LocalDate>`처럼 타입 인자에 붙여야 함 (Bean Validation 2.0 컨테이너 원소 검증)
9. **기존 로컬 DB**: `ddl-auto: update`로 새 컬럼은 생기지만 nullable만 안전함 (6.4)
10. **README·oncare-review 문서 수치**: 추천 쿼리 수 6회는 단건 기준 표기. 묶음을 추가해도 단건 수치는 바뀌면 안 됨 (`RecommendationQueryCountTest` 그대로 통과해야 함)

---

## 9. 열린 질문 (사람이 정해야 할 것)

1. **원자성**: 묶음 확정은 전부 아니면 전무로 할까요, 아니면 가능한 날짜만 배정(부분 성공)도 허용할까요? (조사 결과로는 전부 아니면 전무가 자연스러움)
2. **추천 기준**: "모든 날짜에 가능한 보호사만" 추천할까요, 아니면 온케어처럼 "모든 날짜 가능자를 **우선**하고 일부만 가능한 보호사도 뒤에 보여줄까요"? 후자라면 가능 날짜 수를 응답에 넣어야 함
3. **시간대**: 묶음의 모든 날짜가 같은 시작·종료 시간인가요, 날짜마다 다를 수 있나요?
4. **최대 날짜 수**: 상한(예: 31일 또는 14일)을 둘까요?
5. **표현 방식**: 새 엔티티 `VisitRequestGroup`(+ API `/api/visit-request-groups`) vs `VisitRequest`에 groupId 컬럼만 추가
6. **단건 API와의 관계**: 그룹에 속한 요청을 단건 확정·취소 API로 건드리는 것을 막을까요?
7. **묶음 취소**: 묶음 전체 취소 API가 필요한가요, 아니면 날짜별 단건 취소만으로 충분한가요?
8. **redisson 전략**: 묶음 확정에서 D 전략(MultiLock)까지 지원할까요, 아니면 이번엔 B(기본)·C만 지원하고 D는 "지원 안 함"으로 막을까요?
