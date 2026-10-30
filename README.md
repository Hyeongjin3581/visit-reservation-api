# 방문 일정 예약·배정 API

요양보호사 근무 가능 시간과 방문 요청을 받아, 조건에 맞는 보호사를 추천하고 **동시 요청에도 중복 배정 없이** 예약을 확정하는 백엔드 API입니다.

> 1인 개인 프로젝트 · 팀 프로젝트 '온케어'에서 맡았던 추천·배정 로직을 엔지니어링 관점에서 다시 설계하고, 개선 결과를 수치로 기록합니다.

## 진행 상황

| 주차 | 내용 | 상태 |
|---|---|---|
| 1주 | 프로젝트 구성, 엔티티·ERD, 등록 API, 공통 예외 처리, 테스트 | ✅ 뼈대 완료 |
| 2주 | 추천 API (필터·점수 정책) + N+1 측정·개선 | ⏳ |
| 3주 | 예약 확정 + 동시성 제어 4가지 비교 (유니크 제약 / 비관적 락 / 낙관적 락 / Redis 분산 락) | ⏳ |
| 4주 | CI, 배포, 문서 정리 | ⏳ |

## 기술 스택

Java 21 · Spring Boot 4.1 · Spring Data JPA · MySQL 8 · H2(테스트) · JUnit 5 · Gradle
예정: Redis(Redisson) · Testcontainers · k6 · Docker Compose · GitHub Actions

## 실행 방법

### 1. DB 준비 (둘 중 하나)
- **로컬 MySQL**: 설치된 MySQL 8 사용. `visit_db` 는 처음 실행할 때 자동 생성됩니다.
- **Docker**: `docker compose up -d` (Docker Desktop 필요, Redis 도 같이 실행)

### 2. 접속 정보 설정
기본값은 `localhost:3306`, `root`, 비밀번호 없음입니다. 다르면 환경 변수로 넣습니다 (`.env.example` 참고).

```powershell
$env:DB_PASSWORD="내 MySQL 비밀번호"
./gradlew bootRun
```

### 3. 테스트
```bash
./gradlew test
```
테스트는 H2(MySQL 모드)로 돌아가서 MySQL 없이도 실행됩니다.

## API (1주차)

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/caregivers` | 요양보호사 등록 |
| GET | `/api/caregivers/{id}` | 요양보호사 조회 |
| POST | `/api/caregivers/{id}/availabilities` | 근무 가능 시간 등록 |
| POST | `/api/recipients` | 수급자 등록 |
| GET | `/api/recipients/{id}` | 수급자 조회 |
| POST | `/api/visit-requests` | 방문 요청 등록 |
| GET | `/api/visit-requests/{id}` | 방문 요청 조회 |

에러 응답은 모두 같은 형식입니다.
```json
{ "code": "CAREGIVER_NOT_FOUND", "message": "요양보호사를 찾을 수 없습니다." }
```

## 설계 메모

- **시간은 30분 단위만 허용** — 예약 중복을 `(caregiver_id, visit_date, start_time)` 유니크 제약으로 막기 위한 단순화입니다.
- **삭제 대신 상태 변경** — 보호사 `INACTIVE`, 요청·예약 `CANCELED`. 지난 기록을 보존합니다 (온케어 소프트 삭제 경험 반영).
- **좌표는 등록 시 확정** — 외부 지도 API 없이 추천·성능 실험에 집중합니다.

### 알려진 한계 (3주차에 결정)
- 취소된 예약도 유니크 제약의 슬롯을 차지해서, 같은 슬롯에 다시 예약할 수 없습니다.
- 슬롯 단위로만 막기 때문에 "시작 시간은 다르지만 겹치는" 예약은 아직 막지 못합니다.

## 측정 기록 (실제 수치로 채울 예정)

| 실험 | 조건 | 개선 전 | 개선 후 |
|---|---|---|---|
| 중복 배정 | 동시 요청 100건 | | |
| 추천 쿼리 수 | 후보 100명 | | |
| 추천 응답 p95 | 보호사 1,000명 | | |
