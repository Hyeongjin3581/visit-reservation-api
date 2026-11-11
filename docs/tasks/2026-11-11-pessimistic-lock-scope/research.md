# Research: 비관적 락 범위 축소 (보호사 → 보호사·날짜)

## 1. 범위
- 조사한 경로
  - `src/main/java/com/hyeongjin/visit/reservation/{service,lock,domain,repository}`
  - `src/main/java/com/hyeongjin/visit/caregiver/{domain,service,repository}`
  - `src/main/java/com/hyeongjin/visit/recommendation/{domain,filter}` (확정 재검증에 쓰는 필터)
  - `src/main/java/com/hyeongjin/visit/common/{config,exception,seed}`
  - `src/main/resources/application.yml`, `src/test/resources/application*.yml`, `build.gradle`(mysqlTest), `.github/workflows/ci.yml`
  - `src/test/java/com/hyeongjin/visit/reservation/*` (동시성·API 테스트), `k6/reservation.js`
  - 문서: `README.md`, `docs/interview-notes.md`
- 조사 목적
  - 방법 B(비관적 락)는 지금 **보호사 행 하나**를 `SELECT ... FOR UPDATE`로 잠근다. 그래서 같은 보호사의 **다른 날짜** 확정끼리도 줄을 선다(`README.md:153`, `PessimisticLockStrategy.java:13`).
  - 겹침은 같은 날짜 안에서만 생긴다(`ScheduleConflictFilter.java:17`). 그러니 락 단위를 **(보호사, 날짜)**로 줄여도 정합성이 유지되는지, 줄이려면 무엇이 필요한지 조사한다.
  - `docs/interview-notes.md:87`에 이미 "보호사·날짜 단위의 잠금용 행을 따로 두는 방법"이 개선 후보로 적혀 있다.

## 2. 전체 흐름

### 2.1 단건 확정 (기본 전략 pessimistic)
```
ReservationController.confirm                     reservation/controller/ReservationController.java
 └ ReservationService.confirm                     reservation/service/ReservationService.java  (트랜잭션 없음, 의도)
    └ PessimisticLockStrategy.confirm             reservation/lock/PessimisticLockStrategy.java:26-28
       └ ReservationWriter.confirm  @Transactional(READ_COMMITTED)   ReservationWriter.java:50-55
          ├ ① 방문 요청 + 수급자 조회 (일반 SELECT)                    :52
          └ confirmAll (트랜잭션 애너테이션 없음, 같은 트랜잭션)         :71-110
             ├ ② 요청 상태 검사 (REQUESTED 인지)                        :72-74
             ├ ③ em.find(Caregiver, id, PESSIMISTIC_WRITE)               :78   ← 지금 락이 걸리는 유일한 지점
             │     = SELECT ... FROM caregiver WHERE id=? FOR UPDATE
             ├ ④ 보호사 ACTIVE·성별 재검증                               :85-88
             ├ ⑤ loadSchedule: 근무 가능 시간·해당 날짜 예약 조회         :89, :140-148
             ├ ⑥ 날짜마다 WorkingHours → ScheduleConflict 재검증           :90-98
             ├ ⑦ Reservation 생성 + 요청 assign()                         :100-103
             └ ⑧ saveAllAndFlush (INSERT, 유니크 위반 → 409)               :104-109
          커밋 → 보호사 행 락 해제
```
- **락 경계**: ③에서 잡아서 트랜잭션 커밋·롤백 때 풀린다. 락을 쥔 동안 ④~⑧과 `VisitRequest` UPDATE(@Version), 커밋이 실행된다.
- **격리 수준**: `READ_COMMITTED` 명시(`ReservationWriter.java:45-50`).
  - ① 일반 SELECT가 락보다 먼저 나간다. REPEATABLE READ면 ①에서 스냅샷이 고정되어, ⑤가 앞사람이 커밋한 예약을 못 본다(commit `c5d2466`, `README.md:127`).
  - **락 대상이 바뀌어도 ①이 락보다 먼저인 구조는 그대로이므로 READ_COMMITTED는 계속 필요하다.**

### 2.2 묶음 확정
- `PessimisticLockStrategy.confirmGroup` → `ReservationWriter.confirmGroup`(`:59-66`) → 같은 `confirmAll`.
- 보호사 행 락은 날짜 수와 상관없이 **1번**(`:76-78`). 날짜가 K개면 락 하나로 K개 날짜 전부를 보호한다.
- 락 단위를 날짜로 바꾸면 **K개 락을 잡아야 한다** → 획득 순서 문제(데드락)가 생긴다(6.3절).

### 2.3 다른 전략과 공유하는 지점
| 전략 | 쓰는 락 모드 | 위치 | 이번 변경 영향 |
|---|---|---|---|
| unique (A) | `NONE` | `UniqueConstraintStrategy.java:25-32` | 없음 |
| pessimistic (B) | `PESSIMISTIC_WRITE` | `PessimisticLockStrategy.java:26-34` | **대상** |
| optimistic (C) | `OPTIMISTIC_FORCE_INCREMENT` (Caregiver.@Version) | `OptimisticLockStrategy.java:33-53`, `Caregiver.java:46-51` | 같은 "보호사 단위" 문제가 있음. 이번 범위인지는 열린 질문 |
| redisson (D) | `NONE` + Redis 키 `lock:caregiver:{id}:{date}` | `RedissonLockStrategy.java:20-21, 79-87, 98-100` | 없음. **이미 날짜 단위라 B를 바꾸면 B와 D의 락 단위가 같아짐** |

- 네 전략 모두 `confirmAll`의 `em.find(Caregiver.class, caregiverId, caregiverLock)` 한 줄(`:78`)을 공유한다.
- 차이는 이 `LockModeType` 인자 하나뿐이다(`ReservationWriter.java:33`).
- 락 대상을 바꾸려면 이 인자 구조를 넓히거나, 락 획득을 writer 밖(전략)이나 별도 컴포넌트로 빼야 한다.

## 3. 주요 파일과 책임
| 파일 | 역할 | 비고 |
|---|---|---|
| `reservation/service/ReservationWriter.java` | 확정·취소 트랜잭션. 락 획득(③), 재검증, 저장 | `:78` 한 줄이 4전략 공통 락 지점 |
| `reservation/lock/PessimisticLockStrategy.java` | B. `PESSIMISTIC_WRITE` 전달 | 주석 `:11-13`이 "보호사 단위라 다른 날짜끼리도 기다림"을 이미 단점으로 적음 |
| `reservation/lock/RedissonLockStrategy.java` | D. 날짜 단위 Redis 락, 묶음은 날짜 오름차순으로 획득·역순 해제 | 날짜 정렬·획득 패턴을 **그대로 참고할 수 있음** (`:67-96`) |
| `reservation/lock/OptimisticLockStrategy.java` | C. Caregiver 버전 + 재시도 | `retry(Supplier)` 공통화됨 (`:44-53`) |
| `reservation/lock/ReservationLockStrategy.java` | 전략 인터페이스 | `confirm`, `confirmGroup` 시그니처는 유지해야 함 |
| `caregiver/domain/Caregiver.java` | 보호사. `@Version`은 방법 C 전용 | `:46-51`. B는 이 행을 FOR UPDATE |
| `reservation/domain/Reservation.java` | 예약. 유니크 `uk_reservation_active_slot(caregiver_id, visit_date, start_time, active_slot)`, 인덱스 `idx_reservation_caregiver_date` | `:31-40`. 락 대체로 쓸 수 있는지는 6.2절 |
| `recommendation/filter/ScheduleConflictFilter.java` | 같은 **날짜**의 살아 있는 예약과 겹침 검사 | `:17` 날짜로 먼저 거름 → 정합성 근거 |
| `common/exception/GlobalExceptionHandler.java` | `ConcurrencyFailureException` → 409 `CONCURRENT_MODIFICATION` | `:40-46`. 락 대기 시간 초과(`PessimisticLockingFailureException`)도 여기로 옴 |
| `src/test/resources/application.yml` | H2 MySQL 모드, `LOCK_TIMEOUT=10000`, REPEATABLE_READ | `:3, :6-8` |
| `src/test/resources/application-mysql.yml` | mysqlTest용, 풀 20 | `:9` |

## 4. 따라야 할 기존 패턴
- **전략 선택**: `reservation.lock.strategy`로 고르고(`application.yml:21-24`), 알 수 없는 이름이면 앱이 뜰 때 실패한다(`ReservationService`). 전략 이름 `pessimistic`은 유지한다(k6·README·CI가 이 이름을 씀).
- **`ReservationService`에 `@Transactional`을 붙이지 않음**: 트랜잭션 밖에서 해야 하는 일(재시도, Redis 락)은 전략에서 한다. 락 행을 미리 만드는 단계가 생기면 같은 원칙으로 **트랜잭션 밖(전략)**에 둔다(6.1절).
- **공통 로직은 트랜잭션 애너테이션 없는 private 메서드**(`confirmAll`): 같은 클래스 안 호출에는 `@Transactional`이 적용되지 않는다(self-invocation).
- **예외**: 비즈니스 규칙은 `BusinessException(ErrorCode)`, 동시성 충돌은 Spring `ConcurrencyFailureException` 계열 → 409.
- **스키마 변경**: `ddl-auto: update`(`application.yml:14`)는 새 테이블·컬럼·인덱스를 추가하지만 기존 제약을 지우지 않는다. 테스트는 `create-drop`.
- **테스트 정리 순서**(FK): `ConcurrencyTestSupport.java:52-62`, `RecommendationLatencyTest`, `SampleDataSeederTest`가 모두 같은 순서로 `deleteAllInBatch`한다. 보호사를 가리키는 새 테이블이 생기면 세 곳 모두 `caregiver`보다 먼저 지워야 한다.
- **주석 스타일**: 왜 그렇게 했는지 한국어로 적고, 방법 A~D 이름을 쓴다.

## 5. 재사용할 수 있는 기존 로직
- `RedissonLockStrategy.confirmGroup`(`:67-96`)
  - 날짜 정렬·중복 제거 → 차례로 획득 → 역순 해제.
  - DB 락에서는 해제를 트랜잭션이 해 주므로 획득 순서만 가져오면 된다.
- `ReservationWriter.loadSchedule`(`:140-148`)
  - `Set<LocalDate> dates`를 이미 만든다. 락 대상 날짜도 같은 방식으로 구할 수 있다.
- `ReservationWriter.confirmAll`의 순서(`:71-110`)
  - 락 대상만 바뀌고 ④~⑧은 그대로 재사용한다.
- `ConcurrencyTestSupport`
  - `runConcurrently(List<Long>, Function<Long, ?>)`(`:134`), `overlappingRequests`, `overlappingGroups`, `allWeekCaregiver`, `print`.
  - 새 시나리오도 이 헬퍼로 만들 수 있다.
- `GlobalExceptionHandler.handleConcurrency`(`:40-46`)
  - 락 대기 시간 초과·데드락 희생자 예외가 409로 나간다. 새 예외 코드는 필요 없을 가능성이 높다.

## 6. 쿼리·동시성·스키마

### 6.1 날짜 단위로 잠그려면 "잠글 행"이 있어야 한다
`SELECT ... FOR UPDATE`는 **있는 행**만 잠근다. 그런데 (보호사, 날짜) 조합에는 예약이 하나도 없을 수 있다. 첫 예약이 그런 경우다.

| 후보 | 방법 | 판단 |
|---|---|---|
| ① 잠금 전용 테이블 | `caregiver_day_lock(caregiver_id, visit_date)` 유니크. 행이 없으면 만들고, `FOR UPDATE`로 잠금 | **유력**. 아래에 세부 사항 |
| ② 기존 예약 행 잠금 | `SELECT ... FROM reservation WHERE caregiver_id=? AND visit_date=? FOR UPDATE` | **불가**. 예약이 0건이면 잠글 행이 없다. REPEATABLE READ에서는 갭 락으로 막히지만, 확정은 READ_COMMITTED라 갭 락이 없다(MySQL RC는 갭 락 비활성). 첫 예약 두 건이 동시에 통과한다 |
| ③ MySQL `GET_LOCK('caregiver:1:2026-11-12')` | 이름 기반 락 | 불가에 가까움. H2에 없어 테스트를 못 하고, 트랜잭션이 아니라 세션 단위라 커넥션 풀과 같이 쓰기 위험하다 |
| ④ 보호사 행 락 유지 + 다른 날짜는 락 생략 | — | 의미 없음. 어떤 날짜인지와 상관없이 같은 행을 잠그게 된다 |
| ⑤ D(Redisson)를 기본값으로 | 이미 날짜 단위 | 범위 밖. 인프라 추가는 B를 고른 이유와 반대다(`README.md:129`) |

#### ①을 고를 때 "행이 없으면 만들기"의 세 가지 방식

**(a) 트랜잭션 밖에서 미리 만들기 + 트랜잭션 안에서 `FOR UPDATE`**
- 전략에서 `writer` 호출 전에 `INSERT`를 시도하고, 유니크 위반은 무시한다. 단건이든 묶음이든 날짜 목록을 받아 처리한다.
- 그다음 writer 트랜잭션이 `SELECT ... WHERE caregiver_id=? AND visit_date IN (...) FOR UPDATE`를 실행한다.
- 장점
  - 락 획득이 순수한 `FOR UPDATE` 하나라 MySQL·H2 동작이 단순하다.
  - Redisson이 "트랜잭션 밖에서 날짜를 먼저 읽는" 구조와 같다.
- 단점
  - 확정 1건마다 짧은 트랜잭션(INSERT)이 하나 더 생길 수 있다. 행이 이미 있으면 유니크 위반 한 번, 또는 존재 확인 SELECT로 줄일 수 있다.
  - 날짜를 알려면 요청을 미리 읽어야 한다. Redisson과 같고, 쿼리가 1번 늘어난다.
- **주의**: writer 트랜잭션 안에서 `REQUIRES_NEW`로 만들면 **커넥션을 2개** 쓴다.
  - 풀은 H2 테스트 기본 10, mysql 20(`application-mysql.yml:9`)이다. 동시성 테스트 스레드는 32개다(`ConcurrencyTestSupport.java:42`).
  - 바깥 트랜잭션들이 풀을 다 쥔 채 안쪽 커넥션을 기다리면 **풀 고갈 데드락**이 생긴다. 그래서 반드시 트랜잭션 밖(전략)에서 해야 한다.

**(b) 트랜잭션 안에서 `INSERT ... ON DUPLICATE KEY UPDATE`로 만들면서 잠그기**
- MySQL에서는 중복이면 기존 행에 X 락을 잡으므로 이 문장 하나가 "만들기 + 잠그기"가 된다.
- H2 MySQL 모드도 문법을 지원한다. 다만 행 락 의미가 MySQL과 같은지는 확인이 필요하다.
- JPA 엔티티로는 표현할 수 없어서 네이티브 쿼리가 필요하다.

**(c) 트랜잭션 안에서 `INSERT IGNORE` 후 `SELECT ... FOR UPDATE`** → **피해야 함**
- MySQL은 중복 키 검사 때 기존 레코드에 **S 락**을 잡고 트랜잭션 끝까지 유지한다.
- 두 트랜잭션이 같은 행에 S 락을 쥔 채 각자 `FOR UPDATE`(X 락)로 올리려 하면 **서로를 기다리는 데드락**이 된다. InnoDB의 잘 알려진 "duplicate key S lock" 데드락이다.
- H2에서는 재현되지 않을 수 있다. CI의 MySQL에서만 터지는, `c5d2466`과 같은 유형의 문제가 될 위험이 있다.

### 6.2 정합성 근거: 날짜 단위로 잠가도 겹침이 막히는 이유
- 겹침 검사는 같은 날짜 예약만 본다(`ScheduleConflictFilter.java:17`). 유니크 제약도 `visit_date`를 포함한다(`Reservation.java:34-36`).
- 다른 날짜의 확정은 서로의 검사 결과에 영향을 주지 않는다. (보호사, 날짜) 락이면 "같은 날짜끼리 줄 서기"만 남고 정합성은 같다.
- **보호사 행에 걸려 있던 부수 효과**: 지금은 `FOR UPDATE`가 보호사 행 자체도 잠그므로, 확정 중 보호사 상태 변경(UPDATE)과 직렬화된다.
  - 날짜 락으로 바꾸면 ④의 ACTIVE 검사는 락 없는 읽기가 된다.
  - 다만 현재 상태를 바꾸는 API가 없다. `Caregiver.deactivate()`는 시드에서만 호출한다(`SampleDataSeeder.java:75`). `CaregiverService`도 `register`, `get`, `addAvailability`뿐이다.
  - 그래서 **실제로 문제가 되는 경로는 없다**. 계획에 "보호사 상태 변경 API가 생기면 다시 보아야 함"으로 적어 둘 일이다.
- 근무 가능 시간 추가(`addAvailability`)는 지금도 확정과 직렬화되지 않는다(availability 테이블은 잠그지 않음). 변화 없음.

### 6.3 묶음 확정과 데드락
- 묶음은 K개 (보호사, 날짜) 락을 잡는다.
- 묶음 X(3일·5일)와 Y(5일·3일)가 서로 다른 순서로 잡으면 데드락이다. 날짜 오름차순으로 통일해야 한다. Redisson과 같은 원칙(`RedissonLockStrategy.java:71`)이다.
- `... WHERE caregiver_id=? AND visit_date IN (...) ORDER BY visit_date FOR UPDATE` 한 문장으로 잠그면 InnoDB는 인덱스를 스캔하는 순서대로 잠근다.
  - 유니크 인덱스 (caregiver_id, visit_date) 범위 스캔은 오름차순이라 순서가 일정하다.
  - 하지만 옵티마이저 선택에 기대는 셈이다. **날짜마다 한 문장씩 정렬 순서로** 잠그는 쪽이 명시적이다.
  - 쿼리 수는 K(≤14)번 늘어난다. 트레이드오프는 계획에서 정한다.
- 단건과 묶음이 섞여도 단건은 락 1개라 순환 대기가 생기지 않는다.
- 데드락이 나면 MySQL이 한쪽을 희생시킨다. `CannotAcquireLockException` / `DeadlockLoserDataAccessException` → `ConcurrencyFailureException` 계열 → 409.

### 6.4 쿼리 수 변화 (단건 확정 기준)
| 단계 | 지금 (보호사 락) | 바뀐 뒤 (방식 a 가정) |
|---|---|---|
| 트랜잭션 밖 | 0 | 요청 날짜 조회 1 + 락 행 확보(존재 확인 또는 INSERT) 1~2 |
| 트랜잭션 안 락 | `caregiver FOR UPDATE` 1 | `caregiver_day_lock FOR UPDATE` 1 + 보호사 일반 조회 1 (`em.find(..., NONE)`) |
| 나머지 | 요청 1, 근무 1, 예약 1, INSERT 1, 요청 UPDATE 1 | 같음 |
- 확정 1건당 쿼리가 2~3개 늘어난다. 대신 같은 보호사의 다른 날짜 확정끼리 기다리지 않는다.
- 추천 경로(쿼리 6회)는 영향이 없다.

### 6.5 MySQL 동작 확인 포인트
- 유니크 인덱스로 한 행을 찾는 `FOR UPDATE`: READ_COMMITTED에서는 **레코드 락만** 잡는다(갭 락 없음). 다른 날짜 행과 간섭하지 않는다.
- 행이 없는 날짜에 `FOR UPDATE`: RC에서는 아무것도 잠그지 않는다. 그래서 **6.1의 "행 미리 만들기"가 반드시 필요하다.** 빠뜨리면 첫 예약 경쟁에서 겹침이 생기고, H2와 MySQL 결과가 다를 수 있다.
- 락 대기 시간
  - MySQL `innodb_lock_wait_timeout` 기본 50초, H2 `LOCK_TIMEOUT=10000`(10초, `application.yml:3`).
  - 날짜 단위로 줄이면 대기 자체가 줄어든다.
- FK `caregiver_day_lock.caregiver_id → caregiver.id`를 두면, MySQL은 INSERT 때 부모 행(caregiver)에 **S 락**을 잡는다.
  - 행을 만드는 순간에만 잡고, (a)는 트랜잭션 밖에서 짧게 끝나므로 영향은 작다.
  - 같은 트랜잭션에서 보호사 행을 X로 잠그는 코드가 남아 있으면 상호작용을 확인해야 한다. 바뀐 뒤에는 보호사 행을 잠그지 않는다.

### 6.6 스키마
- 새 테이블 예: `caregiver_day_lock`
  - 컬럼: `id`(IDENTITY) 또는 복합 키, `caregiver_id`(FK, NOT NULL), `visit_date`(NOT NULL)
  - 유니크: `uk_caregiver_day_lock(caregiver_id, visit_date)`
  - `ddl-auto: update`로 새 테이블이 만들어진다. 기존 테이블은 바뀌지 않는다.
- 행 수: 확정이 있었던 (보호사, 날짜)마다 1행. 보호사 1,000명 × 365일 ≈ 연 36만 행이라 작다. 지난 날짜 정리는 범위 밖으로 두어도 된다.
- 기존 `caregiver` 행과 `Caregiver.@Version`은 그대로 둔다. 방법 C가 계속 쓴다.

## 7. 테스트 현황
| 테스트 | 태그 | 내용 | 이번 변경과의 관계 |
|---|---|---|---|
| `ReservationConcurrencyTest` | concurrency | 같은 시작 시간 100건(A·B·C), 겹침 100건(B·C), A의 한계, 같은 요청 다른 보호사 10명 | 모두 **같은 날짜**라서 날짜 락으로 바꿔도 결과(성공 1, 겹침 0)가 같아야 함 → 회귀 확인용 |
| `GroupReservationConcurrencyTest` | concurrency | 날짜를 일부 공유하는 묶음 21개, 묶음+단건 50개, 같은 묶음 다른 보호사 10명 | **날짜 락 획득 순서 검증에 그대로 쓸 수 있음**(묶음마다 날짜 조합이 달라서 순서가 틀리면 데드락이 남) |
| `RedissonConcurrencyTest` | concurrency, Redis 있을 때만 | D 검증 | 영향 없음 |
| `ReservationApiTest`, `GroupReservationApiTest` | — | 규칙·에러 코드 | 회귀 확인 |
| `RecommendationQueryCountTest` | — | 추천 쿼리 6회 | 영향 없음 |

- **빠진 케이스 (새로 필요)**: "같은 보호사, 다른 날짜는 서로 기다리지 않는다"를 보여 주는 테스트가 없다.
  - 소요 시간 비교는 H2라 근거가 약하다(`README.md`도 H2 시간을 비교 근거로 쓰지 않는다고 적음).
  - 결정적으로 확인하려면 이렇게 한다.
    1. 스레드 1이 `TransactionTemplate`으로 (보호사, D1) 락을 잡고 래치에서 멈춘다.
    2. 그동안 스레드 2가 같은 보호사 D2를 확정하면 **바로 끝나야** 한다.
    3. 같은 보호사 D1을 확정하면 스레드 1이 끝날 때까지 **끝나지 않아야** 한다.
  - 이 테스트는 지금 코드에서는 2번이 막혀 실패하고, 바뀐 뒤 통과한다. 개선을 증명하는 테스트가 된다.
- mysqlTest 대상: 새 동시성 테스트에 `@Tag("concurrency")`를 붙이면 CI MySQL에서 실행된다(`build.gradle:49-59`, `ci.yml:53-57`). 6.1(c)와 6.5의 MySQL 고유 동작은 여기서만 검증된다.
- 부하 측정: `k6/reservation.js`는 보호사 20명 × 날짜 7일(`:115`) × 무작위 시간이라 다른 날짜 경합이 많다. 바뀐 뒤 처리량 차이가 이 스크립트에서 드러날 것이다. 다만 k6 실행·기록은 다음 작업으로 남아 있다.

## 8. 위험 요소
1. **행이 없는 날짜에 락이 안 걸림**: "락 행 미리 만들기"를 빠뜨리면 READ_COMMITTED에서는 첫 예약 경쟁이 무방비다. 동시성 테스트가 모두 빈 날짜에서 시작하므로 바로 드러나야 한다.
2. **`INSERT IGNORE` + `FOR UPDATE` 데드락**(6.1-c): H2에서는 통과하고 MySQL에서만 터질 수 있다.
3. **REQUIRES_NEW 커넥션 고갈**(6.1-a): 트랜잭션 안에서 새 트랜잭션으로 행을 만들면 풀 10/20, 스레드 32에서 멈출 수 있다.
4. **묶음 락 순서**: 정렬하지 않으면 묶음끼리 데드락이 생긴다.
5. **4전략 공통 지점 `confirmAll:78`**
   - `LockModeType` 인자 하나로 4전략을 구분하는 구조라, 락 대상을 바꾸면 writer 시그니처가 바뀔 수 있다.
   - `ReservationLockStrategy`의 공개 시그니처(`confirm`, `confirmGroup`)는 그대로 둬야 한다.
   - writer의 `confirm(.., LockModeType)` 시그니처를 바꾸면 4개 전략 클래스를 모두 고쳐야 한다.
6. **READ_COMMITTED 유지**: 락 대상과 무관하게 ①이 락보다 먼저라서 필요하다. 이 결정을 바꾸면 `c5d2466` 버그가 재발한다.
7. **보호사 상태와의 직렬화 상실**(6.2): 지금은 문제가 되는 API가 없지만 문서화가 필요하다.
8. **테스트 정리 순서**: 새 테이블이 `caregiver`를 FK로 가리키면 정리 코드 세 곳을 모두 고쳐야 한다. 빠뜨리면 다음 테스트가 FK 위반으로 실패한다.
9. **문서 수치**: README 결과 표·대안 비교 표(`README.md:11, :121`)의 B 설명("같은 보호사 요청을 줄 세움")과 한계(`:153`), interview-notes(`:48, :65, :87`), `PessimisticLockStrategy` 주석(`:11-13`)을 함께 고쳐야 한다.

## 9. 열린 질문
1. **방식 선택**: 6.1의 (a) 트랜잭션 밖에서 미리 만들기 vs (b) `INSERT ... ON DUPLICATE KEY UPDATE`.
   - (a)는 이식성이 좋고 동작이 단순하다. (b)는 쿼리가 적지만 네이티브 SQL이고 H2/MySQL 의미 차이를 확인해야 한다.
2. **방법 C(낙관적)도 날짜 단위로 바꿀지**: 지금은 `Caregiver.@Version`이라 같은 문제가 있다. 잠금 행에 `@Version`을 두면 함께 해결된다. 범위를 B로 한정할지 정해야 한다.
3. **기존 보호사 락을 "선택지"로 남길지**
   - 예: 전략 이름 `pessimistic`은 날짜 락으로 바꾸고, 비교용으로 `pessimistic-caregiver`를 남긴다.
   - 아니면 그대로 교체한다. 남기면 k6로 전후를 같은 코드에서 비교할 수 있다.
4. **묶음 락 획득**: IN 한 문장 + `ORDER BY` vs 날짜마다 한 문장(명시적 순서, 쿼리 K번).
5. **잠금 행 정리**: 지난 날짜 행을 지우는 작업을 이번에 넣을지(기본: 범위 밖).
6. **개선 증명 방식**: 래치 기반 "다른 날짜는 안 기다림" 테스트로 충분한지, k6 수치까지 이번에 기록할지(기본: k6는 다음 작업).
