import { useState } from 'react'
import { api } from './api'
import type { Task } from './api'
import { StageDots } from './StageDots'
import { StatusBadge } from './StatusBadge'
import { STAGE_LABEL, ago, attentionText, currentStage, groupOf } from './taskView'
import { usePoll } from './usePoll'

function TaskRow({ task, note }: { task: Task; note?: string }) {
  const stage = currentStage(task)
  return (
    <li>
      <a href={`#/tasks/${task.id}`} className="task-row">
        <div className="task-head">
          <strong>{task.feature}</strong>
          <small>{task.projectId} · {ago(task.createdAt)}</small>
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

export function Dashboard() {
  const projects = usePoll(api.projects, 5000)
  const tasks = usePoll(api.tasks, 3000)
  const settings = usePoll(api.settings, 30000)
  const [projectId, setProjectId] = useState('')
  const [feature, setFeature] = useState('')
  const [requirement, setRequirement] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const selected = projectId || projects.data?.find((p) => p.online)?.id || projects.data?.[0]?.id || ''
  const all = tasks.data ?? []
  const attention = all.filter((t) => groupOf(t) === 'attention')
  const running = all.filter((t) => groupOf(t) === 'running')
  const done = all.filter((t) => groupOf(t) === 'done')
  const waiting = attention.filter((t) => t.status !== 'BLOCKED').length
  const blocked = attention.length - waiting

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const t = await api.createTask(selected, feature.trim(), requirement.trim())
      location.hash = `#/tasks/${t.id}`
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

      <section className="card">
        <h2>새 요구사항</h2>
        <form className="col" onSubmit={submit}>
          <div className="row">
            <select value={selected} onChange={(e) => setProjectId(e.target.value)} aria-label="프로젝트">
              {projects.data?.map((p) => (
                <option key={p.id} value={p.id}>{p.name}{p.online ? '' : ' (Runner 오프라인)'}</option>
              ))}
            </select>
            <input placeholder="기능 이름 (비우면 자동)" value={feature} onChange={(e) => setFeature(e.target.value)} />
          </div>
          <textarea rows={4} placeholder="요구사항을 자유롭게 적으세요" value={requirement} onChange={(e) => setRequirement(e.target.value)} />
          {error && <p className="err">{error}</p>}
          <button disabled={busy || !selected || !requirement.trim()}>분석 시작</button>
        </form>
      </section>

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

      <section>
        <h2>프로젝트</h2>
        {projects.data?.length ? (
          <ul className="plain projects">
            {projects.data.map((p) => {
              const active = all.filter((t) => t.projectId === p.id && t.status === 'ACTIVE').length
              return (
                <li key={p.id} className="project">
                  <span className={`presence ${p.online ? 'on' : 'off'}`} aria-hidden />
                  <div>
                    <strong>{p.name}</strong>
                    <small>
                      {p.online ? 'Runner 연결됨' : `Runner 오프라인 · 마지막 ${ago(p.lastSeen)}`}
                      {active > 0 && ` · 진행 ${active}건`}
                    </small>
                  </div>
                </li>
              )
            })}
          </ul>
        ) : (
          <p className="muted">
            Runner가 연결되면 프로젝트가 표시됩니다. <code>ilsim-runner add &lt;id&gt; &lt;path&gt;</code> 후 <code>ilsim-runner run</code>
          </p>
        )}
      </section>
    </>
  )
}
