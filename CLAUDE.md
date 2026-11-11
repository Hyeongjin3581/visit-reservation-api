# CLAUDE.md

방문 일정 예약·배정 API (Java 21 · Spring Boot 4.1 · JPA · MySQL 8 · Redis/Redisson · H2 테스트 · Gradle).
도메인 패키지: `com.hyeongjin.visit.{caregiver, recipient, request, recommendation, reservation, common}`
각 도메인은 `controller / domain / dto / repository / service` 레이어를 따른다.

## 작업 방식: 리서치 → 계획 → 승인 → 구현

**내가 plan.md를 검토하고 승인하기 전까지 코드를 수정하지 않는다.** (오타·한 줄 수정처럼 단순한 작업은 예외)

1. **리서치** — 관련 코드를 깊이, 매우 상세히 읽고 `docs/tasks/<YYYY-MM-DD>-<기능명>/research.md`에 기록한다. 채팅 요약으로 대신하지 않는다.
2. **계획** — 실제 소스를 읽은 뒤 같은 폴더의 `plan.md`에 작성한다. 접근 방식과 이유, 수정 파일 경로, 코드 스니펫, 트레이드오프, 제외 범위, TODO를 포함한다.
3. **메모 반영** — plan.md 안의 `NOTE:` 메모를 모두 반영해 문서를 고치고, 반영한 메모는 지운다. **"구현해"라는 명시적 지시가 있기 전엔 구현하지 않는다.**
4. **구현** — 승인된 plan.md대로만 구현한다. 끝낸 TODO는 plan.md에서 `[x]`로 체크하고, 모든 단계가 끝날 때까지 멈추지 않는다. 계획에 없는 결정이 필요하면 멈추고 묻는다.
5. 방향이 틀렸다고 하면 그 위에 덧대지 말고, 되돌린 뒤 좁힌 범위로 다시 한다.

템플릿: `docs/tasks/_template/research.md`, `docs/tasks/_template/plan.md`
커맨드: `/research`, `/write-plan`, `/apply-notes`, `/implement`

## 코드 규칙
- 기존 레이어 구조와 공통 예외 처리(`common/exception`)를 따른다. 이미 있는 서비스·리포지토리 로직을 중복해서 만들지 않는다.
- 엔티티·스키마 변경은 `ddl-auto: update`가 제약을 지우지 않는다는 점을 plan.md에 적는다.
- 쿼리 수(N+1)와 동시성(락 전략: unique / pessimistic / optimistic / redisson)에 영향을 주는 변경은 plan.md에 영향과 검증 방법을 적는다.

## 검증
- 단계마다 `./gradlew compileJava compileTestJava`, 마무리에 `./gradlew test`를 실행한다.
- 동시성·성능 관련 변경은 `./gradlew mysqlTest`(DB_PASSWORD 필요) 대상인지 plan.md에 표시한다.
