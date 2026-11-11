---
description: 승인된 plan.md를 끝까지 구현 (단계마다 체크, 컴파일·테스트 확인)
argument-hint: [plan.md 경로 — 생략하면 가장 최근 작업 폴더]
---

대상 계획: $ARGUMENTS (비어 있으면 `docs/tasks/` 아래 가장 최근 폴더의 plan.md)

plan.md대로 전부 구현해.
- 작업이나 단계를 끝낼 때마다 plan.md의 TODO를 `[x]`로 표시해.
- 모든 작업과 단계가 끝날 때까지 멈추지 마.
- 단계마다 `./gradlew compileJava compileTestJava`를 실행해서 새로운 문제를 만들지 마. 마지막에 `./gradlew test`를 실행해.
- raw 타입, 불필요한 `Object` 캐스팅, 예외 삼키기를 하지 마.
- 계획에 없는 설계 결정이 필요하면 임의로 정하지 말고 멈춰서 물어봐.
