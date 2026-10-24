import { api } from './api'
import type { Project, Task } from './api'
import { StageDots } from './StageDots'
import { StatusBadge } from './StatusBadge'
import { STAGE_LABEL, ago, attentionText, currentStage, groupOf, taskTitle, usd } from './taskView'
import { usePoll } from './usePoll'

function TaskRow({ task, note }: { task: Task; note?: string }) {
  const stage = currentStage(task)
  return (
    <li>
      <a href={`#/tasks/${task.id}`} className="task-row">
        <div className="task-head">
          <strong title={task.feature}>{taskTitle(task)}</strong>
          <small>{ago(task.createdAt)}{task.costUsd ? ` · ${usd(task.costUsd)}` : ''}</small>
        </div>
        <StageDots task={task} />
        <div className="task-note">
          {note ?? (stage ? `${STAGE_LABEL[stage.stage]}${stage.attempt > 1 ? ` · ${stage.attempt}회차` : ''}` : '')}
          {stage && <StatusBadge value={stage.status} />}
        </div>
      </a>
    </li>
  )
}

function Stat({ label, value, tone }: { label: string; value: number; tone?: 'warn' | 'bad' | 'ok' }) {
  return (
    <div className={`stat ${tone ?? ''} ${value === 0 ? 'zero' : ''}`}>
      <span className="stat-value">{value}</span>
      <span className="stat-label">{label}</span>
    </div>
  )
}

export function Dashboard({ project }: { project: Project }) {
  const tasks = usePoll(api.tasks, 3000)
  const settings = usePoll(api.settings, 30000)
  const all = (tasks.data ?? []).filter((t) => t.projectId === project.id)
  const attention = all.filter((t) => groupOf(t) === 'attention')
  const running = all.filter((t) => groupOf(t) === 'running')
  const done = all.filter((t) => groupOf(t) === 'done')
  const waiting = attention.filter((t) => t.status !== 'BLOCKED').length
  const blocked = attention.length - waiting

  const jevMissing = settings.data && !settings.data.jev.configured

  return (
    <>
      {jevMissing && (
        <p className="notice">
          JEV 키가 등록되지 않아 JEV 판정 없이 검증합니다. <a href="#/settings">설정에서 등록</a>
        </p>
      )}

      <section className="stats">
        <Stat label="승인 대기" value={waiting} tone="warn" />
        <Stat label="막힘" value={blocked} tone="bad" />
        <Stat label="진행 중" value={running.length} />
        <Stat label="완료" value={done.length} tone="ok" />
      </section>

      {attention.length > 0 && (
        <section>
          <h2>지금 확인할 작업</h2>
          <ul className="plain tasks">
            {attention.map((t) => <TaskRow key={t.id} task={t} note={attentionText(t)} />)}
          </ul>
        </section>
      )}

      <section>
        <h2>진행 중</h2>
        {running.length ? (
          <ul className="plain tasks">{running.map((t) => <TaskRow key={t.id} task={t} />)}</ul>
        ) : (
          <p className="muted">{all.length ? '진행 중인 작업이 없습니다' : '아직 작업이 없습니다. Claude 앱에서 요청하면 시작됩니다'}</p>
        )}
      </section>

      {done.length > 0 && (
        <section>
          <h2>최근 완료</h2>
          <ul className="plain tasks">{done.slice(0, 5).map((t) => <TaskRow key={t.id} task={t} note="모든 단계 통과" />)}</ul>
        </section>
      )}

    </>
  )
}
