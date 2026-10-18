import type { StageState, Task } from './api'

export const STAGE_LABEL: Record<string, string> = {
  REQUIREMENTS: '요구사항 분석',
  TEST_CASES: '테스트 케이스',
  TEST_CODE: '테스트 코드',
  IMPLEMENTATION: '구현',
}

export const STAGE_SHORT: Record<string, string> = {
  REQUIREMENTS: '요구사항',
  TEST_CASES: '케이스',
  TEST_CODE: '테스트',
  IMPLEMENTATION: '구현',
}

/** 비용(USD)을 보기 좋게. 0 이면 빈 문자열. */
export function usd(v?: number): string {
  if (!v) return ''
  return v < 0.01 ? '<$0.01' : `$${v.toFixed(2)}`
}

/** "claude-opus-5-5" → "opus" 처럼 모델 이름에서 등급만 남긴다. */
export function modelLabel(id: string): string {
  return id.match(/(haiku|sonnet|opus|fable)/)?.[1] ?? id
}

/** 아직 통과하지 못한 첫 단계. 모두 통과했으면 undefined. */
export function currentStage(t: Task): StageState | undefined {
  return t.stages.find((s) => s.status !== 'PASSED')
}

export type Group = 'attention' | 'running' | 'done'

/** 사용자가 지금 해야 할 일이 있는지(승인 대기, 차단), 진행 중인지, 끝났는지. */
export function groupOf(t: Task): Group {
  if (t.status === 'COMPLETED') return 'done'
  if (t.status === 'BLOCKED') return 'attention'
  return currentStage(t)?.status === 'WAITING_APPROVAL' ? 'attention' : 'running'
}

export function attentionText(t: Task): string {
  const s = currentStage(t)
  if (t.status === 'BLOCKED') return `${s ? STAGE_LABEL[s.stage] : ''} 단계가 막혔습니다. 사유를 보고 다시 시도하세요`
  return `${s ? STAGE_LABEL[s.stage] : ''} 결과를 확인하고 승인하세요`
}

export function ago(iso: string | null | undefined): string {
  if (!iso) return '-'
  const sec = Math.max(0, Math.round((Date.now() - new Date(iso).getTime()) / 1000))
  if (sec < 60) return '방금'
  if (sec < 3600) return `${Math.floor(sec / 60)}분 전`
  if (sec < 86400) return `${Math.floor(sec / 3600)}시간 전`
  return `${Math.floor(sec / 86400)}일 전`
}

/**
 * 화면에 보일 작업 이름. 요구사항 문서 첫 줄의 `# 제목`(Claude 가 쓴 요약)을 쓰고,
 * 아직 없으면 지시문 첫 줄을 줄여 쓴다. feature 는 브랜치·경로용 식별자라 이름으로 쓰지 않는다.
 */
export function taskTitle(t: Task): string {
  const doc = Object.entries(t.stages.find((s) => s.stage === 'REQUIREMENTS')?.artifacts ?? {})
    .find(([path]) => path.endsWith('requirements.md'))?.[1]
  const heading = doc?.match(/^#\s+(.+)$/m)?.[1]?.trim()
  if (heading && !/^요구사항(\s*(문서|명세|정의))?$/.test(heading)) return heading.slice(0, 60)
  const line = t.requirement.split('\n').map((l) => l.trim()).find(Boolean) ?? t.feature
  return line.length > 40 ? line.slice(0, 40) + '…' : line
}
