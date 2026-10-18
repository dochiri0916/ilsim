import { api } from './api'
import { STAGE_LABEL, modelLabel, usd } from './taskView'
import { usePoll } from './usePoll'

function Bars({ rows }: { rows: { label: string; value: number; note: string }[] }) {
  const max = Math.max(...rows.map((r) => r.value), 0.0001)
  return (
    <ul className="plain bars">
      {rows.map((r) => (
        <li key={r.label}>
          <div className="bar-head"><span>{r.label}</span><small>{r.note}</small></div>
          <div className="bar-track"><div className="bar-fill" style={{ width: `${Math.max(2, (r.value / max) * 100)}%` }} /></div>
        </li>
      ))}
    </ul>
  )
}

export function Stats({ projectId }: { projectId: string }) {
  const { data, error } = usePoll(() => api.stats(projectId), 10000, [projectId])
  if (!data) return <p className="muted">{error ?? '불러오는 중…'}</p>
  const models = Object.entries(data.byModel).sort((a, b) => b[1].costUsd - a[1].costUsd)

  return (
    <>
      <h1 className="page-title">통계</h1>
      <section className="stats">
        <div className="stat"><span className="stat-value">{data.tasks}</span><span className="stat-label">작업</span></div>
        <div className="stat"><span className="stat-value">{usd(data.costUsd) || '$0'}</span><span className="stat-label">Claude 비용</span></div>
        <div className="stat"><span className="stat-value">{data.escalatedStages}</span><span className="stat-label">모델 승격 단계</span></div>
      </section>

      <section className="card">
        <h2>모델별 비용</h2>
        {models.length ? (
          <Bars rows={models.map(([m, u]) => ({ label: modelLabel(m), value: u.costUsd, note: `${usd(u.costUsd) || '$0'} · ${u.stages}단계` }))} />
        ) : <p className="muted">아직 집계된 비용이 없습니다</p>}
      </section>

      <section className="card">
        <h2>단계별 비용</h2>
        <Bars rows={data.byStage.map((s) => ({
          label: STAGE_LABEL[s.stage] ?? s.stage,
          value: s.costUsd,
          note: `${usd(s.costUsd) || '$0'} · 평균 ${s.averageAttempts.toFixed(1)}회 시도`,
        }))} />
      </section>
    </>
  )
}
