import type { Task } from './api'
import { STAGE_LABEL, STAGE_SHORT } from './taskView'

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
