# Plan: 묶음 신청 (여러 날짜 한 번에 신청 → 모든 날짜 가능자 추천 → 같은 보호사로 일괄 확정·취소)

> 메모는 해당 위치에 `<!-- NOTE: ... -->`로 달고 `/apply-notes`로 반영한다.
> 근거 조사: [research.md](research.md)
> research.md 9절의 열린 질문은 아래 **0. 결정 사항**에 기본값을 정해 두었다. 다르게 하고 싶으면 그 줄에 NOTE를 달면 된다.

## 0. 결정 사항 (research.md 9절 답)

| # | 질문 | 이 계획의 선택 | 이유 |
|---|---|---|---|
| 1 | 원자성 | **전부 아니면 전무**: 한 날짜라도 실패하면 묶음 전체 롤백 | "같은 보호사로 전부 배정"이 기능의 정의. 부분 성공을 허용하면 응답·상태가 복잡해짐 |
| 2 | 추천 기준 | **모든 날짜에 가능한 보호사만** 추천 | 1번과 맞물림. 일부만 가능한 보호사는 확정해도 실패하므로 보여줄 이유가 없음 |
| 3 | 시간대 | 모든 날짜가 **같은 시작·종료 시간** | 온케어 묶음 신청과 같음. 날짜별 시간은 범위 밖 |
| 4 | 최대 날짜 수 | **14일** | 2주 단위 정기 방문 기준. 비관적 락 보유 시간 상한도 같이 정해짐 |
| 5 | 표현 방식 | 새 엔티티 **`VisitRequestGroup`** + `VisitRequest.group`(nullable FK) | 묶음 단위 조회·추천·확정 API의 주소가 생김. groupId 컬럼만 두면 묶음 자체 정보(시간대·수급자)를 둘 곳이 없음 |
| 6 | 단건 API와의 관계 | 하위 요청을 단건 API로 **확정·취소하는 것을 막지 않음** | 묶음 중 하루만 취소하고 그날만 다른 보호사로 채우는 흐름이 필요함 |
| 7 | 묶음 취소 | **묶음 전체 취소 API 추가** | 일괄 확정의 짝 |
| 8 | redisson | **지원**: 날짜별 락 키를 **정렬된 순서로** 모두 획득 | 4가지 전략 비교가 이 프로젝트의 핵심이라 묶음에서도 전략 설정이 그대로 동작해야 함 |

## 1. 목표
- 수급자가 여러 날짜(같은 시간대)를 한 번에 신청한다.
- **모든 날짜에 갈 수 있는** 보호사를 기존과 같은 점수로 추천한다. 쿼리 수는 **날짜 수와 상관없이 6번**이다(온케어는 날짜 수 × 2N).
- 고른 보호사로 **모든 날짜를 한 트랜잭션에서 확정**한다. 확정 직전에 날짜마다 다시 검증한다(온케어의 "고른 날짜 재검증 없음" 빈틈을 다시 만들지 않음).
- 묶음 단위로 일괄 취소한다.

**완료 조건**
- 아래 API 5개가 동작하고 테스트가 있다.
- 기존 단건 테스트(쿼리 수 6, 동시성 결과)가 그대로 통과한다.
- `./gradlew test` 통과. 동시성 테스트(`@Tag("concurrency")`)는 H2에서 통과하고, `mysqlTest` 대상으로 표시한다.

## 2. 범위

**포함**
| API | 설명 |
|---|---|
| `POST /api/visit-request-groups` | 묶음 등록. 날짜마다 `VisitRequest` 생성 |
| `GET /api/visit-request-groups/{groupId}` | 묶음과 하위 요청 조회 |
| `GET /api/visit-request-groups/{groupId}/recommendations?size=3` | 모든 날짜 가능자 추천 |
| `POST /api/visit-request-groups/{groupId}/reservations` `{caregiverId}` | 일괄 확정 (락 전략 설정을 따름) |
| `DELETE /api/visit-request-groups/{groupId}/reservations` | 묶음의 살아 있는 예약을 모두 취소 |

**제외 (이번에 안 함)**
- 날짜마다 다른 시간대
- "일부 날짜만 가능한" 보호사 추천, 날짜별로 다른 보호사에게 나눠서 배정
- 반복 규칙("매주 월·수 4주") 입력. 클라이언트가 날짜 목록을 만들어 보냄
- 묶음에 날짜 추가·삭제(수정 API)
- k6 스크립트, README 결과 표 갱신 (기능이 들어간 뒤 따로)

## 3. 접근 방식

### 3.1 추천: 단건 추천 코드를 "요청 목록" 기준으로 일반화
- `RecommendationService`의 핵심을 `recommend(List<VisitRequest> requests, Recipient recipient, int size)`로 바꾼다. **단건은 크기 1인 목록**으로 같은 경로를 탄다.
- 조회를 "요일 목록·날짜 목록" IN 조회로 바꾼다. 근무 가능 시간 1번, 예약 1번이라서 날짜가 늘어도 쿼리 수가 같다.
- 보호사마다 `CaregiverSchedule` 하나에 모든 요일 근무 시간과 모든 날짜 예약을 담는다. 그리고 **요청마다 기존 필터를 `allMatch`** 한다. research.md 4.1에서 확인했듯 두 필터는 날짜·요일로 먼저 거르기 때문에 **필터 코드는 그대로** 둔다.
- 버린 대안: 날짜마다 기존 `recommend`를 부르고 교집합을 구하는 방식. 구현은 쉽지만 온케어처럼 쿼리가 날짜 수에 비례하고, 점수 계산도 날짜 수만큼 반복된다.
- 버린 대안: 단건 경로는 그대로 두고 묶음용 메서드를 따로 만드는 방식. 필터·조회 로직이 두 벌이 된다. 단건 경로가 바뀌는 위험은 `RecommendationQueryCountTest`(쿼리 수 6)와 `RecommendationApiTest`가 지켜 준다.

### 3.2 확정: `ReservationWriter`에 묶음 메서드, 검증은 공통 private 메서드로
- `ReservationWriter.confirmGroup(groupId, caregiverId, lockMode)`를 **`READ_COMMITTED` 트랜잭션 하나**로 만든다.
- 순서는 이렇다: 하위 요청 일괄 조회 → 보호사 락 **1번** → 보호사 상태·성별 검증 → 모든 날짜의 근무 시간·예약 **한 번에** 조회 → 날짜마다 근무 시간·겹침 검증 → `saveAllAndFlush`.
- 단건 `confirm`과 겹치는 검증(상태·성별 → 근무 시간 → 겹침)은 트랜잭션 애너테이션이 없는 **private 메서드**로 뺀다. 같은 클래스 안에서 `@Transactional` 메서드를 부르면 프록시를 안 타서 격리 수준이 적용되지 않기 때문이다(research.md 8-2).
- 버린 대안: 바깥에서 단건 `confirm`을 K번 호출하는 방식. 트랜잭션이 K개가 되어 부분 성공이 남고, 보호사 락도 K번 잡았다 놓는다.

### 3.3 락 전략: 인터페이스에 `confirmGroup` 추가
| 전략 | 묶음 확정 방식 |
|---|---|
| unique | `writer.confirmGroup(..., NONE)` |
| pessimistic | `writer.confirmGroup(..., PESSIMISTIC_WRITE)`. 보호사 행 락 하나로 모든 날짜가 보호됨 |
| optimistic | 기존과 같은 재시도 루프로 `confirmGroup(..., OPTIMISTIC_FORCE_INCREMENT)`. 충돌하면 묶음 전체를 새 트랜잭션으로 재시도 |
| redisson | 날짜 목록을 읽고 **정렬**한 다음 `lock:caregiver:{id}:{date}`를 앞에서부터 차례로 `tryLock`. 하나라도 못 잡으면 잡은 것을 모두 풀고 `RESERVATION_BUSY`를 던짐. 성공하면 writer 호출 후 `finally`에서 역순으로 해제 |

- 정렬하는 이유: 묶음 X(3일·5일)와 묶음 Y(5일·3일)가 서로 반대 순서로 잡으면 서로를 기다리는 데드락이 생긴다. 모두 같은 순서(날짜 오름차순)로 잡으면 순환 대기가 생기지 않는다.
- 버린 대안: `redissonClient.getMultiLock(...)`. 한 줄로 끝나지만 내부 획득 순서와 대기 시간 분배가 드러나지 않는다. 이 프로젝트는 락 동작을 설명하는 게 목적이라 직접 쓴다.
  <!-- 참고: MultiLock으로 바꿔도 동작은 같음. 원하면 NOTE로 -->

### 3.4 묶음 취소
- `ReservationWriter.cancelGroup(groupId)`: 묶음의 살아 있는 예약(CANCELED 제외)을 한 번에 조회한다. 각각 `cancel()`과 `visitRequest.unassign()`을 호출한다. 하나도 없으면 404 `RESERVATION_NOT_FOUND`.
- 단건 취소와 같은 기본 격리 수준, 락은 없다(단건과 같음). 같은 예약을 동시에 바꾸면 `Reservation.@Version`이 막는다.

## 4. 변경 파일

| 파일 경로 | 변경 내용 | 신규/수정 |
|---|---|---|
| `request/domain/VisitRequestGroup.java` | 묶음 엔티티 (수급자, 시작·종료 시간) | 신규 |
| `request/domain/VisitRequest.java` | `group` 필드(nullable FK) + 인덱스 + `createInGroup` 팩토리 | 수정 |
| `request/repository/VisitRequestGroupRepository.java` | 묶음 리포지토리 | 신규 |
| `request/repository/VisitRequestRepository.java` | `findAllWithRecipientByGroupId` | 수정 |
| `request/dto/VisitRequestGroupCreateRequest.java` | 등록 DTO | 신규 |
| `request/dto/VisitRequestGroupResponse.java` | 응답 DTO | 신규 |
| `request/service/VisitRequestGroupService.java` | 등록·조회 | 신규 |
| `request/controller/VisitRequestGroupController.java` | 등록·조회 API | 신규 |
| `recommendation/service/RecommendationService.java` | 요청 목록 기준으로 일반화, `recommendGroup` 추가 | 수정 |
| `recommendation/controller/RecommendationController.java` | 묶음 추천 엔드포인트 | 수정 |
| `recommendation/dto/RecommendationResponse.java` | 묶음 응답용 `ofGroup`(visitRequestId 대신 groupId) → 별도 record | 수정 |
| `caregiver/repository/CaregiverAvailabilityRepository.java` | `findByCaregiverIdInAndDayOfWeekIn` | 수정 |
| `reservation/repository/ReservationRepository.java` | `findByCaregiverIdInAndVisitDateInAndStatusNot`, `findActiveByGroupId` | 수정 |
| `reservation/service/ReservationWriter.java` | `confirmGroup`, `cancelGroup`, 공통 검증 private 메서드, `loadSchedule` 일반화 | 수정 |
| `reservation/service/ReservationService.java` | `confirmGroup`, `cancelGroup` | 수정 |
| `reservation/lock/ReservationLockStrategy.java` | `confirmGroup` 추가 | 수정 |
| `reservation/lock/{Unique,Pessimistic,Optimistic,Redisson}*.java` | `confirmGroup` 구현 | 수정 |
| `common/config/RedissonConfig.java` | 생성자 인자가 바뀌면 맞춤 (아래 스니펫은 기존 인자 그대로라 변경 없을 예정) | 확인 |
| `reservation/controller/ReservationController.java` | 묶음 확정·취소 엔드포인트 | 수정 |
| `reservation/dto/GroupReservationResponse.java` | `{groupId, caregiverId, reservations[]}` | 신규 |
| `common/exception/ErrorCode.java` | `VISIT_REQUEST_GROUP_NOT_FOUND`(404), `DUPLICATE_VISIT_DATE`(400) | 수정 |
| 테스트: `request/VisitRequestGroupApiTest.java` | 등록·조회·검증 | 신규 |
| 테스트: `recommendation/GroupRecommendationApiTest.java` | 묶음 추천 | 신규 |
| 테스트: `recommendation/RecommendationQueryCountTest.java` | 묶음 추천 쿼리 수 케이스 추가 | 수정 |
| 테스트: `reservation/GroupReservationApiTest.java` | 일괄 확정·롤백·취소 | 신규 |
| 테스트: `reservation/GroupReservationConcurrencyTest.java` | 묶음 동시성 (`@Tag("concurrency")`) | 신규 |
| 테스트: `reservation/ConcurrencyTestSupport.java`, `recommendation/RecommendationLatencyTest.java` | `cleanUp`에 그룹 삭제 추가 (FK 순서) | 수정 |
| `docs/oncare-review.md` | 4절 "아직 가져오지 않은 것"에서 묶음 신청을 3절 개선 표로 옮김 | 수정 |

(경로는 `src/main/java/com/hyeongjin/visit/` 또는 `src/test/java/com/hyeongjin/visit/` 기준)

## 5. 상세 변경

### 5.1 `VisitRequestGroup` (신규)
```java
// 여러 날짜를 같은 시간대로 한 번에 신청한 묶음. 날짜별 요청(VisitRequest)은 이 묶음을 가리킴
@Entity
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VisitRequestGroup extends BaseTimeEntity {

    public static final int MAX_DATES = 14;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "recipient_id")
    private Recipient recipient;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    private VisitRequestGroup(Recipient recipient, LocalTime startTime, LocalTime endTime) { ... }

    public static VisitRequestGroup create(Recipient recipient, LocalTime startTime, LocalTime endTime) {
        TimeSlots.validateRange(startTime, endTime);
        return new VisitRequestGroup(recipient, startTime, endTime);
    }
}
```
- 묶음 상태 컬럼은 두지 않는다. 상태는 하위 요청들의 상태로 판단한다(중복 상태로 어긋나는 것 방지).

### 5.2 `VisitRequest` 수정
```java
@Table(indexes = @Index(name = "idx_visit_request_group", columnList = "group_id"))
public class VisitRequest extends BaseTimeEntity {
    ...
    // 묶음 신청의 하위 요청이면 묶음, 단건 신청이면 null
    // nullable 인 이유: 기존 로컬 DB 의 행이 있고 ddl-auto: update 는 NOT NULL 을 채워 주지 않음
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "group_id")
    private VisitRequestGroup group;

    public static VisitRequest createInGroup(VisitRequestGroup group, LocalDate visitDate) {
        VisitRequest request = create(group.getRecipient(), visitDate, group.getStartTime(), group.getEndTime());
        request.group = group;
        return request;
    }
}
```

### 5.3 등록 DTO·서비스
```java
public record VisitRequestGroupCreateRequest(
        @NotNull Long recipientId,
        @NotEmpty @Size(max = VisitRequestGroup.MAX_DATES)
        List<@NotNull @FutureOrPresent LocalDate> visitDates,   // 원소 검증은 타입 인자에 붙임
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime
) {
}
```
```java
@Transactional
public VisitRequestGroupResponse register(VisitRequestGroupCreateRequest request) {
    Recipient recipient = recipientRepository.findById(request.recipientId())
            .orElseThrow(() -> new BusinessException(ErrorCode.RECIPIENT_NOT_FOUND));
    // 같은 날짜가 두 번 오면 같은 보호사에게 스스로 겹치는 예약을 만들게 되므로 등록 단계에서 막음
    if (new HashSet<>(request.visitDates()).size() != request.visitDates().size()) {
        throw new BusinessException(ErrorCode.DUPLICATE_VISIT_DATE);
    }
    VisitRequestGroup group = groupRepository.save(
            VisitRequestGroup.create(recipient, request.startTime(), request.endTime()));
    List<VisitRequest> requests = request.visitDates().stream()
            .sorted()
            .map(date -> VisitRequest.createInGroup(group, date))
            .toList();
    return VisitRequestGroupResponse.of(group, visitRequestRepository.saveAll(requests));
}
```
- 쿼리: 수급자 1 + 묶음 INSERT 1 + 요청 INSERT K (IDENTITY라 배치 안 됨, K ≤ 14)

### 5.4 리포지토리 추가
```java
// VisitRequestRepository
@Query("select v from VisitRequest v join fetch v.recipient where v.group.id = :groupId order by v.visitDate")
List<VisitRequest> findAllWithRecipientByGroupId(@Param("groupId") Long groupId);

// CaregiverAvailabilityRepository — 묶음의 요일 전체를 한 번에 (idx_availability_caregiver_day 사용)
List<CaregiverAvailability> findByCaregiverIdInAndDayOfWeekIn(Collection<Long> caregiverIds,
                                                              Collection<DayOfWeek> daysOfWeek);

// ReservationRepository — 묶음의 날짜 전체를 한 번에 (idx_reservation_caregiver_date 사용)
List<Reservation> findByCaregiverIdInAndVisitDateInAndStatusNot(
        Collection<Long> caregiverIds, Collection<LocalDate> visitDates, ReservationStatus excludedStatus);

// 묶음 취소용: 묶음의 살아 있는 예약 (요청을 함께 읽어 unassign 때 지연 로딩 방지)
@Query("""
        select r from Reservation r join fetch r.visitRequest v
        where v.group.id = :groupId and r.status <> :canceled
        """)
List<Reservation> findActiveByGroupId(@Param("groupId") Long groupId,
                                      @Param("canceled") ReservationStatus canceled);
```
- 기존 단수형 메서드(`findByCaregiverIdInAndDayOfWeek`, `findByCaregiverIdInAndVisitDateAndStatusNot`)는 `OncareStyleCandidateLoader` 등 테스트 코드가 쓰면 남긴다. 구현 때 쓰는 곳을 확인하고, 아무도 안 쓰면 지운다.

### 5.5 `RecommendationService` 일반화
```java
public RecommendationResponse recommend(Long visitRequestId, int size) {
    validateSize(size);
    VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
    requireAssignable(List.of(request));
    return RecommendationResponse.of(request.getId(), rank(List.of(request), request.getRecipient(), size));
}

// 묶음: 모든 날짜에 갈 수 있는 보호사만. 쿼리는 날짜 수와 상관없이 6번
public GroupRecommendationResponse recommendGroup(Long groupId, int size) {
    validateSize(size);
    List<VisitRequest> requests = visitRequestRepository.findAllWithRecipientByGroupId(groupId);
    if (requests.isEmpty()) {
        throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
    }
    requireAssignable(requests);
    return GroupRecommendationResponse.of(groupId, rank(requests, requests.get(0).getRecipient(), size));
}

private List<ScoredCandidate> rank(List<VisitRequest> requests, Recipient recipient, int size) {
    List<Caregiver> passed = filterCandidates(requests, recipient);
    return ranker.rank(toCandidates(recipient, passed), size);
}

private List<Caregiver> filterCandidates(List<VisitRequest> requests, Recipient recipient) {
    List<Caregiver> caregivers = findActiveCaregivers(recipient);
    if (caregivers.isEmpty()) {
        return List.of();
    }
    List<Long> ids = caregivers.stream().map(Caregiver::getId).toList();
    Set<DayOfWeek> days = requests.stream().map(r -> r.getVisitDate().getDayOfWeek()).collect(toSet());
    Set<LocalDate> dates = requests.stream().map(VisitRequest::getVisitDate).collect(toSet());

    // 요일·날짜가 몇 개든 한 번씩만 조회
    Map<Long, List<CaregiverAvailability>> availabilities = availabilityRepository
            .findByCaregiverIdInAndDayOfWeekIn(ids, days).stream()
            .collect(groupingBy(a -> a.getCaregiver().getId()));
    Map<Long, List<Reservation>> reservations = reservationRepository
            .findByCaregiverIdInAndVisitDateInAndStatusNot(ids, dates, ReservationStatus.CANCELED).stream()
            .collect(groupingBy(r -> r.getCaregiver().getId()));

    return caregivers.stream()
            .filter(caregiver -> {
                CaregiverSchedule schedule = new CaregiverSchedule(caregiver,
                        availabilities.getOrDefault(caregiver.getId(), List.of()),
                        reservations.getOrDefault(caregiver.getId(), List.of()));
                // 필터들은 요청의 날짜·요일로 먼저 거르므로, 여러 날짜 데이터가 섞인 schedule 을 그대로 넘겨도 됨
                return requests.stream().allMatch(request ->
                        filters.stream().allMatch(filter -> filter.accept(schedule, request)));
            })
            .toList();
}
```
- `toCandidates`, `findActiveCaregivers`, `RecommendationRanker`는 변경 없음
- 응답: 기존 `RecommendationResponse(visitRequestId, candidates)`는 그대로 둔다. 묶음용으로 `GroupRecommendationResponse(groupId, List<LocalDate> visitDates, candidates)`를 새로 만들고 `CandidateResponse`를 재사용한다(지금 `static from`이 package-private이라 `public`으로 바꿈)

### 5.6 `ReservationWriter`
```java
@Transactional(isolation = Isolation.READ_COMMITTED)
public Reservation confirm(Long visitRequestId, Long caregiverId, LockModeType caregiverLock) {
    VisitRequest request = visitRequestRepository.findWithRecipientById(visitRequestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND));
    return confirmAll(List.of(request), caregiverId, caregiverLock).get(0);
}

// 묶음 일괄 확정: 한 트랜잭션 → 한 날짜라도 실패하면 전부 롤백
// READ COMMITTED 를 단건과 똑같이 명시 (빠뜨리면 MySQL 에서 c5d2466 버그가 묶음 경로로 다시 생김)
@Transactional(isolation = Isolation.READ_COMMITTED)
public List<Reservation> confirmGroup(Long groupId, Long caregiverId, LockModeType caregiverLock) {
    List<VisitRequest> requests = visitRequestRepository.findAllWithRecipientByGroupId(groupId);
    if (requests.isEmpty()) {
        throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
    }
    return confirmAll(requests, caregiverId, caregiverLock);
}

// 단건·묶음 공통. @Transactional 을 붙이지 않음 (같은 클래스 호출이라 붙여도 적용 안 됨, 호출하는 쪽 트랜잭션을 씀)
private List<Reservation> confirmAll(List<VisitRequest> requests, Long caregiverId, LockModeType caregiverLock) {
    if (!requests.stream().allMatch(VisitRequest::isAssignable)) {
        throw new BusinessException(ErrorCode.VISIT_REQUEST_NOT_ASSIGNABLE);
    }
    // 날짜가 몇 개든 보호사 락은 한 번 (비관적: FOR UPDATE 1번, 낙관적: version + 1 한 번)
    Caregiver caregiver = em.find(Caregiver.class, caregiverId, caregiverLock);
    if (caregiver == null) {
        throw new BusinessException(ErrorCode.CAREGIVER_NOT_FOUND);
    }
    Recipient recipient = requests.get(0).getRecipient();
    if (!caregiver.isActive() || !recipient.accepts(caregiver.getGender())) {
        throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
    }
    CaregiverSchedule schedule = loadSchedule(caregiver, requests);
    // 고른 보호사로 "모든" 날짜를 확정 직전에 다시 검증 (온케어는 고른 날짜를 다시 보지 않았음)
    for (VisitRequest request : requests) {
        if (!workingHoursFilter.accept(schedule, request)) {
            throw new BusinessException(ErrorCode.CAREGIVER_NOT_AVAILABLE);
        }
        if (!scheduleConflictFilter.accept(schedule, request)) {
            throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
        }
    }
    List<Reservation> reservations = requests.stream()
            .map(request -> Reservation.confirm(caregiver, request))
            .toList();
    requests.forEach(VisitRequest::assign);
    try {
        return reservationRepository.saveAllAndFlush(reservations);
    } catch (DataIntegrityViolationException e) {
        throw new BusinessException(ErrorCode.RESERVATION_CONFLICT);
    }
}

// 요일·날짜 목록으로 한 번씩 조회 (단건이면 목록 크기 1)
private CaregiverSchedule loadSchedule(Caregiver caregiver, List<VisitRequest> requests) {
    Set<DayOfWeek> days = ...;
    Set<LocalDate> dates = ...;
    List<CaregiverAvailability> availabilities =
            availabilityRepository.findByCaregiverIdInAndDayOfWeekIn(List.of(caregiver.getId()), days);
    List<Reservation> existing = reservationRepository.findByCaregiverIdInAndVisitDateInAndStatusNot(
            List.of(caregiver.getId()), dates, ReservationStatus.CANCELED);
    return new CaregiverSchedule(caregiver, availabilities, existing);
}

@Transactional
public List<Reservation> cancelGroup(Long groupId) {
    List<Reservation> active = reservationRepository.findActiveByGroupId(groupId, ReservationStatus.CANCELED);
    if (active.isEmpty()) {
        throw new BusinessException(ErrorCode.RESERVATION_NOT_FOUND);
    }
    active.forEach(reservation -> {
        reservation.cancel();
        reservation.getVisitRequest().unassign();
    });
    return active;
}
```
- 단건 `confirm`의 순서가 바뀐다. 지금은 "요청 조회 → 상태 검사 → 보호사 락"이고, 바뀐 뒤에도 같다(`confirmAll`의 첫 줄이 상태 검사). 예외 종류와 순서도 같다 → `ReservationApiTest` 8개가 그대로 통과해야 한다.
- 단건 쿼리 수는 그대로다(IN에 값 1개).

### 5.7 락 전략
```java
public interface ReservationLockStrategy {
    String name();
    Reservation confirm(Long visitRequestId, Long caregiverId);
    List<Reservation> confirmGroup(Long groupId, Long caregiverId);
}
```
```java
// PessimisticLockStrategy
@Override
public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
    return writer.confirmGroup(groupId, caregiverId, LockModeType.PESSIMISTIC_WRITE);
}

// OptimisticLockStrategy: 단건과 같은 재시도 루프를 공통 메서드로
@Override
public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
    return retry(() -> writer.confirmGroup(groupId, caregiverId, LockModeType.OPTIMISTIC_FORCE_INCREMENT));
}

private <T> T retry(Supplier<T> action) {
    for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
        try {
            return action.get();
        } catch (ConcurrencyFailureException e) {
            log.debug("낙관적 락 충돌, 재시도 {}/{}", attempt, MAX_ATTEMPTS);
        }
    }
    throw new BusinessException(ErrorCode.CONCURRENT_MODIFICATION);
}
```
```java
// RedissonLockStrategy
@Override
public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
    // 락 키에 날짜가 필요해서 먼저 읽음 (트랜잭션 밖, 확정은 writer 안에서 다시 검증)
    List<LocalDate> dates = visitRequestRepository.findAllWithRecipientByGroupId(groupId).stream()
            .map(VisitRequest::getVisitDate)
            .sorted()          // 모든 요청이 같은 순서로 잡아야 서로 기다리는 데드락이 안 생김
            .distinct()
            .toList();
    if (dates.isEmpty()) {
        throw new BusinessException(ErrorCode.VISIT_REQUEST_GROUP_NOT_FOUND);
    }
    List<RLock> acquired = new ArrayList<>();
    try {
        for (LocalDate date : dates) {
            RLock lock = redissonClient.getLock(lockKey(caregiverId, date));
            if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(ErrorCode.RESERVATION_BUSY);
            }
            acquired.add(lock);
        }
        return writer.confirmGroup(groupId, caregiverId, LockModeType.NONE);
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new BusinessException(ErrorCode.RESERVATION_BUSY);
    } finally {
        // 커밋이 끝난 뒤에 해제 (단건과 같은 원칙), 잡은 역순으로
        Collections.reverse(acquired);
        acquired.forEach(RLock::unlock);
    }
}
```
- 단건 `confirm`의 키 생성도 같은 `lockKey`를 쓰게 맞춘다(문자열 형식은 그대로).

### 5.8 컨트롤러
```java
// VisitRequestGroupController
@PostMapping("/api/visit-request-groups")          → 201, Location: /api/visit-request-groups/{id}
@GetMapping("/api/visit-request-groups/{groupId}")

// RecommendationController
@GetMapping("/api/visit-request-groups/{groupId}/recommendations")

// ReservationController
@PostMapping("/api/visit-request-groups/{groupId}/reservations")   → 201 + GroupReservationResponse
@DeleteMapping("/api/visit-request-groups/{groupId}/reservations") → 200 + GroupReservationResponse
```

### 5.9 `ErrorCode`
```java
DUPLICATE_VISIT_DATE(HttpStatus.BAD_REQUEST, "같은 날짜를 두 번 신청할 수 없습니다."),
VISIT_REQUEST_GROUP_NOT_FOUND(HttpStatus.NOT_FOUND, "묶음 신청을 찾을 수 없습니다."),
```

## 6. 바꾸면 안 되는 것
- 단건 API의 경로·요청·응답 형식과 에러 코드: `POST /api/visit-requests`, `GET .../recommendations`, `POST .../reservations`, `DELETE /api/reservations/{id}`
- `ReservationWriter.confirm`의 `READ_COMMITTED`, 그리고 "요청 조회 → 상태 검사 → 보호사 락 → 재검증 → 저장" 순서
- `ReservationService`에 `@Transactional`을 붙이지 않는 원칙(낙관적 재시도·Redis 락은 트랜잭션 밖)
- 단건 추천 쿼리 수 6번 (`RecommendationQueryCountTest`)
- 기존 유니크 제약 `uk_reservation_active_slot`과 인덱스
- `CandidateFilter`, `ScorePolicy` 구현체와 `RecommendationRanker` (변경 없음)
- `ReservationLockStrategy.confirm`의 시그니처

## 7. 영향과 검증

- **쿼리 수**
  - 묶음 추천: **6번**(묶음 요청 1 + 보호사 1 + 근무 시간 1 + 예약 1 + 집계 2). 날짜 1·7·14일에서 같은지 `RecommendationQueryCountTest`에 파라미터 테스트로 추가
  - 단건 추천: 6번 유지 (기존 테스트)
  - 묶음 확정(비관적): 요청 1 + 보호사 락 1 + 근무 1 + 예약 1 + INSERT K + 요청 UPDATE K
- **동시성/락**
  - 비관적: 보호사 행 락 1개로 묶음 전체 보호. 락을 쥔 시간은 K에 비례(K ≤ 14)
  - 낙관적: 충돌하면 묶음 전체 재시도(최대 5번)
  - redisson: 날짜 정렬 순서로 획득 → 순환 대기 없음
  - 같은 묶음을 두 관리자가 동시에 다른 보호사로 확정: `VisitRequest.@Version`으로 한쪽만 성공(409 `CONCURRENT_MODIFICATION`)
  - 검증 테스트 `GroupReservationConcurrencyTest` (`@Tag("concurrency")` → **`./gradlew mysqlTest` 대상**)
    1. 같은 보호사, 날짜가 겹치는 묶음 20개를 동시에 확정 → 성공 1, 겹친 쌍 0 (pessimistic, optimistic)
    2. 묶음 1개 + 그 묶음 날짜 중 하나와 겹치는 단건 50개 동시 → 겹친 쌍 0, 묶음이 성공하면 단건 0, 묶음이 실패하면 묶음 쪽 예약 0(부분 저장 없음)
    3. 같은 묶음을 서로 다른 보호사 10명으로 동시 확정 → 성공 1
    4. (Redis 있을 때) `RedissonConcurrencyTest`에 시나리오 1 추가
- **스키마** (`ddl-auto: update`는 제약을 지우지 않고 NOT NULL을 채워 주지 않음)
  - 새 테이블 `visit_request_group`, `visit_request.group_id`(**nullable**) + FK + 인덱스 `idx_visit_request_group` → update로 추가돼서 기존 로컬 DB에서도 뜸
  - 테스트는 `create-drop`이라 영향 없음
  - 테스트 정리 순서: `ConcurrencyTestSupport.cleanUp`, `RecommendationLatencyTest`에서 visit_request 다음에 visit_request_group 삭제
- **검증 명령**
  - 단계마다 `./gradlew compileJava compileTestJava`
  - 마지막 `./gradlew test` (H2, 동시성 포함)
  - `./gradlew mysqlTest`는 `DB_PASSWORD`가 필요해서 로컬에서는 사람이 실행, CI에서 자동

## 8. TODO
- [x] Phase 1 — 스키마·등록: `VisitRequestGroup`, `VisitRequest.group`, 리포지토리, DTO, `VisitRequestGroupService`·컨트롤러, `ErrorCode` 2개, 테스트 정리 순서 수정 → `VisitRequestGroupApiTest`
- [x] Phase 2 — 조회 일반화: `...DayOfWeekIn`, `...VisitDateInAndStatusNot` 추가, `RecommendationService`·`ReservationWriter.loadSchedule`을 목록 기준으로 → **기존 테스트 전부 통과 확인**(단건 동작 불변)
- [x] Phase 3 — 묶음 추천: `recommendGroup`, `GroupRecommendationResponse`, 엔드포인트 → `GroupRecommendationApiTest`, 쿼리 수 테스트(1·7·14일)
- [x] Phase 4 — 묶음 확정·취소: `confirmAll` 추출, `confirmGroup`, `cancelGroup`, 락 전략 4개, `ReservationService`, 엔드포인트 → `GroupReservationApiTest` (전부 성공 / 한 날짜 충돌 시 전체 롤백·상태 REQUESTED 유지 / 근무 시간 밖 날짜 하나 → 409 / 묶음 취소 후 재확정)
- [x] Phase 5 — 동시성: `GroupReservationConcurrencyTest` 시나리오 1~3
- [x] Phase 6 — 문서: `docs/oncare-review.md` 4절 → 개선 표로 이동, README "한계와 남은 일"에서 묶음 신청 줄 제거, API 목록에 추가
- [x] `./gradlew test` 통과
- [x] `mysqlTest` 대상 표시 확인 (`GroupReservationConcurrencyTest`에 `@Tag("concurrency")`)

### 구현 메모 (계획과 다르게 하거나 덧붙인 것)
- `VisitRequestGroupRepository.findWithRecipientById` 추가: 묶음 조회 응답의 수급자 id 를 지연 로딩 없이 쓰려고.
- 단수형 조회 메서드(`findByCaregiverIdInAndDayOfWeek`, `findByCaregiverIdInAndVisitDateAndStatusNot`)는 쓰는 곳이 없어져서 지웠다(5.4절 방침대로).
- 테스트 정리 순서는 `SampleDataSeederTest`에도 같이 반영했다(4절 표에 없던 파일).
- `ConcurrencyTestSupport.runConcurrently`의 인자를 `Function<Long, ?>`로 넓혔다(묶음은 `List<Reservation>`을 돌려주므로). 기존 호출부는 그대로.
- 결과: `./gradlew test` 83개 중 80개 통과, 3개는 로컬에 Redis 가 없어 건너뜀(`RedissonConcurrencyTest`, 묶음 시나리오 포함). Redisson 묶음 시나리오와 MySQL 동작은 CI(`mysqlTest`)에서 확인 필요.
