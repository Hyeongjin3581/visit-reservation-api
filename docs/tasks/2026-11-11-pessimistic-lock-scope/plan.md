# Plan: 비관적 락 범위 축소 (보호사 → 보호사·날짜)

> 메모는 해당 위치에 `<!-- NOTE: ... -->`로 달고 `/apply-notes`로 반영한다.
> 근거 조사: [research.md](research.md)

## 0. 결정 사항 (research.md 9절 답)

| # | 질문 | 선택 | 이유 |
|---|---|---|---|
| 1 | 잠금 행 만드는 방식 | **(a) 트랜잭션 밖에서 미리 만들고, 트랜잭션 안에서는 `FOR UPDATE`만** | H2와 MySQL의 락 의미가 같다(유니크 인덱스 한 행에 레코드 락). 네이티브 SQL이 없다. Redisson이 "트랜잭션 밖에서 날짜를 읽고 준비"하는 구조와 같다. (b)는 쿼리 1~2개를 아끼지만 H2/MySQL 의미 차이를 따로 검증해야 하고, `c5d2466`처럼 CI에서만 터지는 문제가 될 위험이 있다 |
| 2 | 낙관적 락(C)도 날짜 단위로 | **이번에는 안 함** (후속 후보로 기록) | 범위를 좁게 유지한다(CLAUDE.md 5번). C를 같이 바꾸면 "B만 바꿨을 때 효과"를 따로 볼 수 없다. 잠금 행이 생기면 나중에 `@Version`만 붙여 같은 방식으로 옮길 수 있다 |
| 3 | 기존 보호사 단위 락을 남길지 | **남김**: 전략 이름 `pessimistic-caregiver` | 같은 코드에서 전후를 비교할 수 있다. 래치 테스트가 "예전 방식은 기다리고 새 방식은 안 기다림"을 매번 CI에서 증명하고, 다음 작업인 k6도 설정만 바꿔 전후를 잴 수 있다. 비용은 전략 클래스 1개 |
| 4 | 묶음 락 획득 | **날짜마다 한 문장씩, 날짜 오름차순** | 획득 순서를 코드가 직접 정한다. IN + `ORDER BY` 한 문장은 실제 잠금 순서가 옵티마이저의 인덱스 스캔 순서에 달려 있다. 추가 비용은 묶음일 때만 최대 14문장이고, 단건은 어느 쪽이든 1문장 |
| 5 | 지난 날짜 잠금 행 정리 | **안 함** | 연 36만 행 수준이라 작다. 정리 작업 자체가 락과 경합할 수 있어 따로 설계하는 게 맞다 |
| 6 | 개선 증명 | **래치 테스트로 증명** (`@Tag("concurrency")`, mysqlTest 대상). k6 수치는 다음 작업("k6 실행·수치 기록")에서 `pessimistic` vs `pessimistic-caregiver`로 잰다 | 래치 테스트는 시간 측정 없이 "기다림/안 기다림"을 결정적으로 보여 준다. k6는 원래 다음 순서 작업이고, 3번 덕분에 전후 비교가 설정 하나로 된다 |

## 1. 목표
- 방법 B(`pessimistic`)의 락 단위를 **보호사 행 → (보호사, 날짜) 잠금 행**으로 줄인다. 같은 보호사라도 다른 날짜의 확정은 서로 기다리지 않는다.
- 같은 날짜끼리의 직렬화, 겹침 0, 묶음의 전부 아니면 전무는 그대로 유지한다.

**완료 조건**
- 래치 테스트: `pessimistic`은 D1 락을 쥔 동안 D2 확정이 바로 끝나고, D1 확정은 기다린다. `pessimistic-caregiver`는 D2도 기다린다.
- 기존 동시성 테스트(단건·묶음)가 `pessimistic`과 `pessimistic-caregiver` 모두에서 성공 1, 겹침 0이다.
- `./gradlew test` 통과. 새 테스트는 `@Tag("concurrency")`라 CI `mysqlTest`에서 MySQL로도 돈다.

## 2. 범위
**포함**
- 잠금 행 엔티티 `CaregiverDayLock`과 리포지토리
- 트랜잭션 밖에서 잠금 행을 준비하는 컴포넌트 `CaregiverDayLocks`
- `ReservationWriter`가 락 대상을 고를 수 있도록 `LockModeType` 인자를 `ConfirmLock` enum으로 교체
- `pessimistic`을 날짜 락으로 전환하고, 예전 방식을 `pessimistic-caregiver`로 보존
- 래치 테스트와 기존 동시성 테스트 파라미터 추가
- 문서: README, interview-notes, 전략 주석

**제외 (이번에 안 함)**
- 낙관적 락(C)을 날짜 단위로 바꾸기 (후속 후보)
- 지난 날짜 잠금 행 정리
- k6 실행과 README 처리량 수치 (다음 작업)
- Redisson(D) 변경 (이미 날짜 단위)

## 3. 접근 방식

### 3.1 흐름 (단건, `pessimistic`)
```
PessimisticLockStrategy.confirm                 ← 트랜잭션 밖
 ├ 요청의 방문 날짜 조회 (VISIT_REQUEST_NOT_FOUND 는 여기서)
 ├ dayLocks.prepare(caregiverId, [날짜])        ← 없는 잠금 행만 INSERT, 각자 짧은 자동 트랜잭션
 └ writer.confirm(.., ConfirmLock.CAREGIVER_DAY)  ← READ_COMMITTED 트랜잭션
    ├ 요청 조회 · 상태 검사
    ├ 보호사 일반 조회 (없으면 404)
    ├ 날짜 오름차순으로 잠금 행 SELECT ... FOR UPDATE (행이 없으면 IllegalStateException)
    └ (이하 기존과 같음) 상태·성별 → 근무 시간 → 겹침 → 저장
```

### 3.2 research에서 짚은 함정 3개와 개선 방안

**① REQUIRES_NEW 커넥션 고갈 → 중첩 트랜잭션을 아예 만들지 않음**
- 잠금 행 준비(`prepare`)는 **전략에서, writer 트랜잭션이 시작되기 전에** 부른다. INSERT 하나하나는 `saveAndFlush`의 자체 트랜잭션(커넥션 1개, 바로 반납)으로 끝난다. 그래서 어느 시점에도 요청 하나가 커넥션을 2개 쥐지 않는다.
- 실수로 트랜잭션 안에서 부르는 것을 막는다: `prepare` 첫 줄에서 `TransactionSynchronizationManager.isActualTransactionActive()`가 true면 `IllegalStateException`을 던진다. 함정을 주석이 아니라 코드로 막는 장치다.
- 검증: 기존 동시성 테스트가 이미 "스레드 32 > H2 풀 10" 조건이다(`ConcurrencyTestSupport.java:42`). 중첩이 생기면 이 테스트가 멈추거나 60초 대기(`:158`)에 걸려 바로 드러난다.

**② 묶음 데드락 → 정렬·중복 제거 후 한 문장씩**
- writer 안에서 날짜를 `sorted().distinct()`한 뒤 날짜마다 `findForUpdate`를 차례로 호출한다. 모든 트랜잭션이 같은 순서로 잡으므로 순환 대기가 생기지 않는다(Redisson과 같은 원칙).
- 행이 없으면(준비 단계를 건너뛴 경우) 조용히 락 없이 진행하지 않고 `IllegalStateException`을 던진다. research 위험 1번("행이 없으면 락이 안 걸림")을 코드로 막는다.
- 검증
  - 기존 `GroupReservationConcurrencyTest`의 `overlappingGroups`는 날짜 조합(D1·D2 / D3·D2 / D3·D1)과 등록 순서가 섞여 있다. 정렬이 빠지면 데드락이나 락 대기 초과(H2 10초)가 나서 성공 1 단언이 깨진다.
  - 이 테스트를 `pessimistic`에서 그대로 돌린다.

**③ "다른 날짜는 안 기다림"을 증명하는 테스트 → 래치 테스트 2개 (시간 측정 없음)**
- 붙잡는 스레드가 `TransactionTemplate` 안에서 **운영 코드와 같은 방법으로** 락을 잡고, 래치가 풀릴 때까지 커밋하지 않는다.
- 다른 스레드가 확정을 시도하고 `Future.get(timeout)`으로 판정한다.
  - 끝남 = 기다리지 않음. `TimeoutException` = 기다림.
- 붙잡은 쪽을 풀면 기다리던 확정도 성공해야 한다. 락 해제 후 정상 진행까지 확인한다.

| 테스트 | 붙잡는 락 | 같은 보호사 D2 확정 | 같은 보호사 D1 확정 |
|---|---|---|---|
| `pessimistic` | D1 잠금 행 `FOR UPDATE` | 3초 안에 끝남 | 1초 안에 안 끝남 → 풀면 성공 |
| `pessimistic-caregiver` | 보호사 행 `FOR UPDATE` | 1초 안에 안 끝남 → 풀면 성공 | (생략, 기존 동작) |

- 두 줄이 대비되는 것 자체가 개선 근거이고, CI에서 매번 다시 확인된다.

### 3.3 버린 대안
- **(b) `INSERT ... ON DUPLICATE KEY UPDATE`**: 0절 1번 참고.
- **IN 한 문장으로 묶음 잠금**: 0절 4번 참고.
- **writer 안 `REQUIRES_NEW`로 행 만들기**: 커넥션 고갈(① 참고).
- **잠금 행 없이 예약 행 `FOR UPDATE`**: READ_COMMITTED라 첫 예약 경쟁을 못 막는다(research 6.1-②).
- **`pessimistic`을 그대로 두고 새 이름(`pessimistic-day`)으로 추가**: 기본값을 바꾸려면 설정 기본값·README·k6 안내를 모두 바꿔야 한다. 이름은 유지하고 내부만 개선하는 편이 사용하는 쪽 변경이 적다. 예전 동작은 `pessimistic-caregiver`로 남는다.

## 4. 변경 파일
| 파일 경로 | 변경 내용 | 신규/수정 |
|---|---|---|
| `reservation/domain/CaregiverDayLock.java` | 잠금 전용 엔티티 (보호사 FK, 날짜, 유니크) | 신규 |
| `reservation/repository/CaregiverDayLockRepository.java` | 이미 있는 날짜 조회, `findForUpdate` | 신규 |
| `reservation/lock/CaregiverDayLocks.java` | `prepare(caregiverId, dates)`: 트랜잭션 밖에서 없는 행만 만듦 | 신규 |
| `reservation/service/ConfirmLock.java` | `NONE / CAREGIVER_ROW / CAREGIVER_VERSION / CAREGIVER_DAY` | 신규 |
| `reservation/service/ReservationWriter.java` | `LockModeType` 인자 → `ConfirmLock`, `lock(...)` private 메서드 | 수정 |
| `reservation/lock/PessimisticLockStrategy.java` | 날짜 조회 + `prepare` + `CAREGIVER_DAY` | 수정 |
| `reservation/lock/CaregiverRowLockStrategy.java` | 이름 `pessimistic-caregiver`, 예전 B 동작 (`CAREGIVER_ROW`) | 신규 |
| `reservation/lock/{Unique,Optimistic,Redisson}*.java` | 인자를 `ConfirmLock.NONE` / `CAREGIVER_VERSION`으로 (동작 같음) | 수정 |
| `reservation/lock/ReservationLockStrategy.java` | 전략 목록 주석에 `pessimistic-caregiver` 추가 | 수정 |
| `src/main/resources/application.yml` | 전략 목록 주석 | 수정 |
| 테스트 `reservation/LockScopeTest.java` | 래치 테스트 2개 (`@Tag("concurrency")`) | 신규 |
| 테스트 `reservation/ReservationConcurrencyTest.java`, `GroupReservationConcurrencyTest.java` | `@ValueSource`에 `pessimistic-caregiver` 추가 | 수정 |
| 테스트 `ConcurrencyTestSupport`, `RecommendationLatencyTest`, `SampleDataSeederTest` | 정리 순서에 잠금 행 삭제 추가 (`caregiver`보다 먼저) | 수정 |
| `README.md`, `docs/interview-notes.md` | B 설명·한계·대안 표 갱신 | 수정 |

(경로는 `src/main/java/com/hyeongjin/visit/` 또는 `src/test/java/com/hyeongjin/visit/` 기준)

## 5. 상세 변경

### 5.1 `CaregiverDayLock`
```java
// 방법 B 의 잠금 전용 행: (보호사, 날짜)마다 하나. 이 행을 SELECT ... FOR UPDATE 로 잠가서
// 같은 보호사·같은 날짜 확정만 줄 세움 (다른 날짜끼리는 서로 기다리지 않음)
// 예약이 0건인 날짜도 잠글 수 있어야 해서 예약 행 대신 따로 둠 (READ COMMITTED 에는 갭 락이 없음)
@Entity
@Table(uniqueConstraints = @UniqueConstraint(
        name = "uk_caregiver_day_lock", columnNames = {"caregiver_id", "visit_date"}))
public class CaregiverDayLock {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false) @JoinColumn(name = "caregiver_id") private Caregiver caregiver;
    @Column(nullable = false) private LocalDate visitDate;

    public static CaregiverDayLock of(Caregiver caregiver, LocalDate visitDate) { ... }
}
```
- `BaseTimeEntity`는 상속하지 않는다. 잠금용이라 생성·수정 시각이 필요 없다.

### 5.2 `CaregiverDayLockRepository`
```java
@Query("select l.visitDate from CaregiverDayLock l where l.caregiver.id = :caregiverId and l.visitDate in :dates")
List<LocalDate> findExistingDates(@Param("caregiverId") Long caregiverId, @Param("dates") Collection<LocalDate> dates);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select l from CaregiverDayLock l where l.caregiver.id = :caregiverId and l.visitDate = :date")
Optional<CaregiverDayLock> findForUpdate(@Param("caregiverId") Long caregiverId, @Param("date") LocalDate date);
```

### 5.3 `CaregiverDayLocks.prepare` (트랜잭션 밖)
```java
// 잠글 행이 없으면 만들어 둠. 반드시 확정 트랜잭션 "밖"에서 호출:
// 안에서 새 트랜잭션으로 만들면 요청 하나가 커넥션을 2개 쥐어, 스레드가 풀보다 많을 때 풀 고갈로 멈춤
public void prepare(Long caregiverId, Collection<LocalDate> dates) {
    if (TransactionSynchronizationManager.isActualTransactionActive()) {
        throw new IllegalStateException("잠금 행 준비는 트랜잭션 밖에서 호출해야 합니다");
    }
    Set<LocalDate> missing = new TreeSet<>(dates);
    missing.removeAll(dayLockRepository.findExistingDates(caregiverId, dates));   // 대부분 여기서 끝 (SELECT 1번)
    Caregiver ref = em.getReference(Caregiver.class, caregiverId);   // 실제 조회 없이 FK 값만
    for (LocalDate date : missing) {
        try {
            dayLockRepository.saveAndFlush(CaregiverDayLock.of(ref, date));   // 자체 트랜잭션, 바로 커밋
        } catch (DataIntegrityViolationException e) {
            // 다른 요청이 같은 행을 먼저 만들었거나(유니크) 보호사가 없음(FK)
            // → 앞의 경우는 목적 달성, 뒤의 경우는 writer 가 CAREGIVER_NOT_FOUND 로 응답
        }
    }
}
```
- `em.getReference`를 트랜잭션 밖에서 쓰면 공유 EntityManager가 요청마다 임시 컨텍스트를 연다. 구현 때 동작이 애매하면 `caregiverRepository.getReferenceById` 대신 FK 값만 담는 생성 방식으로 바꾼다(결과 동일).

### 5.4 `ConfirmLock`과 `ReservationWriter`
```java
// 확정 트랜잭션에서 무엇을 잠그는가 (전략마다 다름)
public enum ConfirmLock {
    NONE,               // A 유니크 제약만, D Redis 락(트랜잭션 밖에서 이미 잠금)
    CAREGIVER_ROW,      // B' 보호사 행 FOR UPDATE (pessimistic-caregiver, 예전 B)
    CAREGIVER_VERSION,  // C  보호사 version +1 (OPTIMISTIC_FORCE_INCREMENT)
    CAREGIVER_DAY       // B  (보호사, 날짜) 잠금 행 FOR UPDATE
}
```
```java
private List<Reservation> confirmAll(List<VisitRequest> requests, Long caregiverId, ConfirmLock lock) {
    ... // 상태 검사 그대로
    Caregiver caregiver = em.find(Caregiver.class, caregiverId, switch (lock) {
        case CAREGIVER_ROW -> LockModeType.PESSIMISTIC_WRITE;
        case CAREGIVER_VERSION -> LockModeType.OPTIMISTIC_FORCE_INCREMENT;
        case NONE, CAREGIVER_DAY -> LockModeType.NONE;
    });
    if (caregiver == null) { throw CAREGIVER_NOT_FOUND; }
    if (lock == ConfirmLock.CAREGIVER_DAY) {
        lockDays(caregiverId, requests);
    }
    ... // 이하 그대로 (상태·성별 → schedule → 날짜별 재검증 → 저장)
}

// 날짜 오름차순으로 한 행씩 잠금 → 묶음끼리 서로 반대 순서로 잡는 데드락이 생기지 않음
private void lockDays(Long caregiverId, List<VisitRequest> requests) {
    requests.stream().map(VisitRequest::getVisitDate).sorted().distinct().forEach(date ->
            dayLockRepository.findForUpdate(caregiverId, date).orElseThrow(() ->
                    // 잠글 행이 없으면 락 없이 진행하게 되므로 조용히 넘어가지 않음
                    new IllegalStateException("잠금 행이 없습니다. CaregiverDayLocks.prepare 를 먼저 호출해야 합니다")));
}
```
- `confirm`, `confirmGroup`의 public 시그니처는 마지막 인자 타입만 `LockModeType` → `ConfirmLock`으로 바뀐다. 호출자는 전략 4+1개뿐이다.
- `READ_COMMITTED`는 그대로 둔다. 요청 SELECT가 락보다 먼저인 구조는 같다.

### 5.5 전략
```java
// PessimisticLockStrategy (name "pessimistic", 기본값)
public Reservation confirm(Long visitRequestId, Long caregiverId) {
    LocalDate date = visitRequestRepository.findById(visitRequestId)
            .orElseThrow(() -> new BusinessException(ErrorCode.VISIT_REQUEST_NOT_FOUND)).getVisitDate();
    dayLocks.prepare(caregiverId, List.of(date));
    return writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_DAY);
}

public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
    List<LocalDate> dates = visitRequestRepository.findAllWithRecipientByGroupId(groupId).stream()
            .map(VisitRequest::getVisitDate).toList();
    if (dates.isEmpty()) { throw VISIT_REQUEST_GROUP_NOT_FOUND; }
    dayLocks.prepare(caregiverId, dates);
    return writer.confirmGroup(groupId, caregiverId, ConfirmLock.CAREGIVER_DAY);
}

// CaregiverRowLockStrategy (name "pessimistic-caregiver"): 예전 B 를 비교용으로 보존
writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_ROW);
```
- `Unique` → `NONE`, `Optimistic` → `CAREGIVER_VERSION`, `Redisson` → `NONE`. 동작은 바뀌지 않는다.

### 5.6 `LockScopeTest` (요약)
```java
@Tag("concurrency")
@SpringBootTest
class LockScopeTest extends ConcurrencyTestSupport {
    @Test void 날짜_락은_같은_보호사의_다른_날짜를_막지_않는다() {
        // D1·D2 요청 준비, prepare(D1)
        // holder: TransactionTemplate 안에서 dayLockRepository.findForUpdate(c, D1) 후 release 래치 대기
        // D2 확정(pessimistic) → future.get(3s) 성공
        // D1 확정(pessimistic) → future.get(1s) TimeoutException → release → future.get(10s) 성공
    }
    @Test void 보호사_행_락은_다른_날짜도_막는다() {
        // holder: em.find(Caregiver, c, PESSIMISTIC_WRITE) 후 대기
        // D2 확정(pessimistic-caregiver) → future.get(1s) TimeoutException → release → 성공
    }
}
```
- 대기 판정 1초, 끝남 판정 3초. H2 `LOCK_TIMEOUT`이 10초라 판정 전에 락 대기 초과 예외가 나지 않는다.
- holder는 `finally`에서 반드시 래치를 풀어, 실패해도 다른 테스트로 락이 새지 않게 한다.

## 6. 바꾸면 안 되는 것
- `ReservationLockStrategy`의 `confirm`, `confirmGroup` 시그니처와 전략 이름 `unique / pessimistic / optimistic / redisson`, 기본값 `pessimistic`
- `ReservationWriter`의 `READ_COMMITTED`, 그리고 "요청 조회 → 상태 검사 → 보호사 조회 → 락 → 재검증 → 저장" 순서와 에러 코드
- `ReservationService`에 `@Transactional`을 붙이지 않는 원칙
- 방법 C(`Caregiver.@Version`)와 D(Redis 키 형식)의 동작
- 유니크 제약 `uk_reservation_active_slot`, 추천 쿼리 수 6

## 7. 영향과 검증
- **쿼리 수** (단건, `pessimistic`)
  - 트랜잭션 밖: 요청 날짜 1 + 잠금 행 존재 확인 1 (+ 처음일 때만 INSERT 1)
  - 트랜잭션 안: 보호사 락 1 대신 보호사 조회 1 + 잠금 행 `FOR UPDATE` 1 → 1번 늘어남
  - 합계: 확정 1건당 +3 (처음 날짜면 +4)
  - 묶음: 밖 +2(+K), 안 +K(≤14)
  - 대가로 같은 보호사의 다른 날짜 확정이 병렬로 처리된다. 추천 경로는 영향 없음.
- **동시성/락**
  - 같은 (보호사, 날짜)만 직렬화된다. 획득 순서는 날짜 오름차순.
  - 락 대기 초과나 데드락이 나면 `ConcurrencyFailureException` 계열 → 409 `CONCURRENT_MODIFICATION`(기존 처리).
  - 확정 중 보호사 상태 변경과의 직렬화는 사라진다. 지금은 상태를 바꾸는 API가 없어서 실제로 문제가 되는 경로는 없다(research 6.2). 그런 API가 생기면 다시 봐야 한다고 주석에 남긴다.
- **스키마** (`ddl-auto: update`는 제약을 지우지 않음)
  - 새 테이블 `caregiver_day_lock` + 유니크 `uk_caregiver_day_lock` + FK. update로 생성되고, 기존 테이블은 바뀌지 않는다.
  - 테스트는 `create-drop`.
  - 정리 순서: 세 곳에서 `caregiver`보다 먼저 지운다.
- **검증**
  - 단계마다 `./gradlew compileJava compileTestJava`, 마지막에 `./gradlew test`.
  - `LockScopeTest`와 파라미터가 늘어난 동시성 테스트는 `@Tag("concurrency")` → **`./gradlew mysqlTest` 대상**. MySQL 고유 동작(레코드 락, FK S 락)은 CI에서 확인한다.

## 8. TODO
- [x] Phase 1 — 잠금 행: `CaregiverDayLock`, `CaregiverDayLockRepository`, `CaregiverDayLocks.prepare`(트랜잭션 중 호출 차단 포함), 테스트 정리 순서 3곳 → 컴파일, 기존 테스트 통과
- [x] Phase 2 — writer·전략: `ConfirmLock`, `ReservationWriter.lockDays`, 전략 4개 인자 교체, `pessimistic`을 날짜 락으로, `CaregiverRowLockStrategy` 추가 → **기존 테스트 전부 통과**(특히 동시성: 풀 10 / 스레드 32에서 멈추지 않는지)
- [x] Phase 3 — 테스트: `LockScopeTest` 2개, 기존 동시성 `@ValueSource`에 `pessimistic-caregiver` 추가
- [x] Phase 4 — 문서: `PessimisticLockStrategy` 주석, README(대안 표 B 설명, 예약 확정 방식, 한계 `:153`), interview-notes(`:48, :65, :87`), `application.yml` 전략 목록
- [x] `./gradlew test` 통과
- [x] `mysqlTest` 대상 표시 확인 (`LockScopeTest`에 `@Tag("concurrency")`)
- [x] 후속 후보로 기록: 낙관적 락(C) 날짜 단위화, 지난 날짜 잠금 행 정리, k6 전후 비교(`pessimistic` vs `pessimistic-caregiver`)

### 구현 메모 (계획과 다르게 하거나 덧붙인 것)
- **`prepare`의 트랜잭션 안전장치 (사용자 결정: 합류 허용)**
  - 계획에서는 트랜잭션 안에서 부르면 예외를 던지기로 했다. 그런데 `@Transactional` API 테스트(`ReservationApiTest`, `GroupReservationApiTest`) 14개가 실패했다.
  - 이미 트랜잭션이 있으면 거기에 합류하도록 바꿨다. 새 커넥션을 잡지 않으므로 풀 고갈 위험은 그대로 없다. 운영 경로에는 바깥 트랜잭션이 없다.
- **없는 보호사는 FK 위반 대신 `existsById`로 거름**
  - 트랜잭션에 합류한 상태에서 FK 위반이 나면, 실패한 엔티티가 세션에 남아 이후 flush가 `AssertionFailure`로 깨졌다.
  - `existsById` 조회는 새로 만들 행이 있을 때만 실행한다.
- **`CaregiverDayLock`은 `caregiverId`(Long)로 쓰고, FK는 읽기 전용 `@ManyToOne`으로 생성**
  - 트랜잭션 밖에서 보호사 프록시를 다루지 않으려고 이렇게 했다(5.3절 주의사항 해소).
- **결과**: `./gradlew test` 89개 중 86개 통과, 3개는 Redis 없어 건너뜀. 실패 0.
- **확인 못 한 것**: MySQL 동작(레코드 락, FK S 락)과 k6 처리량은 아직 확인하지 못했다. CI `mysqlTest`와 다음 작업에서 확인한다.
- **후속 후보**: 낙관적 락(C) 날짜 단위화, 지난 날짜 잠금 행 정리, k6 전후 비교(`pessimistic` vs `pessimistic-caregiver`)
