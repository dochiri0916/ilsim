import { useEffect, useState } from 'react'
import { api, type Analysis, type MemoryView, type RuleSet, type RuleView } from './api'
import { Dialog } from './Dialog'
import { StatusBadge } from './StatusBadge'
import { usePoll } from './usePoll'
import { memoryTradeoff, ruleTradeoff, type Tradeoff } from './ruleTradeoffs'

type Tab = 'architecture' | 'convention' | 'memory'
const TAB_LABEL: Record<Tab, string> = { architecture: '아키텍처 규칙', convention: '컨벤션', memory: '학습 메모리' }

function ruleState(r: RuleView): 'approved' | 'rejected' | 'pending' {
  return r.approved ? 'approved' : r.rejected ? 'rejected' : 'pending'
}

const STATE_LABEL = { approved: '승인됨', rejected: '거절됨', pending: '승인 대기' }
const STATE_ORDER = { pending: 0, approved: 1, rejected: 2 }

function byState<T>(items: T[], state: (t: T) => keyof typeof STATE_ORDER): T[] {
  return [...items].sort((a, b) => STATE_ORDER[state(a)] - STATE_ORDER[state(b)])
}

function memoryState(m: MemoryView): 'approved' | 'rejected' | 'pending' {
  return m.status === 'ACTIVE' ? 'approved' : m.status === 'CANDIDATE' ? 'pending' : 'rejected'
}

function TradeoffTip({ t }: { t: Tradeoff & { note?: string } }) {
  return (
    <span className="tip" tabIndex={0} aria-label="적용 시 장단점">
      장단점
      <span className="tip-body" role="tooltip">
        <span><b>장점</b> {t.pro}</span>
        <span><b>단점</b> {t.con}</span>
        {t.note && <span className="muted">{t.note}</span>}
      </span>
    </span>
  )
}

function Item({ title, meta, body, state, tradeoff, onApprove, onReject, busy }: {
  title: string; meta: string; body: React.ReactNode; state: 'approved' | 'rejected' | 'pending'
  tradeoff?: Tradeoff & { note?: string }; onApprove: () => void; onReject: () => void; busy: boolean
}) {
  return (
    <li className={`item ${state}`}>
      <div className="item-head">
        <strong>{title}</strong>
        {tradeoff && <TradeoffTip t={tradeoff} />}
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

function upgradeRequest(set: RuleSet, r: RuleView): string {
  return [
    `[추천 규칙 업그레이드] ${r.principle}`,
    `규칙: ${r.description}`,
    `이유: ${r.rationale}`,
    r.examples.length ? `위반 예: ${r.examples.join(', ')}` : '',
    `${set === 'architecture' ? '아키텍처' : '컨벤션'} 규칙 \`${r.id}\`을 지키도록 기존 코드를 리팩터링한다. 동작은 바꾸지 않고 기존 테스트는 모두 통과해야 한다. 위반이 많으면 영향이 큰 곳부터 고친다.`,
  ].filter(Boolean).join('\n')
}

function RuleBody({ r, onUpgrade, busy }: { r: RuleView; onUpgrade: () => void; busy: boolean }) {
  return (
    <>
      {r.recommended && (
        <div className="recommend">
          <p className="review"><b>{r.principle}</b> {r.rationale}</p>
          <p className="muted small">현재 준수 {r.compliant}/{r.total}{r.examples.length > 0 && ` · 예: ${r.examples.join(', ')}`}</p>
          {r.compliant < r.total && <button className="secondary" disabled={busy} onClick={onUpgrade}>업그레이드 작업 만들기</button>}
        </div>
      )}
      {r.reviewVerdict && <p className="review"><StatusBadge value={r.reviewVerdict} /> {r.reviewReason}</p>}
    </>
  )
}

const ANALYSIS_LABEL: Record<Analysis['status'], string> = {
  REQUESTED: 'Runner 대기 중…', RUNNING: '분석 중…', DONE: '분석 완료', FAILED: '분석 실패',
}

export function Knowledge({ projectId }: { projectId: string }) {
  const [tab, setTab] = useState<Tab>('architecture')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const knowledge = usePoll(() => (projectId ? api.knowledge(projectId) : Promise.resolve(null)), 8000, [projectId])
  const k = knowledge.data
  const analysis = usePoll(() => (projectId ? api.analysis(projectId) : Promise.resolve(undefined)), 3000, [projectId])
  const a = analysis.data
  const analyzing = a?.status === 'REQUESTED' || a?.status === 'RUNNING'
  const [showReport, setShowReport] = useState(false)

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
      <p className="muted small">코드에서 아키텍처(레이어·의존 방향)와 코드 컨벤션 규칙을 추출합니다. AI가 확실한 규칙만 자동 승인하고, 나머지는 아래에서 승인·거절합니다. 헥사고날·DDD·클린 아키텍처·객체지향·생활 체조 기준으로 더 나은 방향은 "추천"으로 제안하며, 승인하면 적용되고 위반은 업그레이드 작업으로 고칩니다.</p>
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
            {byState(k[tab], ruleState).map((r) => (
              <Item key={r.id} title={r.description} state={ruleState(r)} busy={busy} tradeoff={ruleTradeoff(r.kind, r.severity)}
                meta={`${r.recommended ? '추천 · ' : ''}${r.kind}${r.severity ? ` · ${r.severity}` : ''}${r.decidedBy ? ` · ${r.decidedBy}` : ''}`}
                body={<RuleBody r={r} busy={busy} onUpgrade={() => run(async () => {
                  const task = await api.createTask(projectId, '', upgradeRequest(tab, r))
                  location.hash = `#/tasks/${task.id}`
                })} />}
                onApprove={() => run(() => api.decideRules(projectId, tab, [r.id], 'approve'))}
                onReject={() => run(() => api.decideRules(projectId, tab, [r.id], 'reject'))} />
            ))}
          </ul>
        ) : <p className="muted">규칙이 없습니다</p>
      )}

      {k && tab === 'memory' && (
        k.memory.length ? (
          <ul className="plain items">
            {byState(k.memory, memoryState).map((m: MemoryView) => (
              <Item key={m.id} title={m.summary} busy={busy} state={memoryState(m)} tradeoff={memoryTradeoff(m.kind)}
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
