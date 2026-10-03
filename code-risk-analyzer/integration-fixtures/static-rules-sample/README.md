# Static Rules Sample

분석기의 정적 규칙 판정(탐지와 오탐 방지)을 검증하는 소스 전용 fixture다. 빌드·실행하지 않는다.
`StaticRulesFixtureTests`가 이 디렉터리를 복사해 scan하고, 파일별 기대 결과를 assertion한다.
각 클래스의 `// EXPECT:` 주석이 기대 결과다. 기대를 바꾸면 테스트도 함께 수정한다.
