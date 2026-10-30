# 문서 규칙 강제 회귀 세트

등록 프로젝트 문서에서 뽑은 규칙(문서 규칙 정적 변환 결과)이 그 문서를 어기는 변경을 실제로 막는지 확인한다. `DocEnforcementCorpusTest`가 사례마다 임시 저장소를 만들어 `before/`를 커밋하고 `after/`를 덮어쓴 뒤 제출 정적 검사(`StageGates.quickCheck`)를 돌린다.

사례 하나는 폴더 하나다.

- `case.json`: `stage`, `expect`(`BLOCK` 또는 `PASS`), `note`, `doc`(규칙이 나온 문서 경로), `rules`(`id`·`kind`·`description`·`line`·`params`)
- `doc.md.txt`: 규칙이 나온 문서 원문(사람이 규칙 변환이 맞는지 볼 때 쓴다)
- `before/`, `after/`: 저장소 기준 경로에 `.txt`를 붙인 파일. 일심이 이 저장소를 분석할 때 사례 코드를 소스로 읽지 않게 한다.

`expect: PASS` 사례는 게이트를 느슨하게 만들 수 있으므로 사용자 확인을 거쳐 넣는다.
