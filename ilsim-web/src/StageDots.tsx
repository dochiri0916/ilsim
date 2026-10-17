import type { Task } from './api'
import { STAGE_LABEL, STAGE_SHORT } from './taskView'

/** 네 단계의 진행 상황을 한 줄로 보여 준다. 색: 통과=초록, 실행 중=파랑, 승인 대기=주황, 실패=빨강. */
export function StageDots({ task }: { task: Task }) {
  return (
    <ol className="dots" aria-label="단계 진행">
      {task.stages.map((s) => (
        <li key={s.stage} className={`dot ${s.status.toLowerCase()}`} title={`${STAGE_LABEL[s.stage]}: ${s.status}${s.attempt > 1 ? ` (${s.attempt}회차)` : ''}`}>
          <span className="bar" />
          <small>{STAGE_SHORT[s.stage]}</small>
        </li>
      ))}
    </ol>
  )
}
