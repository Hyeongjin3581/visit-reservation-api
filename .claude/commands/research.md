---
description: 대상 코드를 깊이 분석해 docs/tasks/<날짜>-<기능명>/research.md 작성 (코드 수정 금지)
argument-hint: <기능명 또는 경로> [조사 목적]
---

대상: $ARGUMENTS

이 대상과 관련된 코드를 깊이 읽고, 어떻게 동작하는지 깊이 이해하고, 모든 세부 사항을 파악해.
함수 시그니처만 훑지 말고 호출 흐름, 트랜잭션·락 경계, 쿼리, 예외 처리, 테스트까지 따라가.

끝나면 `docs/tasks/_template/research.md` 형식에 맞춰 `docs/tasks/<오늘 날짜 YYYY-MM-DD>-<짧은 기능명>/research.md`에 매우 상세한 보고서를 작성해.
파일:라인 근거를 달고, 재사용할 수 있는 기존 로직과 건드리면 깨지는 곳을 반드시 적어.

코드는 수정하지 마.
