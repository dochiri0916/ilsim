import { useState } from 'react'
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

/** 선택한 프로젝트의 작업만 보여 주고, 새 요구사항도 그 프로젝트에 만든다. */
export function Dashboard({ project }: { project: Project }) {
  const tasks = usePoll(api.tasks, 3000)
  const settings = usePoll(api.settings, 30000)
  const [requirement, setRequirement] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const all = (tasks.data ?? []).filter((t) => t.projectId === project.id)
  const attention = all.filter((t) => groupOf(t) === 'attention')
  const running = all.filter((t) => groupOf(t) === 'running')
  const done = all.filter((t) => groupOf(t) === 'done')
  const waiting = attention.filter((t) => t.status !== 'BLOCKED').length
  const blocked = attention.length - waiting

  async function submit(e?: React.FormEvent) {
    e?.preventDefault()
    if (busy || !requirement.trim()) return
    setBusy(true)
    setError(null)
    try {
      // 입력할 때마다 새 작업(새 Claude 세션)을 만들고, 바로 다음 요청을 입력할 수 있게 화면에 남는다.
      await api.createTask(project.id, '', requirement.trim())
      setRequirement('')
      tasks.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const jevMissing = settings.data && !settings.data.jev.configured

  return (
    <>
      {jevMissing && (
        <p className="notice">
          JEV 키가 등록되지 않아 모델을 규칙으로 고릅니다. <a href="#/settings">설정에서 등록</a>
        </p>
      )}

      <form className="card command" onSubmit={submit}>
        <textarea rows={3} placeholder="무엇을 할까요? (Enter로 새 작업, Shift+Enter로 줄바꿈)" value={requirement} aria-label="명령"
          onChange={(e) => setRequirement(e.target.value)}
          onKeyDown={(e) => {
            // 한글 조합 중의 Enter 는 글자 확정이라 보내지 않는다.
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); submit() }
          }} />
        {error && <p className="err">{error}</p>}
        <button disabled={busy || !requirement.trim()}>확인</button>
      </form>

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
          <p className="muted">{all.length ? '진행 중인 작업이 없습니다' : '아직 작업이 없습니다. 위에서 요구사항을 입력하면 시작됩니다'}</p>
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
