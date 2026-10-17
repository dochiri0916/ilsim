const LABEL: Record<string, string> = {
  PENDING: '대기', RUNNING: '실행 중', WAITING_APPROVAL: '승인 대기', PASSED: '통과', FAILED: '실패',
  ACTIVE: '진행 중', COMPLETED: '완료', BLOCKED: '차단', RED: 'RED(실패 확인)', UNKNOWN: '미확인', SKIPPED: '건너뜀', LOW: '낮음', CONCERN: '지적 있음',
}

export function StatusBadge({ value }: { value: string }) {
  return <span className={`badge ${value.toLowerCase()}`}>{LABEL[value] ?? value}</span>
}
