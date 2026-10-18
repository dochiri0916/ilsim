/** 규칙을 승인(적용)했을 때의 장단점. 규칙 종류(kind)별 고정 설명이다. */
export interface Tradeoff { pro: string; con: string }

const RULES: Record<string, Tradeoff> = {
  'no-cycles': { pro: '모듈 사이 순환 의존을 막아 분리·테스트·빌드가 쉬워집니다', con: '서로 부르는 구조를 쓰려면 인터페이스나 이벤트로 끊어야 합니다' },
  'leaf-unit': { pro: '핵심 계층(예: domain)이 다른 계층에 끌려가지 않아 변경에 강합니다', con: '편의상 바깥 계층을 직접 쓰던 코드는 옮기거나 포트를 둬야 합니다' },
  'forbid-edge': { pro: '정해진 의존 방향을 지켜 계층이 무너지지 않습니다', con: '급한 수정에서도 우회 경로를 만들어야 해 코드가 조금 늘어납니다' },
  'absent-edge': { pro: '지금 없는 의존이 새로 생기는 것을 막아 구조를 그대로 유지합니다', con: '앞으로 필요한 정당한 의존도 막혀 규칙을 다시 풀어야 할 수 있습니다' },
  'unit-edges': { pro: '계층 간 허용 의존을 명시해 구조 위반을 바로 잡아냅니다', con: '새 계층·패키지를 추가할 때마다 규칙을 갱신해야 합니다' },
  'slice-edges': { pro: '기능(slice)끼리 엉키지 않아 기능 단위로 고치고 지우기 쉽습니다', con: '기능 간 공통 코드는 shared 로 따로 빼야 합니다' },
  'cross-slice-targets': { pro: '다른 기능은 정해진 진입점으로만 쓰게 해 내부 구현을 숨깁니다', con: '진입점에 없는 것을 쓰려면 공개 API 를 먼저 늘려야 합니다' },
  'shared-independence': { pro: '공통 모듈이 특정 기능에 묶이지 않아 재사용이 쉽습니다', con: '기능별 예외 처리를 공통 모듈에 넣을 수 없습니다' },
  'pure-layer': { pro: '계층이 프레임워크·인프라에 물들지 않아 단위 테스트가 쉽습니다', con: '어노테이션 하나로 끝나던 일을 어댑터로 감싸야 합니다' },
  'layer-externals': { pro: '계층이 쓰는 외부 라이브러리를 제한해 기술 의존이 퍼지지 않습니다', con: '새 라이브러리를 쓸 때마다 허용 목록을 갱신해야 합니다' },
  'confinement': { pro: '특정 기술(JPA·Spring 등)을 정해진 패키지 안에 가둬 교체 비용을 줄입니다', con: '다른 곳에서 바로 쓰던 편의 API 를 쓸 수 없습니다' },
  'annotation-confinement': { pro: '어노테이션을 정해진 계층에서만 쓰게 해 책임이 섞이지 않습니다', con: '경계 밖에서 필요해지면 구조를 바꾸거나 규칙을 풀어야 합니다' },
  'annotation-suffix': { pro: '역할(Controller·Service 등)이 이름에 드러나 코드를 찾기 쉽습니다', con: '기존 이름과 다른 클래스는 이름을 바꿔야 합니다' },
  'file-matches-type': { pro: '파일 이름으로 타입을 바로 찾을 수 있습니다', con: '한 파일에 여러 타입을 두는 방식은 쓸 수 없습니다' },
  'test-class-suffix': { pro: '테스트 클래스가 일관되게 이름 지어져 빌드 도구가 놓치지 않습니다', con: '다른 접미사를 쓰던 테스트는 이름을 바꿔야 합니다' },
  'test-method-style': { pro: '테스트 이름이 한 형식이라 실패 보고서를 읽기 쉽습니다', con: '형식이 다른 기존 테스트는 고쳐야 합니다' },
  'indent-style': { pro: '들여쓰기가 같아 diff 가 깔끔하고 리뷰가 쉽습니다', con: '편집기 설정이 다르면 자주 위반이 납니다' },
  'max-line-length': { pro: '좁은 화면·나란히 보기에서도 코드가 잘 읽힙니다', con: '긴 문자열·시그니처는 억지로 줄을 나눠야 합니다' },
  'no-wildcard-import': { pro: '어떤 타입을 쓰는지 import 만 보고 알 수 있고 이름 충돌을 막습니다', con: 'import 줄이 길어집니다' },
  'no-debug-output': { pro: 'println 같은 디버그 출력이 운영 코드에 남지 않습니다', con: '간단한 출력이 필요한 CLI·스크립트도 로거를 써야 합니다' },
  'no-field-injection': { pro: '생성자 주입으로 의존이 드러나고 테스트에서 바꿔 끼우기 쉽습니다', con: '필드 주입을 쓰던 클래스는 생성자를 고쳐야 합니다' },
  'no-force-unwrap': { pro: '강제 언래핑(!!)으로 인한 NPE 를 막습니다', con: 'null 이 될 수 없음을 아는 곳에서도 처리 코드를 써야 합니다' },
}

const MEMORY: Record<string, Tradeoff> = {
  FALSE_POSITIVE: { pro: '같은 오탐이 다음 검사부터 숨겨져 잡음이 줄어듭니다', con: '판단이 틀렸다면 진짜 위험도 함께 숨겨집니다' },
  CONFIRMED_RISK: { pro: '확인된 위험을 기억해 다음 작업에서 같은 실수를 경고합니다', con: '코드가 바뀌어도 근거가 사라질 때까지 경고가 계속 남습니다' },
}

const BLOCK = '심각도 BLOCK: 위반하면 작업 단계가 막힙니다'

export function ruleTradeoff(kind: string, severity: string | null | undefined): Tradeoff & { note?: string } {
  const t = RULES[kind] ?? { pro: '추출된 구조·관례를 이후 작업에서도 지키게 합니다', con: '규칙이 실제 의도와 다르면 정당한 변경도 막힙니다' }
  return severity === 'BLOCK' ? { ...t, note: BLOCK } : t
}

export function memoryTradeoff(kind: string): Tradeoff | undefined {
  return MEMORY[kind]
}
