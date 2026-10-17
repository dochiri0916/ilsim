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
