import { useEffect, useState } from 'react'
import { api, type ExecutionReview, type OptionReport, type RoutingAdjustment } from './api'
import { ago, modelLabel, usd } from './taskView'
import { usePoll } from './usePoll'

const EFFORTS = ['low', 'medium', 'high', 'xhigh', 'max']

const VERDICT: Record<ExecutionReview['verdict'], { label: string; tone: string }> = {
  FIT: { label: '적정', tone: '' },
  OVER: { label: '과함', tone: 'warn' },
  UNDER: { label: '부족', tone: 'bad' },
  UNKNOWN: { label: '판단 보류', tone: 'muted' },
}

const pct = (v: number | null | undefined) => (v === null || v === undefined ? '-' : `${Math.round(v * 100)}%`)
const signed = (v: number | null | undefined) => (v === null || v === undefined ? '-' : `${v > 0 ? '+' : ''}${Math.round(v * 100)}%p`)

export function Routing({ projectId }: { projectId: string }) {
  const [scope, setScope] = useState<'project' | 'all'>('all')
  const data = usePoll(() => api.routing(scope === 'project' ? projectId : undefined), 10000, [scope, projectId])
  const [draft, setDraft] = useState<Record<string, RoutingAdjustment>>({})
  const [note, setNote] = useState('')
  const [dirty, setDirty] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const current = data.data?.current

  useEffect(() => {
    if (!current || dirty) return
    setDraft(Object.fromEntries(current.items.map((i) => [`${i.model}/${i.effort}`, i])))
  }, [current?.version])

  if (!data.data) return <p className="muted">{data.error ?? '불러오는 중…'}</p>
  const { report, history, models, upgrades, locked } = data.data
  const MODELS = ['haiku', 'sonnet', 'opus'].map((t) => models[t]).filter(Boolean)
  const byKey = Object.fromEntries(report.options.map((o) => [`${o.model}/${o.effort}`, o])) as Record<string, OptionReport>

  function change(model: string, effort: string, patch: Partial<RoutingAdjustment>) {
    const key = `${model}/${effort}`
    setDraft((d) => ({ ...d, [key]: { ...(d[key] ?? { model, effort, enabled: true, successOffset: 0 }), ...patch } }))
    setDirty(true)
  }

  async function run(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      setDirty(false)
      setNote('')
      data.reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    }
  }

  const total = Object.values(report.verdicts).reduce((a, b) => a + (b ?? 0), 0)
  const hasRecommendation = report.options.some((o) => o.recommendedOffset !== null)

  return (
    <>
      <h1 className="page-title">모델 선택</h1>
      <p className="muted small">
        JEV가 작업마다 고른 모델·추론 강도를 실제 검증 결과와 비교합니다. 저장된 이력만 쓰므로 추가 AI 비용이 없습니다.
        조정은 모든 프로젝트에 공통으로 적용되며, 다음 선택부터 반영됩니다.
      </p>
      <div className="row">
        <select value={scope} onChange={(e) => setScope(e.target.value as 'project' | 'all')}>
          <option value="all">전체 프로젝트 이력</option>
          <option value="project">이 프로젝트 이력만</option>
        </select>
      </div>

      <section className="card">
        <h2>사용 모델</h2>
        <p>{['haiku', 'sonnet', 'opus'].map((t) => models[t] && <span key={t} className="pill">{modelLabel(models[t])} <small className="muted">{models[t]}</small></span>)}</p>
        <p className="muted small">
          같은 계열의 새 버전(예: Sonnet 5.5 → 5.6)은 Runner가 한 시간마다 토큰 없이 확인해 바로 도입하고, 이전 버전의 실행 이력과 조정을 이어받습니다.
          {locked.length > 0 && <> 잠긴 계열: <strong>{locked.join(', ')}</strong>(선택·실행하지 않음).</>}
        </p>
        {upgrades.length > 0 && <ul className="plain small">{upgrades.slice(0, 5).map((u) => <li key={u}>{u}</li>)}</ul>}
      </section>

      <section className="stats">
        {(['FIT', 'OVER', 'UNDER', 'UNKNOWN'] as const).map((v) => (
          <div className="stat" key={v}>
            <span className={`stat-value ${VERDICT[v].tone}`}>{report.verdicts[v] ?? 0}</span>
            <span className="stat-label">{VERDICT[v].label}{total ? ` · ${pct((report.verdicts[v] ?? 0) / total)}` : ''}</span>
          </div>
        ))}
      </section>
      <p className="muted small">
        과함: 성공했지만 같은 작업군에서 더 싼 조합이 {report.minimumSamples}회 이상, {pct(report.qualityThreshold)} 이상 성공했고 비용이 20% 넘게 낮음.
        부족: 실패한 뒤 더 높은 조합으로 성공함. 판단 보류: 환경 장애이거나 비교할 근거가 없음.
      </p>

      <section className="card">
        <div className="row">
          <h2 className="grow">모델·추론 강도별 성적과 조정</h2>
          <button className="secondary" disabled={!hasRecommendation} onClick={() => run(api.applyRecommendedRouting)}
            title={hasRecommendation ? '표본이 충분한 조합의 권장 보정을 적용합니다' : `조합마다 ${report.minimumSamples}회 이상 실행되어야 권장값이 생깁니다`}>
            권장 보정 적용
          </button>
        </div>
        <table className="table">
          <thead>
            <tr><th>조합</th><th>실행</th><th>성공률</th><th>JEV 예측</th><th>예측 차이</th><th>평균 비용</th><th>과함/부족</th><th>권장</th><th>사용</th><th>보정</th></tr>
          </thead>
          <tbody>
            {MODELS.flatMap((m) => EFFORTS.map((e) => {
              const key = `${m}/${e}`
              const o = byKey[key]
              const d = draft[key] ?? { model: m, effort: e, enabled: true, successOffset: 0 }
              return (
                <tr key={key} className={d.enabled ? undefined : 'muted'}>
                  <td>{modelLabel(m)} · {e}</td>
                  <td>{o?.executions ?? 0}</td>
                  <td>{pct(o?.passRate)}</td>
                  <td>{pct(o?.averagePredicted)}</td>
                  <td className={o?.calibrationGap && Math.abs(o.calibrationGap) >= 0.1 ? 'warn' : undefined}>{signed(o?.calibrationGap)}</td>
                  <td>{o?.averageActualCostUsd != null ? usd(o.averageActualCostUsd) : '-'}</td>
                  <td>{o ? `${o.over}/${o.under}` : '-'}</td>
                  <td>{signed(o?.recommendedOffset)}</td>
                  <td><input type="checkbox" checked={d.enabled} onChange={(ev) => change(m, e, { enabled: ev.target.checked })} aria-label={`${key} 사용`} /></td>
                  <td>
                    <input className="offset" type="number" step={1} min={-30} max={30} value={Math.round(d.successOffset * 100)}
                      onChange={(ev) => change(m, e, { successOffset: Number(ev.target.value) / 100 })} aria-label={`${key} 보정(%p)`} />
                  </td>
                </tr>
              )
            }))}
          </tbody>
        </table>
        <p className="muted small">예측 차이 = JEV 예측 성공률 − 실제 성공률(+면 JEV가 낙관적). 보정(%p)은 JEV 예측에 더해지며 ±30까지. 끈 조합은 선택하지 않습니다.</p>
        <div className="row">
          <input className="grow" value={note} onChange={(e) => { setNote(e.target.value); setDirty(true) }} placeholder="조정 메모 (선택)" />
          <button disabled={!dirty} onClick={() => run(() => api.saveRouting(Object.values(draft), note))}>조정 저장</button>
          {dirty && <button className="link" onClick={() => { setDirty(false); setDraft(Object.fromEntries((current?.items ?? []).map((i) => [`${i.model}/${i.effort}`, i]))) }}>취소</button>}
        </div>
        {error && <p className="err">{error}</p>}
      </section>

      {report.workloads.length > 0 && (
        <section className="card">
          <h2>작업군별 선택</h2>
          <table className="table">
            <thead><tr><th>작업군(단계:복잡도:위험)</th><th>실행</th><th>가장 많이 쓴 조합</th><th>기준을 넘은 가장 싼 조합</th></tr></thead>
            <tbody>
              {report.workloads.map((w) => (
                <tr key={w.workload}>
                  <td>{w.workload}</td><td>{w.executions}</td>
                  <td>{w.mostUsed ? label(w.mostUsed) : '-'}</td>
                  <td className={w.cheapestQualified && w.cheapestQualified !== w.mostUsed ? 'warn' : undefined}>{w.cheapestQualified ? label(w.cheapestQualified) : '표본 부족'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>
      )}

      <section className="card">
        <h2>최근 선택</h2>
        {report.recent.length === 0 ? <p className="muted">아직 완료된 실행이 없습니다</p> : (
          <table className="table">
            <thead><tr><th>작업</th><th>단계</th><th>조합</th><th>예측</th><th>결과</th><th>비용</th><th>판정</th></tr></thead>
            <tbody>
              {report.recent.slice(0, 30).map((r) => (
                <tr key={r.executionId}>
                  <td><a href={`#/tasks/${r.taskId}`}>{r.taskId.slice(0, 8)}</a></td>
                  <td>{r.stage}</td>
                  <td>{modelLabel(r.model)} · {r.effort}</td>
                  <td>{pct(r.predictedSuccess)}</td>
                  <td className={r.passed ? undefined : 'err'}>{r.passed ? '성공' : r.failureKind ?? '실패'}</td>
                  <td>{r.actualCostUsd != null ? usd(r.actualCostUsd) : '-'}</td>
                  <td className={VERDICT[r.verdict].tone}>{VERDICT[r.verdict].label}{r.cheaperOption ? ` (${label(r.cheaperOption)})` : ''}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>

      <section className="card">
        <h2>조정 이력</h2>
        {history.length === 0 ? <p className="muted">아직 조정하지 않았습니다(JEV 예측 그대로 사용)</p> : (
          <ul className="plain">
            {history.map((h, i) => (
              <li key={`${h.version}-${i}`} className="row">
                <span className="grow">
                  <strong>{h.version}</strong>{i === 0 ? ' · 적용 중' : ''} · {h.items.length}개 조정{h.note ? ` · ${h.note}` : ''}
                  {h.createdAt && <small className="muted"> · {ago(h.createdAt)}</small>}
                </span>
                {i !== 0 && <button className="link" onClick={() => run(() => api.restoreRouting(h.version))}>이 버전으로 되돌리기</button>}
              </li>
            ))}
            {current && current.version !== 'none' && <li className="row"><span className="grow muted">조정 없음</span><button className="link" onClick={() => run(() => api.restoreRouting('none'))}>모든 조정 해제</button></li>}
          </ul>
        )}
      </section>
    </>
  )
}

function label(key: string): string {
  const [model, effort] = key.split('/')
  return `${modelLabel(model)} · ${effort}`
}
