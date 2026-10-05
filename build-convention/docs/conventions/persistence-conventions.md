# 영속성 컨벤션

- JPA Entity는 `adapter.out.persistence`에 두고, Mapper에서 도메인·VO와 영속성 타입을 변환한다.
- 내부 PK는 자동 생성하는 `Long id`를 사용하며, 내부 키와 JPA `version`은 외부에 노출하지 않는다.
- 애그리거트와 영속 행의 자체 UUID는 `publicId` / `public_id`로 저장하고, 필수·36자리·유니크 제약을 적용한다.
- 부모·외부 참조는 `cartId`, `orderId`, `memberId` 등의 이름을 유지한다. 스냅샷·Inbox·Outbox의 키도 목적에 맞는 이름을 사용한다.
- JPA 연관 관계(`@OneToMany`·`@ManyToOne`)와 `@ElementCollection`을 사용하지 않는다. 자식 행은 별도 Entity로 두고 부모의 `publicId`를 `{parent}Id` 문자열 컬럼으로 참조하며, 목록 조회는 어댑터에서 `findBy{Parent}IdIn`으로 일괄 조회해 묶는다.
- 상태는 문자열로 저장하고 Mapper에서 도메인 enum과 변환한다.
- 이름 있는 유니크 제약은 별도 상수 없이 `@Table`의 `@UniqueConstraint`에 직접 선언한다. 예: `uk_members_email`.
- 기술 예외의 번역과 제약별 충돌 처리는 [예외 컨벤션](exception-conventions.md)을 따른다. 실패한 트랜잭션에서 재시도하지 않는다.
- 저장·삭제 오류가 어댑터 안에서 번역되도록 필요한 flush를 수행한다. 기본·safe 어댑터의 제약과 예외 계약은 동일하게 유지한다.

기존 DB의 컬럼·제약·타입 변경 절차는 [DB 변경 가이드](database-migration-conventions.md)를 따른다.

세부 규칙은 [도메인](domain-conventions.md), [Querydsl](querydsl-conventions.md) 컨벤션을 따른다.
