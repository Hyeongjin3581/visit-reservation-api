# Research: 날짜 락(B)의 준비 비용 줄이기

## 1. 범위
- 조사한 경로
  - `reservation/lock/PessimisticLockStrategy.java`
  - `reservation/lock/CaregiverDayLocks.java`
  - `reservation/service/ReservationWriter.java`
  - `reservation/repository/CaregiverDayLockRepository.java`
  - k6 측정 결과(README "k6 부하 테스트")
- 조사 목적
  - k6에서 (보호사, 날짜) 락(B)이 보호사 행 락(B')보다 느렸다: 처리량 98~122 vs 133~217 req/s.
  - 그런데 락 대기는 거의 없었다. 60초 동안 1초마다 조회해서 대기 중인 락이 B 2건, B' 7건이었다.
  - 그러니 원인은 락 범위가 아니라 **확정 1건당 추가 작업**이다. 그 작업이 무엇이고 어떻게 줄일지 조사한다.

## 2. 전체 흐름 (지금 B의 단건 확정)
```
PessimisticLockStrategy.confirm                                 PessimisticLockStrategy.java:36-42
 ├ ① visitRequestRepository.findById(visitRequestId)            :38   ← 트랜잭션 밖, 읽기 전용 트랜잭션 1개
 ├ ② dayLocks.prepare(caregiverId, [date])                      :41
 │    ├ findExistingDates(caregiverId, dates)                   CaregiverDayLocks.java:38   ← 읽기 전용 트랜잭션 1개
 │    └ (행이 없을 때만) existsById + saveAndFlush               :41-51   ← 처음 (보호사, 날짜)에만
 └ ③ writer.confirm(.., CAREGIVER_DAY)  @Transactional(READ_COMMITTED)
      요청 조회 → 보호사 조회(NONE) → lockDays: 잠금 행 FOR UPDATE   ReservationWriter.java:80-89, 152-157
      → 근무 시간·예약 조회 → 재검증 → INSERT, UPDATE
```
B'(`CaregiverRowLockStrategy`)는 ③만 실행한다. 보호사 조회가 곧 `FOR UPDATE`다.

## 3. 비용 비교
| 항목 | B' | B (지금) |
|---|---|---|
| 확정 트랜잭션 안 SQL | 요청, 보호사 FOR UPDATE, 근무, 예약, INSERT, UPDATE = 6 | 요청, 보호사, **잠금 행 FOR UPDATE**, 근무, 예약, INSERT, UPDATE = 7 |
| 트랜잭션 밖 SQL | 0 | ① 1 + ② 1 = **2** (처음 날짜면 +2) |
| 트랜잭션 수 | 1 | **3** |

- 트랜잭션 밖 두 조회는 Spring Data 리포지토리 메서드다. `SimpleJpaRepository`의 `@Transactional(readOnly = true)`로 각각 **읽기 전용 트랜잭션**이 된다.
- 이런 트랜잭션은 SQL 한 줄 외에도 MySQL과 여러 번 주고받는다. 커넥션을 빌리고, `setReadOnly`(MySQL 드라이버가 `SET SESSION TRANSACTION READ ONLY`로 전송), `setAutoCommit(false)`, 커밋, 상태 원복을 한 뒤 반납한다.
- 그래서 B는 확정 1건마다 B'보다 **트랜잭션 2개 분량의 왕복과 커넥션 대여가 더 든다.**
- k6 측정과 맞아떨어진다.
  - 동시 사용자 1명이면 차이가 거의 없다(B 30.7, B' 22.1 req/s).
  - CPU를 앱·MySQL·k6가 나눠 쓰는 포화 상태에서만 처리량 차이로 드러난다.
  - 커넥션 풀을 50개로 늘리면 동시에 도는 작업이 더 많아져 CPU 경쟁이 커지고, B가 더 나빠졌다(57.6 req/s).
- (추정) 쿼리 단위 시간은 재지 않았다. 계획의 검증은 "트랜잭션 밖 SQL이 0이 되었는지"를 Hibernate 통계로 확인하고, k6로 다시 잰다.

## 4. 각 단계가 왜 있었나
- ① **날짜 조회**: 잠금 행을 준비하려면 날짜가 필요하다. 하지만 날짜는 ③ 트랜잭션 안에서 이미 읽는다(`findWithRecipientById`).
- ② **존재 확인**: 잠금 행은 (보호사, 날짜)마다 **처음 한 번만** 만들면 된다. 한 번 만든 행은 지우지 않는다(정리 작업 없음). 그런데도 매번 확인한다.
  - k6 기준으로 (보호사, 날짜) 조합은 20 × 7 = 140개다. 확정 수천 건 중 140건만 실제로 행이 필요하다.
- 트랜잭션 밖에서 하는 이유는 커넥션 풀 고갈을 막기 위해서다. 트랜잭션 안에서 `REQUIRES_NEW`를 쓰면 요청 하나가 커넥션 2개를 쥔다. 이 제약은 그대로 지켜야 한다.

## 5. 재사용할 수 있는 기존 로직
- `ReservationWriter.lockDays`(`:152-157`): 지금은 행이 없으면 `IllegalStateException`을 던진다.
  - 이미 "행이 없음"을 트랜잭션 안에서 감지하고 있다는 뜻이다. 이걸 **복구 가능한 신호**로 바꾸면 ①·②를 평소 경로에서 없앨 수 있다(6절).
  - 잠그는 시점이 요청 조회와 보호사 조회 직후, **쓰기 전**(`:88-89`)이다. 여기서 실패하면 롤백할 쓰기가 없다.
- `CaregiverDayLocks.prepare`: 트랜잭션 밖에서 행을 만들고, 중복을 무시하며, 없는 보호사를 거른다. 그대로 재사용한다.
- 트랜잭션 밖 재시도 패턴: `OptimisticLockStrategy.retry`(`:44-53`).

## 6. 줄이는 방법 후보
| 후보 | 평소 경로 비용 | 위험 |
|---|---|---|
| **(1) 없을 때만 준비하고 다시 시도** | 확정 트랜잭션 안에서 잠금 행이 없으면 날짜 목록을 담은 예외를 던져 롤백한다. 전략이 트랜잭션 밖에서 `prepare`를 실행하고 한 번 다시 시도한다. 평소에는 트랜잭션 밖 SQL이 **0**이고, 안은 B'보다 1문장 많다 | 처음 날짜일 때만 롤백 1번 + 준비 + 재시도. 쓰기 전에 실패하므로 롤백할 게 없다. 커넥션 2개를 쥐는 순간이 없다 |
| (2) 트랜잭션 안에서 `INSERT ... ON DUPLICATE KEY UPDATE` | 트랜잭션 밖 0, 안 1문장. 만들기와 잠그기를 한 번에 | 네이티브 SQL. 같은 새 키를 동시에 넣을 때 InnoDB 갭·삽입 의도 락 데드락 보고가 있다. 테이블에는 자동 증가 PK와 별도 유니크 키가 있다. H2 의미 차이도 검증해야 한다. 이전 계획(0절 1번)에서 같은 이유로 제외했다 |
| (3) ①·②를 한 쿼리로 합치기 | 트랜잭션 밖 1 (2 → 1) | 절반만 줄어든다 |
| (4) JDBC 옵션 (`readOnlyPropagatesToServer=false`, `useLocalSessionState=true`) | 읽기 전용 트랜잭션의 `SET` 왕복을 줄인다 | 모든 경로에 영향을 주는 전역 설정이다. 원인을 없애지 않고 증상만 줄인다. 범위 밖 |

## 7. 테스트 현황과 영향
- `LockScopeTest`
  - 첫 테스트는 D1 행을 `prepare`로 만든 뒤 붙잡는다. D2 확정은 행이 없어 (1)의 "준비 후 재시도" 경로를 탄다. 바로 끝나야 하는 판정(3초)은 그대로 유지돼야 한다.
  - D1 확정은 행이 있으므로 기다리는 경로다.
- 동시성 테스트(`ReservationConcurrencyTest`, `GroupReservationConcurrencyTest`)
  - 모두 빈 날짜에서 시작한다. 스레드 32개가 동시에 "행 없음 → 롤백 → 준비(중복 무시) → 재시도"를 거친다. **(1)의 동시 경로를 그대로 검증한다.**
- `@Transactional` API 테스트(`ReservationApiTest`, `GroupReservationApiTest`)
  - writer가 테스트 트랜잭션에 합류한다. 예외로 빠져나오면 바깥 트랜잭션이 **롤백 전용**으로 표시된다.
  - 테스트 트랜잭션은 어차피 끝에서 롤백하므로 커밋 실패는 없다. 하지만 재시도가 같은 트랜잭션·세션에서 일어나므로 동작을 확인해야 한다.
  - 예외는 첫 쓰기보다 앞(잠금 단계)에서 던지므로 세션에 반쯤 저장된 엔티티는 남지 않는다.
- 쿼리 수를 확인하는 테스트는 없다. "평소 확정은 트랜잭션 밖 SQL 0"을 Hibernate 통계로 확인하는 테스트가 필요하다.

## 8. 위험 요소
1. **재시도 무한 반복**: 준비한 뒤에도 행이 없으면(보호사가 없어 만들지 않은 경우 등) 다시 실패한다. 재시도는 한 번만 한다. 없는 보호사는 잠그기 전 `CAREGIVER_NOT_FOUND`(`:80-87`)에서 먼저 걸리므로 이 경로로 오지 않는다.
2. **예외가 바깥으로 새는 경우**: 내부 신호용 예외가 `GlobalExceptionHandler`까지 가면 500이 된다. 재시도 후에도 실패하면 지금처럼 `IllegalStateException`으로 바꿔 원인을 분명히 한다.
3. **묶음**: 앞쪽 날짜를 잠근 뒤 뒤쪽 날짜에서 행이 없으면, 롤백으로 앞 락도 풀린다. 예외에는 묶음의 날짜 전체를 담아 한 번에 준비한다. 재시도는 1번이면 된다.
4. **B'·A·C·D 경로**: `ConfirmLock.CAREGIVER_DAY`일 때만 바뀌므로 영향 없음.
5. **문서 수치**: README의 k6 표와 해석은 바뀐 뒤 다시 재서 고쳐야 한다.

## 9. 열린 질문
1. 후보 (1)로 갈지. (2)는 이전 결정과 같은 이유로 제외, (3)·(4)는 효과가 부족함.
2. k6 재측정 횟수: 지난번처럼 B·B' 각 3회를 번갈아 돌릴지.
