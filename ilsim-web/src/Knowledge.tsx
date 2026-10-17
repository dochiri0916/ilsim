import { useEffect, useState } from 'react'
import { api, type Analysis, type MemoryView, type RuleSet, type RuleView } from './api'
import { Dialog } from './Dialog'
import { StatusBadge } from './StatusBadge'
import { usePoll } from './usePoll'

type Tab = 'architecture' | 'convention' | 'memory'
const TAB_LABEL: Record<Tab, string> = { architecture: '아키텍처 규칙', convention: '컨벤션', memory: '학습 메모리' }

function ruleState(r: RuleView): 'approved' | 'rejected' | 'pending' {
  return r.approved ? 'approved' : r.rejected ? 'rejected' : 'pending'
}

const STATE_LABEL = { approved: '승인됨', rejected: '거절됨', pending: '승인 대기' }

function Item({ title, meta, body, state, onApprove, onReject, busy }: {
  title: string; meta: string; body: React.ReactNode; state: 'approved' | 'rejected' | 'pending'
  onApprove: () => void; onReject: () => void; busy: boolean
}) {
  return (
    <li className={`item ${state}`}>
      <div className="item-head">
        <strong>{title}</strong>
        <span className={`tag ${state}`}>{STATE_LABEL[state]}</span>
      </div>
      <small className="muted">{meta}</small>
      <div className="item-body">{body}</div>
      <div className="row actions">
        {state !== 'approved' && <button disabled={busy} onClick={onApprove}>승인</button>}
        {state !== 'rejected' && <button className="secondary" disabled={busy} onClick={onReject}>거절</button>}
      </div>
    </li>
  )
}

const ANALYSIS_LABEL: Record<Analysis['status'], string> = {
  REQUESTED: 'Runner 대기 중…', RUNNING: '분석 중…', DONE: '분석 완료', FAILED: '분석 실패',
}

/** 프로젝트의 규칙과 검증된 학습을 보고 승인·거절한다. 거절한 규칙은 어떤 검사에서도 쓰이지 않는다. */
export function Knowledge() {
  const projects = usePoll(api.projects, 15000)
  const [picked, setPicked] = useState('')
  const [tab, setTab] = useState<Tab>('architecture')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const projectId = picked || projects.data?.[0]?.id || ''
  const knowledge = usePoll(() => (projectId ? api.knowledge(projectId) : Promise.resolve(null)), 8000, [projectId])
  const k = knowledge.data
  const analysis = usePoll(() => (projectId ? api.analysis(projectId) : Promise.resolve(undefined)), 3000, [projectId])
  const a = analysis.data
  const analyzing = a?.status === 'REQUESTED' || a?.status === 'RUNNING'
  const [showReport, setShowReport] = useState(false)

  // 분석이 끝나면 Runner 가 새로 쓴 규칙을 바로 불러온다.
  useEffect(() => { if (a?.finishedAt) knowledge.reload() }, [a?.finishedAt])

  async function run(fn: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await fn()
      knowledge.reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  const pendingRules = (set: RuleSet) => (k?.[set] ?? []).filter((r) => ruleState(r) === 'pending').map((r) => r.id)
  const pendingMemory = (k?.memory ?? []).filter((m) => m.status === 'CANDIDATE').map((m) => m.id)
  const count = (t: Tab) => (t === 'memory' ? pendingMemory.length : pendingRules(t).length)

  const bulk = tab === 'memory' ? pendingMemory : pendingRules(tab)
  const bulkRun = (decision: 'approve' | 'reject') =>
    run(() => (tab === 'memory' ? api.decideMemory(projectId, bulk, decision) : api.decideRules(projectId, tab, bulk, decision)))

  return (
    <>
      <h1 className="page-title">규칙·메모리</h1>
      <div className="row">
        <select value={projectId} onChange={(e) => setPicked(e.target.value)} aria-label="프로젝트">
          {projects.data?.map((p) => <option key={p.id} value={p.id}>{p.name}</option>)}
        </select>
        {projectId && (
          <button disabled={busy || analyzing} onClick={() => run(async () => { await api.analyze(projectId); analysis.reload() })}>
            {analyzing ? '분석 중…' : '프로젝트 분석'}
          </button>
        )}
        {a && (
          <small className="muted">
            {ANALYSIS_LABEL[a.status]}
            {a.finishedAt && ` · ${new Date(a.finishedAt).toLocaleString()}`}
            {a.report && <> · <button className="link" onClick={() => setShowReport(true)}>보고서</button></>}
          </small>
        )}
      </div>
      <p className="muted small">코드에서 아키텍처(레이어·의존 방향)와 코드 컨벤션 규칙을 추출합니다. AI가 확실한 규칙만 자동 승인하고, 나머지는 아래에서 승인·거절합니다.</p>
      {a?.error && <p className="err">{a.error}</p>}
      {showReport && a?.report && (
        <Dialog title="분석 보고서" onClose={() => setShowReport(false)}><pre>{a.report}</pre></Dialog>
      )}

      <nav className="tabs" aria-label="종류">
        {(Object.keys(TAB_LABEL) as Tab[]).map((t) => (
          <button key={t} className={t === tab ? 'on' : ''} onClick={() => setTab(t)}>
            {TAB_LABEL[t]}{count(t) > 0 && <span className="count">{count(t)}</span>}
          </button>
        ))}
      </nav>

      {!projectId && <p className="muted">연결된 프로젝트가 없습니다</p>}
      {error && <p className="err">{error}</p>}
      {knowledge.error && <p className="err">{knowledge.error}</p>}

      {k && bulk.length > 0 && (
        <div className="row bulk">
          <small className="muted">승인 대기 {bulk.length}건</small>
          <button disabled={busy} onClick={() => bulkRun('approve')}>모두 승인</button>
          <button className="secondary" disabled={busy} onClick={() => bulkRun('reject')}>모두 거절</button>
        </div>
      )}

      {k && tab !== 'memory' && (
        k[tab].length ? (
          <ul className="plain items">
            {k[tab].map((r) => (
              <Item key={r.id} title={r.description} state={ruleState(r)} busy={busy}
                meta={`${r.kind}${r.severity ? ` · ${r.severity}` : ''}${r.decidedBy ? ` · ${r.decidedBy}` : ''}`}
                body={r.reviewVerdict && <p className="review"><StatusBadge value={r.reviewVerdict} /> {r.reviewReason}</p>}
                onApprove={() => run(() => api.decideRules(projectId, tab, [r.id], 'approve'))}
                onReject={() => run(() => api.decideRules(projectId, tab, [r.id], 'reject'))} />
            ))}
          </ul>
        ) : <p className="muted">규칙이 없습니다</p>
      )}

      {k && tab === 'memory' && (
        k.memory.length ? (
          <ul className="plain items">
            {k.memory.map((m: MemoryView) => (
              <Item key={m.id} title={m.summary} busy={busy}
                state={m.status === 'ACTIVE' ? 'approved' : m.status === 'CANDIDATE' ? 'pending' : 'rejected'}
                meta={`${m.kind} · ${m.file}${m.supersededReason ? ` · ${m.supersededReason}` : ''}`}
                body={<p className="review">{m.recommendation}</p>}
                onApprove={() => run(() => api.decideMemory(projectId, [m.id], 'approve'))}
                onReject={() => run(() => api.decideMemory(projectId, [m.id], 'reject'))} />
            ))}
          </ul>
        ) : <p className="muted">학습된 메모리가 없습니다</p>
      )}
    </>
  )
}
