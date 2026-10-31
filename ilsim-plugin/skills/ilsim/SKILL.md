---
name: ilsim
description: 일심이 관리하는 저장소에서 코드·문서를 바꾸는 요청을 처리할 때 쓴다. start_task·next_step·submit_result로 단계(요구사항 → 테스트 케이스 → 테스트 코드 → 구현)를 진행한다.
---

작업 절차는 일심 MCP 서버의 안내와 각 도구가 돌려주는 단계 지시를 따른다. 코드·문서를 바꾸는 요청이면 먼저 `start_task`(진행 중 작업은 `next_step`)를 호출한다.
