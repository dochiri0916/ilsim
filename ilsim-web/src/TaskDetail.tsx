import { useState } from 'react'
import { api, type StageState } from './api'
import { usePoll } from './usePoll'
import { StatusBadge } from './StatusBadge'
import { modelLabel, usd } from './taskView'

const STAGE_LABEL: Record<string, string> = {
  REQUIREMENTS: '요구사항 분석',
  TEST_CASES: '테스트 케이스',
  TEST_CODE: '테스트 코드',
  IMPLEMENTATION: '프로덕트 코드',
}

export function TaskDetail({ id }: { id: string }) {
  const { data, error, reload } = usePoll(() => api.task(id), 2000, [id])
  const [picked, setPicked] = useState<string | null>(null)
  const [feedback, setFeedback] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)

  if (!data) return <p className="muted">{error ?? '불러오는 중…'}</p>
  const { task, events } = data

  const current = task.stages.find((s) => s.status !== 'PASSED') ?? task.stages[task.stages.length - 1]
  const shown: StageState = task.stages.find((s) => s.stage === picked) ?? current

  async function act(fn: () => Promise<void>) {
    setActionError(null)
    try {
      await fn()
      setFeedback('')
      reload()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e))
    }
  }

  const stageEvents = events.filter((e) => e.stage === shown.stage).slice(-150)

  return (
    <>
      <p><a href="#/">← 목록</a></p>
      <h2>{task.feature} <StatusBadge value={task.status} /></h2>
      <p className="muted pre">{task.requirement}</p>
      {task.branch && <p><small>브랜치: <code>{task.branch}</code></small></p>}
      {!!task.costUsd && (
        <p><small>
          Claude 비용 {usd(task.costUsd)}
          {Object.entries(task.costByModel ?? {}).map(([m, c]) => ` · ${modelLabel(m)} ${usd(c)}`)}
        </small></p>
      )}

      <ol className="stepper">
        {task.stages.map((s) => (
          <li key={s.stage} className={s.stage === shown.stage ? 'active' : ''}>
            <button className="link" onClick={() => setPicked(s.stage)}>
              {STAGE_LABEL[s.stage]}
              <StatusBadge value={s.status} />
              {s.attempt > 1 && <small>시도 {s.attempt}</small>}
              {!!s.costUsd && <small>{usd(s.costUsd)}</small>}
            </button>
          </li>
        ))}
      </ol>

      {shown.status === 'WAITING_APPROVAL' && (
        <section className="card block">
          <strong>승인이 필요합니다</strong>
          <small>해시 {shown.artifactHash?.slice(0, 12)}</small>
          <textarea rows={3} placeholder="거절 시 수정 요청 사항" value={feedback} onChange={(e) => setFeedback(e.target.value)} />
          <div className="row">
            <button onClick={() => act(() => api.approve(id))}>승인</button>
            <button className="secondary" disabled={!feedback.trim()} onClick={() => act(() => api.reject(id, feedback))}>거절 후 수정 요청</button>
          </div>
        </section>
      )}

      {task.status === 'BLOCKED' && (
        <section className="card block">
          <strong>작업이 차단되었습니다</strong>
          <button onClick={() => act(() => api.retry(id))}>실패한 단계 다시 시도</button>
        </section>
      )}
      {actionError && <p className="err">{actionError}</p>}

      {shown.error && <pre className="err-box">{shown.error}</pre>}

      {shown.routingLog && shown.routingLog.length > 0 && (
        <details>
          <summary>모델 선택 이력</summary>
          <ul className="plain">{shown.routingLog.map((l, i) => <li key={i}><small>{l}</small></li>)}</ul>
        </details>
      )}

      {Object.keys(shown.validation).length > 0 && (
        <p className="validation">
          {Object.entries(shown.validation).map(([k, v]) => (
            <span key={k}>{k} <StatusBadge value={v} /></span>
          ))}
        </p>
      )}

      {Object.entries(shown.artifacts).map(([path, content]) => (
        <details key={path} open={shown.status === 'WAITING_APPROVAL'}>
          <summary>{path}</summary>
          <pre>{content}</pre>
        </details>
      ))}
      {shown.diff && (
        <details>
          <summary>diff</summary>
          <pre>{shown.diff}</pre>
        </details>
      )}

      <details open={shown.status === 'RUNNING'}>
        <summary>진행 로그 ({stageEvents.length})</summary>
        <ul className="plain log">
          {stageEvents.map((e, i) => (
            <li key={i} className={e.type}>
              <small>{new Date(e.at).toLocaleTimeString()}</small> <span className="t">{e.type}</span>{' '}
              <span className="pre">{e.type === 'result' ? summarizeResult(e.data) : e.data}</span>
            </li>
          ))}
        </ul>
      </details>
    </>
  )
}

function summarizeResult(raw: string): string {
  try {
    const r = JSON.parse(raw)
    const u = r.usage ?? {}
    const tokens = (u.input_tokens ?? 0) + (u.output_tokens ?? 0)
    return `완료 · 비용 $${(r.total_cost_usd ?? 0).toFixed(4)} · 턴 ${r.num_turns ?? '?'} · 토큰 ${tokens}`
  } catch {
    return raw.slice(0, 200)
  }
}
