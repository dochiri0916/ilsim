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
        <div className="stat"><span className="stat-value">{usd(data.costUsd) || '$0'}{data.costIsLowerBound ? ' 이상' : ''}</span><span className="stat-label">AI 비용</span></div>
        <div className="stat"><span className="stat-value">{data.escalatedStages}</span><span className="stat-label">모델 승격 단계</span></div>
      </section>
      {data.costIsLowerBound && <p className="muted">비용 미확인 호출 {data.unknownCalls}건 · 실행 중 {data.runningExecutions ?? 0}건. 확인된 금액만 표시하며 JEV·프로젝트 분석 비용도 포함합니다.</p>}
      {data.costIsUnverified && <p className="muted">이전 형식의 비용 {usd(data.legacyCostUsd)}가 포함된 참고 집계입니다. 당시 세션별 원본이 없어 실제 절감률 비교에는 사용할 수 없습니다.</p>}
      {Object.keys(data.decisionsByHandler ?? {}).length > 0 && <p className="muted">처리 이력: {Object.entries(data.decisionsByHandler).map(([h, n]) => `${({ JEV: 'JEV 완료', AGENT: 'Agent 이관', CACHE: '결과 재사용' } as Record<string, string>)[h] ?? h} ${n}건`).join(' · ')}</p>}

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

      <section className="card">
        <h2>호출 목적별 사용량</h2>
        <ul className="plain">
          {Object.entries(data.byPurpose ?? {}).map(([purpose, u]) => <li key={purpose}>
            <strong>{({ WRITE: '작성', REVIEW: '독립 검토', CHANGE_AUDIT: '변경 검토', RISK_REVIEW: '위험 검토', ANALYSIS_ARCHITECTURE_REVIEW: '아키텍처 검토', ANALYSIS_CONVENTION_REVIEW: '컨벤션 검토', JEV: 'JEV 판단', JEV_ROUTING: 'JEV 모델 선택' } as Record<string, string>)[purpose] ?? purpose}</strong>
            <p className="muted">{u.calls}건 · {usd(u.costUsd) || '$0'}{u.unknownCalls ? ' 이상' : ''} · 입력 {u.inputTokens.toLocaleString()} / 출력 {u.outputTokens.toLocaleString()} 토큰 · cache 읽기 {u.cacheReadTokens.toLocaleString()} / 쓰기 {u.cacheWriteTokens.toLocaleString()} · 도구 {u.toolCalls}회 · 질문 {u.questions}개</p>
          </li>)}
        </ul>
      </section>

      <section className="card">
        <h2>모델·effort별 결과</h2>
        <ul className="plain">
          {(data.byCombination ?? []).map((u) => <li key={`${u.stage}/${u.model}/${u.effort}`}>
            {STAGE_LABEL[u.stage] ?? u.stage} · {modelLabel(u.model)} / {u.effort}
            <p className="muted">{u.executions}회 실행 · 성공률 {u.successRate === null ? '집계 전' : `${(u.successRate * 100).toFixed(1)}%`} · {usd(u.costUsd) || '$0'}{u.unknownCalls ? ' 이상' : ''}</p>
          </li>)}
        </ul>
      </section>

      <section className="card">
        <h2>검토 품질 비교</h2>
        {(data.reviewProfiles ?? []).length === 0 && <p className="muted">누락·오탐을 확인한 검토 표본이 아직 없습니다.</p>}
        <ul className="plain">
          {(data.reviewProfiles ?? []).map((p) => <li key={`${p.purpose}/${p.workload}/${p.model}/${p.effort}`}>
            {modelLabel(p.model)} / {p.effort} · {p.purpose} · {p.workload}
            <p className="muted">검증 {p.samples}건 · 정확 {p.passed} · 누락 {p.missed} · 오탐 {p.falsePositives} · 평균 {p.meanCostUsd == null ? '미확인' : usd(p.meanCostUsd) || '$0'} · {p.eligible ? '선택 가능' : '자료 추가 확인'}</p>
          </li>)}
        </ul>
        {(data.reviewSamplesReserved ?? 0) > 0 && <p className="muted">추가 비교 예약 {data.reviewSamplesReserved}건 · 예산 {usd(data.reviewSampleBudgetReservedUsd)}. 실제 사용액은 AI 비용에 포함됩니다.</p>}
      </section>
    </>
  )
}
