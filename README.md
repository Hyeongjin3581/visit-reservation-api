# 방문 일정 예약·배정 API

요양보호사 근무 가능 시간과 방문 요청을 받아, 조건에 맞는 보호사를 추천하고 동시 요청에도 중복 배정 없이 예약을 확정하는 백엔드 API입니다.

> 1인 개인 프로젝트 · 팀 프로젝트 '온케어'에서 맡았던 추천·배정 로직을 다시 설계하고, 개선 결과를 수치로 기록했습니다.

## 결과 요약

| 문제 | 개선 전 | 개선 후 | 근거 |
|---|---|---|---|
| 동시 확정 시 중복 배정 (시작 시간이 다른 겹침 요청 100건, MySQL) | 유니크 제약만: 5건 성공, 겹친 예약 10쌍 | 비관적 락: 1건 성공, 겹침 0 | `ReservationConcurrencyTest` |
| 추천 1회 쿼리 수 (후보 100명) | 504회 (온케어 방식) | 6회 | `RecommendationQueryCountTest` |
| 추천 1회 응답 시간 (보호사 1,000명, MySQL) | p50 4,404ms / p95 4,451ms | p50 38ms / p95 53ms | `RecommendationLatencyTest` |

- MySQL 수치는 GitHub Actions의 MySQL 8 컨테이너에서 `./gradlew mysqlTest`로 잰 값입니다 (서비스 호출 시간, HTTP 왕복 제외). 같은 측정을 H2에서 하면 p95 8,921ms → 115ms였습니다.
- 온케어 방식 수치는 온케어 서버를 직접 잰 것이 아니라, 같은 조회 구조를 이 프로젝트 엔티티로 테스트 코드에 재현해서 잰 값입니다. ([docs/oncare-review.md](docs/oncare-review.md))

## 진행 상황

| 주차 | 내용 | 상태 |
|---|---|---|
| 1주 | 프로젝트 구성, 엔티티, 등록 API, 공통 예외 처리 | ✅ |
| 2주 | 추천 API (필터·점수 정책) + N+1 측정·개선 | ✅ |
| 3주 | 예약 확정·취소 + 동시성 제어 4가지 비교 | ✅ (H2·MySQL·Redis에서 확인) |
| 4주 | CI, Swagger, 샘플 데이터, k6 스크립트, 문서 | ✅ (k6 실행·배포는 남음) |

## 기술 스택

Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL 8 · Redis(Redisson) · H2(테스트) · JUnit 5 · springdoc(Swagger UI) · GitHub Actions · Gradle

## 실행 방법

### 앱 실행
로컬 MySQL 8이 필요합니다. `visit_db`는 처음 실행할 때 자동으로 만들어집니다. 접속 정보가 기본값(`localhost:3306`, `root`, 비밀번호 없음)과 다르면 환경 변수로 넣습니다. Spring은 `.env` 파일을 자동으로 읽지 않으니, 아래처럼 터미널에서 설정합니다.

```powershell
$env:DB_PASSWORD="내 MySQL 비밀번호"
./gradlew bootRun
```

- Swagger UI: http://localhost:8080/swagger-ui.html
- 락 전략 바꾸기: `$env:RESERVATION_LOCK_STRATEGY="optimistic"` (unique / pessimistic / pessimistic-caregiver / optimistic / redisson)
- Redis 분산 락: `$env:REDIS_ENABLED="true"`, `$env:RESERVATION_LOCK_STRATEGY="redisson"` (Redis 127.0.0.1:6379 필요, `docker compose up -d redis`로도 띄울 수 있음)

> 1주차 버전으로 만든 로컬 DB가 있으면 예약 테이블의 옛 유니크 제약(`uk_reservation_caregiver_slot`)이 남아 있을 수 있습니다. `ddl-auto: update`는 제약을 지우지 않으므로 `visit_db`를 지우고 다시 만들거나 그 제약을 직접 지워야 합니다.

### 테스트
```bash
./gradlew test          # H2 전체 테스트 (Redis가 떠 있으면 Redis 분산 락 테스트도 실행)
./gradlew mysqlTest     # MySQL에서 동시성·응답 시간 테스트 (DB_PASSWORD 환경 변수 필요, visit_test DB 사용)
./gradlew test -Pperf   # H2에서 응답 시간 테스트까지 포함
```
GitHub Actions(`.github/workflows/ci.yml`)는 MySQL·Redis 컨테이너를 띄우고 두 작업을 모두 실행합니다.

### 샘플 데이터와 k6 부하 테스트
```powershell
# 1) 샘플 데이터(보호사 1,000명, 근무 가능 시간 7,000건, 완료 방문 2,000건)를 넣고 앱 실행
$env:DB_PASSWORD="내 MySQL 비밀번호"
./gradlew bootRun --args='--spring.profiles.active=seed'

# 2) 다른 창에서 k6 실행 (설치: winget install k6 --source winget)
k6 run k6/recommendation.js                         # 추천 API p95
k6 run -e STRATEGY=pessimistic k6/reservation.js     # 예약 확정 처리량 (락 전략마다 앱을 다시 띄워 반복)
```
- 샘플 데이터는 보호사가 한 명도 없을 때만 들어갑니다. 다시 넣으려면 `visit_db`를 지우고 실행합니다.
- 예약 테스트는 실행할 때마다 방문 요청·예약을 계속 쌓으므로, 전략 간 비교는 같은 조건(DB 초기화 후)에서 하는 것이 공정합니다.
- 결과는 요약의 `http_req_duration{name:...}` 줄(p95)과 `http_reqs{name:confirm}` 줄(초당 처리량)을 봅니다.

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/caregivers` | 요양보호사 등록 |
| GET | `/api/caregivers/{id}` | 요양보호사 조회 |
| POST | `/api/caregivers/{id}/availabilities` | 근무 가능 시간 등록 |
| POST | `/api/recipients` | 수급자 등록 |
| GET | `/api/recipients/{id}` | 수급자 조회 |
| POST | `/api/visit-requests` | 방문 요청 등록 |
| GET | `/api/visit-requests/{id}` | 방문 요청 조회 |
| GET | `/api/visit-requests/{id}/recommendations?size=3` | 요양보호사 추천 (상위 N명, 최대 10명) |
| POST | `/api/visit-requests/{id}/reservations` | 예약 확정 (body: `{"caregiverId": 1}`) |
| DELETE | `/api/reservations/{id}` | 예약 취소 (상태만 `CANCELED`, 요청은 다시 배정 대기) |
| POST | `/api/visit-request-groups` | 묶음 신청 등록 (같은 시간대로 여러 날짜, 최대 14일) |
| GET | `/api/visit-request-groups/{id}` | 묶음 신청 조회 (날짜별 하위 요청 포함) |
| GET | `/api/visit-request-groups/{id}/recommendations?size=3` | 모든 날짜에 갈 수 있는 요양보호사 추천 |
| POST | `/api/visit-request-groups/{id}/reservations` | 묶음 일괄 확정 (body: `{"caregiverId": 1}`, 한 날짜라도 안 되면 전부 실패) |
| DELETE | `/api/visit-request-groups/{id}/reservations` | 묶음 일괄 취소 |

에러 응답은 모두 같은 형식입니다.
```json
{ "code": "RESERVATION_CONFLICT", "message": "이미 예약된 시간대입니다." }
```

### 추천 방식
1. 1차 후보: 근무 중(`ACTIVE`)이고 수급자 선호 성별에 맞는 보호사 (DB 조건)
2. 필터: 방문 시간이 근무 가능 시간 안에 있는지, 그날 겹치는 예약(취소 제외)이 없는지
3. 점수 100점: 거리 50점(25km 이상 0점) + 근무량 30점(최근 30일 완료 20회 이상 0점) + 연속 담당 20점(같은 수급자 1회당 5점)
4. 정렬: 총점 → 거리 → 근무량 → id

필터(`CandidateFilter`)와 점수 항목(`ScorePolicy`)은 구현체로 나뉘어 있어서, 항목을 추가해도 서비스 코드는 바뀌지 않습니다.

### 예약 확정 방식
1. 설정한 락 전략으로 잠금 (기본: 비관적 락, (보호사, 날짜) 잠금 행 → 같은 보호사라도 다른 날짜 확정은 기다리지 않음)
2. 추천 때와 같은 필터로 다시 검증 (근무 상태, 성별, 근무 가능 시간, 겹치는 예약)
3. 예약 저장 + 방문 요청 `ASSIGNED`
4. 같은 시작 시간 중복은 DB 유니크 제약이 한 번 더 막음

묶음 신청은 같은 흐름을 날짜 목록으로 넓혔습니다. 추천은 모든 날짜의 필터를 통과한 보호사만 남기고(쿼리는 날짜 수와 상관없이 6회), 확정은 트랜잭션 하나에서 보호사를 한 번 잠그고 모든 날짜를 다시 검증한 뒤 한꺼번에 저장합니다. 한 날짜라도 실패하면 전부 롤백됩니다. ([docs/oncare-review.md](docs/oncare-review.md) 4절)

## 트러블슈팅 ① 동시 확정 시 중복 배정

**상황** 온케어의 배정 코드는 "요청 상태 확인 → 일정 확인 → 근무 기록 저장" 순서였고, 사이에 락이 없었습니다. 두 요청이 동시에 들어오면 둘 다 "겹치는 일정 없음"을 보고 저장할 수 있습니다.

**1차 대응과 한계** 30분 단위 시간 규칙을 두고 `(보호사, 날짜, 시작 시간)` 유니크 제약을 걸었습니다. 시작 시간이 같은 중복은 막지만, 10:00~12:00과 10:30~12:30처럼 시작 시간이 다른 겹침은 막지 못합니다. 동시 요청 100건으로 재현하니 4~5건이 들어가 겹친 예약이 6~10쌍 생겼습니다. (스레드 실행 순서에 따라 실행마다 달라짐, 시작 시간이 5종류라 최대 5건)

**대안 비교** 같은 보호사에게 확정 요청 100건을 동시에 보냄 (MySQL 8 + Redis 7, CI 컨테이너, 스레드 32개. H2에서도 같은 결과)

| 방법 | 같은 시작 시간 100건 | 시작 시간 다른 겹침 100건 | 특징 |
|---|---|---|---|
| A. 유니크 제약만 | 1건 성공 | 4~5건 성공 (겹침 6~10쌍) | 가장 빠르고 단순하지만 겹침을 못 막음 |
| B. 비관적 락 (`SELECT ... FOR UPDATE`, (보호사, 날짜) 잠금 행) | 1건 | 1건 | 같은 보호사·같은 날짜 요청만 줄 세움. 기다리는 동안 DB 커넥션 점유 |
| B'. 비관적 락 (보호사 행, 범위 축소 전 · 비교용 `pessimistic-caregiver`) | 1건 | 1건 | 같은 보호사면 날짜가 달라도 줄 섬 |
| C. 낙관적 락 (`@Version` + 재시도) | 1건 | 1건 | 충돌이 적으면 유리. 충돌이 잦으면 재시도 비용 |
| D. Redis 분산 락 (Redisson) | 1건 | 1건 | 서버가 여러 대여도 동작. Redis 운영 부담 추가 |

방법별 소요 시간은 테스트 출력의 `[동시성]` 줄에 남지만, H2 메모리 DB 값이라 비교 근거로 쓰지 않습니다. 처리량 비교는 k6(`k6/reservation.js`)로 MySQL에서 잽니다.

**MySQL에서 발견한 문제: 비관적 락을 써도 겹침이 들어감** 처음에는 H2에서만 테스트해서 B가 통과했는데, CI의 MySQL에서는 B도 겹친 예약이 들어갔습니다. 원인은 격리 수준 차이였습니다. MySQL 기본(REPEATABLE READ)은 트랜잭션의 첫 SELECT 시점에 읽기 스냅샷을 고정합니다. 확정 코드가 방문 요청을 먼저 읽고 나서 보호사 락을 기다렸기 때문에, 락을 얻은 뒤의 겹침 검사가 옛 스냅샷을 읽어 앞사람이 방금 커밋한 예약을 못 봤습니다. H2 기본(READ COMMITTED)에서는 드러나지 않았습니다. 확정 트랜잭션을 `READ COMMITTED`로 명시해서 고쳤고, H2 테스트도 REPEATABLE READ로 돌게 바꿔 같은 실수를 로컬에서 잡을 수 있게 했습니다. (수정 전 H2·REPEATABLE READ 재현: B 5건 성공, 겹침 10쌍 → 수정 후 1건)

**선택** 기본값은 B(비관적 락) + A(유니크 제약, 마지막 안전장치)입니다. 서버 1대 기준으로 추가 인프라 없이 겹침까지 막고, 재시도 로직이 필요 없습니다. 서버를 늘리면 D로 바꿀 수 있게 `reservation.lock.strategy` 설정 하나로 전략을 고르게 했습니다.

**락 범위 축소** 처음 B는 보호사 행을 잠가서, 같은 보호사의 다른 날짜 확정끼리도 기다렸습니다. 겹침은 같은 날짜 안에서만 생기므로 `(보호사, 날짜)`마다 잠금 전용 행(`caregiver_day_lock`)을 두고 그 행을 잠그게 바꿨습니다.
- 예약 행 대신 따로 둔 이유: 예약이 0건인 날짜도 잠글 수 있어야 합니다. 확정 트랜잭션은 READ COMMITTED라 갭 락이 없어서, 없는 행을 `FOR UPDATE`로 읽으면 아무것도 잠기지 않습니다.
- 잠금 행은 확정 트랜잭션 **밖**에서 미리 만듭니다. 안에서 새 트랜잭션으로 만들면 요청 하나가 커넥션을 2개 쥐어, 동시 요청이 풀보다 많을 때 모두 멈춥니다.
- 묶음은 날짜 오름차순으로 한 행씩 잠가 묶음끼리의 데드락을 막습니다.
- 확인: `LockScopeTest`가 한 스레드에 D1 락을 쥐게 하고, 같은 보호사의 D2 확정은 바로 끝나고 D1 확정은 기다리는지 봅니다. 예전 방식(`pessimistic-caregiver`)은 D2도 기다립니다. 처리량 차이는 k6로 잴 예정입니다.

**함께 막은 것**
- 같은 요청을 서로 다른 보호사로 동시에 배정: 보호사 락으로는 서로 안 막혀서 `VisitRequest`에 `@Version`을 두었습니다. 10건 동시 → 1건 성공. (H2에서는 나머지가 버전 충돌로, MySQL에서는 대부분 락 획득 실패로 거절됨. 둘 다 409 `CONCURRENT_MODIFICATION`)
- 1주차 한계였던 "취소된 예약이 슬롯을 차지함": 유니크 제약에 `active_slot` 칸을 넣고, 취소하면 NULL로 바꿉니다. 유니크 인덱스는 NULL끼리 같은 값으로 보지 않아서 취소된 슬롯에 다시 예약할 수 있습니다.

## 트러블슈팅 ② 추천 API의 N+1

**상황** 온케어 추천은 보호사마다 근무 가능 시간·그날 일정·근무 기록 전체를 따로 조회하고, 근무 기록마다 방문 요청을 지연 로딩했습니다. 쿼리 수가 `4 + 5N`(N = 후보 수)으로 늘어납니다.

**개선** 후보 전체의 데이터를 `IN` 조건과 `GROUP BY` 집계로 종류별로 한 번씩 가져오고, 보호사별로 나누는 일은 메모리(`groupingBy`)에서 합니다. 필터와 점수는 DB를 모르는 객체라서 단위 테스트가 쉬워졌습니다.

**결과** 후보 10명 54회 → 6회, 100명 504회 → 6회. 후보 수와 상관없이 6회로 고정됩니다. 조회 방식만 바꿨으므로 두 방식의 추천 결과가 같은지도 테스트에서 확인합니다.

## 설계 메모

- **시간은 30분 단위만 허용** — 유니크 제약이 같은 슬롯을 같은 값으로 인식하게 하기 위한 단순화입니다. 겹침 검사는 락 안에서 따로 합니다.
- **삭제 대신 상태 변경** — 보호사 `INACTIVE`, 요청·예약 `CANCELED`. 지난 기록을 보존합니다.
- **좌표는 등록 시 확정** — 외부 지도 API 없이 추천·성능 실험에 집중합니다.
- **"오늘"은 `Clock` 빈으로 주입** — 테스트에서 날짜를 고정할 수 있게 했습니다.

## 한계와 남은 일

- k6 스크립트(`k6/`)는 준비했지만 아직 실행해서 수치를 기록하지 않았습니다. 배포(EC2)도 하지 않았습니다.
- 비관적 락은 (보호사, 날짜) 단위로 줄였지만, 확정 1건마다 잠금 행 확인 쿼리가 2~3개 늘어납니다. 낙관적 락(C)은 아직 보호사 단위입니다.
- 묶음 신청은 모든 날짜가 같은 시간대이고 같은 보호사일 때만 지원합니다. 날짜별 시간대, 날짜별로 다른 보호사 배정은 하지 않습니다.

## 문서
- [docs/oncare-review.md](docs/oncare-review.md) — 온케어 코드 검토: 가져온 것, 고친 것, 측정
- [docs/interview-notes.md](docs/interview-notes.md) — 면접 대비 질문과 답
