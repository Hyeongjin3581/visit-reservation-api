# Plan: 날짜 락(B)의 준비 비용 줄이기

> 메모는 해당 위치에 `<!-- NOTE: ... -->`로 달고 `/apply-notes`로 반영한다.
> 근거 조사: [research.md](research.md)

## 0. 결정 사항 (research.md 9절 답)
| # | 질문 | 선택 | 이유 |
|---|---|---|---|
| 1 | 줄이는 방법 | **(1) 없을 때만 준비하고 다시 시도** | 평소 확정에서 트랜잭션 밖 SQL과 트랜잭션이 0이 된다. 남는 추가 비용은 트랜잭션 안 `FOR UPDATE` 1문장뿐이다. 잠금 행은 (보호사, 날짜)마다 처음 한 번만 필요하므로, 드문 경우에만 비용을 낸다. (2) `INSERT ... ON DUPLICATE KEY UPDATE`는 MySQL 데드락 위험과 H2 의미 차이 때문에 이전 계획에서 제외했고, 그 이유가 그대로다. (3)은 절반만 줄고 (4)는 전역 설정이라 범위 밖이다 |
| 2 | k6 재측정 | **B·B' 각 3회, 번갈아** | 지난 측정과 같은 조건(사용자 50명, 30초, 보호사 20명, 예열 10초, 전략마다 새 앱·빈 DB)이라 그대로 비교할 수 있다 |

## 1. 목표
- `pessimistic`(B) 확정의 평소 경로에서 트랜잭션 밖 조회 2개(날짜 조회, 잠금 행 존재 확인)를 없앤다.
- 잠금 행이 없는 처음 날짜에서만 "롤백 → 트랜잭션 밖에서 행 만들기 → 1번 재시도"를 한다.

**완료 조건**
- 쿼리 수 테스트: 잠금 행이 이미 있을 때 B의 확정 SQL 수 = B'의 확정 SQL 수 + 1 (`FOR UPDATE` 1문장).
- 기존 테스트가 모두 통과한다. 특히 다음 세 가지다.
  - `LockScopeTest`: D2 확정은 처음 날짜라 재시도 경로를 타는데, 그래도 3초 안에 끝나야 한다.
  - 동시성 테스트: 32개 스레드가 동시에 재시도 경로를 탄다.
  - `@Transactional` API 테스트: 테스트 트랜잭션에 합류한 상태에서도 재시도가 동작해야 한다.
- k6 재측정 결과로 README 표와 해석을 고친다.

## 2. 범위
**포함**
- `MissingDayLockException` (신규)
- `ReservationWriter.lockDays` 수정
- `PessimisticLockStrategy` 수정 (트랜잭션 밖 조회 제거, 재시도 추가)
- 쿼리 수 테스트 추가
- README·interview-notes 문장 수정
- k6 재측정과 README 기록

**제외 (이번에 안 함)**
- `INSERT ... ON DUPLICATE KEY UPDATE`
- JDBC 전역 옵션 변경
- 낙관적 락(C) 데드락 개선
- 기본 전략 변경 (재측정 결과를 보고 따로 정함)

## 3. 접근 방식
```
PessimisticLockStrategy.confirm
 └ withDayLocks:
    ├ 1차: writer.confirm(.., CAREGIVER_DAY)   ← 평소엔 여기서 끝 (트랜잭션 1개, 밖 SQL 0)
    │     lockDays: 날짜 오름차순 FOR UPDATE, 행이 없으면 MissingDayLockException(보호사, 날짜 전체)
    │     → 쓰기 전이라 롤백할 것 없음, 잡았던 앞 날짜 락도 롤백으로 풀림
    └ 행이 없었으면: dayLocks.prepare(보호사, 날짜들)  ← 트랜잭션 밖 (커넥션 1개씩, 겹치지 않음)
                    → 2차: writer.confirm(...)  ← 또 없으면 IllegalStateException (재시도는 1번만)
```
- 요청이나 묶음을 찾지 못하는 경우(404)는 지금도 writer 안에서 같은 에러 코드로 처리한다. 그래서 전략에서 미리 읽을 필요가 없다.
- 없는 보호사는 잠그기 전 단계(`em.find`)에서 `CAREGIVER_NOT_FOUND`로 걸리므로, 재시도 경로로 오지 않는다.
- **버린 대안:** research.md 6절 표 참고.

## 4. 변경 파일
| 파일 경로 | 변경 내용 | 신규/수정 |
|---|---|---|
| `reservation/service/MissingDayLockException.java` | 잠금 행 없음 신호 (보호사 id, 날짜 목록) | 신규 |
| `reservation/service/ReservationWriter.java` | `lockDays`: 행이 없으면 `IllegalStateException` 대신 `MissingDayLockException` | 수정 |
| `reservation/lock/PessimisticLockStrategy.java` | 트랜잭션 밖 날짜 조회·`prepare` 제거, `withDayLocks` 재시도, `VisitRequestRepository` 의존 제거 | 수정 |
| `reservation/lock/CaregiverDayLocks.java` | 주석: "처음 날짜에서만 호출됨" | 수정 |
| 테스트 `reservation/DayLockQueryCountTest.java` | 평소 경로 쿼리 수 B = B' + 1 | 신규 |
| `README.md`, `docs/interview-notes.md` | 잠금 행 준비 설명, k6 표·해석 갱신 | 수정 |

## 5. 상세 변경

### 5.1 `MissingDayLockException`
```java
// 확정 트랜잭션 안에서 (보호사, 날짜) 잠금 행이 없을 때 던짐 → 트랜잭션이 롤백되고,
// 전략이 트랜잭션 밖에서 행을 만든 뒤 한 번 다시 시도함 (사용자에게 나가는 에러가 아님)
public class MissingDayLockException extends RuntimeException {
    private final Long caregiverId;
    private final List<LocalDate> dates;   // 확정하려던 날짜 전체 (묶음이면 모두)
    ...
}
```

### 5.2 `ReservationWriter.lockDays`
```java
private void lockDays(Long caregiverId, List<VisitRequest> requests) {
    List<LocalDate> dates = requests.stream().map(VisitRequest::getVisitDate).sorted().distinct().toList();
    for (LocalDate date : dates) {
        if (dayLockRepository.findForUpdate(caregiverId, date).isEmpty()) {
            // 처음 확정하는 (보호사, 날짜). 아직 쓰기 전이라 롤백할 것이 없음 → 전략이 행을 만들고 다시 시도
            throw new MissingDayLockException(caregiverId, dates);
        }
    }
}
```

### 5.3 `PessimisticLockStrategy`
```java
@Override
public Reservation confirm(Long visitRequestId, Long caregiverId) {
    return withDayLocks(() -> writer.confirm(visitRequestId, caregiverId, ConfirmLock.CAREGIVER_DAY));
}

@Override
public List<Reservation> confirmGroup(Long groupId, Long caregiverId) {
    return withDayLocks(() -> writer.confirmGroup(groupId, caregiverId, ConfirmLock.CAREGIVER_DAY));
}

// 평소엔 바로 확정 (트랜잭션 1개). 처음 확정하는 (보호사, 날짜)라 잠금 행이 없을 때만
// 트랜잭션 밖에서 행을 만들고 한 번 다시 시도 → 확정마다 미리 확인하던 조회 2번이 사라짐
private <T> T withDayLocks(Supplier<T> confirm) {
    try {
        return confirm.get();
    } catch (MissingDayLockException e) {
        dayLocks.prepare(e.getCaregiverId(), e.getDates());
        try {
            return confirm.get();
        } catch (MissingDayLockException again) {
            throw new IllegalStateException("잠금 행을 만들지 못했습니다", again);
        }
    }
}
```

### 5.4 `DayLockQueryCountTest`
```java
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
class DayLockQueryCountTest extends ConcurrencyTestSupport {   // 커밋하는 테스트라 정리 코드 재사용
    @Test
    void 잠금_행이_있으면_확정_쿼리는_보호사_행_락보다_한_문장만_많다() {
        // 보호사 둘, 같은 날 안 겹치는 요청 둘씩
        // 각 전략으로 첫 확정(잠금 행 생성·준비)을 한 번 하고, 두 번째 확정의 prepared statement 수를 셈
        assertThat(dayLockCount).isEqualTo(rowLockCount + 1);
    }
}
```
- 절대값(7, 6)이 아니라 차이로 단언한다. Hibernate 버전에 따라 SQL 개수가 바뀌어도 의미가 유지된다.

## 6. 바꾸면 안 되는 것
- 전략 이름과 `ReservationLockStrategy` 시그니처
- writer의 `READ_COMMITTED`와 확정 순서, 에러 코드
- `prepare`는 트랜잭션 밖에서 실행 (`REQUIRES_NEW` 금지)
- 날짜 오름차순 잠금
- B'·A·C·D의 동작

## 7. 영향과 검증
- **쿼리 수**
  - 평소 확정: 트랜잭션 밖 2 → 0, 트랜잭션 3 → 1
  - 처음 날짜: 롤백된 1차(요청·보호사·`FOR UPDATE` 3문장) + 준비(존재 확인·보호사 확인·INSERT) + 2차 확정
- **동시성/락**
  - 같은 날짜 직렬화와 묶음 획득 순서는 같다.
  - 1차가 실패하면 롤백으로 앞 날짜 락이 모두 풀리므로, 락을 쥔 채 트랜잭션 밖 작업을 하지 않는다.
- **스키마**: 변경 없음.
- **검증**
  - 단계마다 `./gradlew compileJava compileTestJava`, 마지막에 `./gradlew test`.
  - 동시성 테스트와 `LockScopeTest`는 `@Tag("concurrency")` → **`./gradlew mysqlTest` 대상** (변경 없음).
  - `DayLockQueryCountTest`는 일반 `test`에서 실행한다.
  - k6: B·B' 각 3회를 번갈아 돌리고 README 표를 갱신한다. 이번에는 결과와 상관없이 그대로 기록한다.

## 8. TODO
- [x] Phase 1 — `MissingDayLockException`, `lockDays` 수정, `PessimisticLockStrategy.withDayLocks` → 기존 테스트 전부 통과
- [x] Phase 2 — `DayLockQueryCountTest`
- [x] Phase 3 — README(락 범위 축소 문단의 준비 설명) · interview-notes 문장 수정
- [x] `./gradlew test` 통과
- [x] Phase 4 — k6 재측정 (B·B' 각 3회) → README k6 표·해석 갱신

### 구현 메모
- 구현은 계획대로 했다. `@Transactional` API 테스트에서도 재시도가 동작했다(합류한 트랜잭션은 롤백 전용으로 표시되지만 테스트가 어차피 롤백함).
- `./gradlew test`: 90개 중 87개 통과, 3개는 Redis 없어 건너뜀.
- `DayLockQueryCountTest`: 평소 확정 SQL 수는 날짜 락 7, 보호사 행 락 6.
- k6 (B·B' 번갈아 3회씩): B 94.6 / 119.1 / 137.9 req/s, B' 138.3 / 158.0 / 212.4 req/s.
  - B 평균은 107 → 117 req/s로 약 10% 개선됐지만, B'(평균 170)보다 여전히 약 30% 느리다.
  - 데드락·락 대기 초과는 0건이었다.
  - 남은 차이의 원인은 확정하지 못했다(README에 가설로 기록).
- 기본 전략(`pessimistic`)은 바꾸지 않았다. 결과를 보고 따로 정한다(제외 범위).
