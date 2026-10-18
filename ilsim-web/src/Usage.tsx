import { useState } from 'react'
import { api, type Project, type UsageWindow } from './api'
import { ago } from './taskView'
import { usePoll } from './usePoll'

function resetText(iso: string | null): string {
  if (!iso) return ''
  const min = Math.round((new Date(iso).getTime() - Date.now()) / 60000)
  if (min <= 0) return '곧 재설정'
  const when = new Date(iso).toLocaleString(undefined, { month: 'numeric', day: 'numeric', weekday: 'short', hour: '2-digit', minute: '2-digit' })
  const left = min < 60 ? `${min}분` : min < 1440 ? `${Math.floor(min / 60)}시간 ${min % 60}분` : `${Math.floor(min / 1440)}일 ${Math.floor((min % 1440) / 60)}시간`
  return `${left} 후 재설정 · ${when}`
}

function Meter({ label, w }: { label: string; w: UsageWindow | null }) {
  const pct = w ? Math.round(w.utilization * 100) : null
  const tone = pct === null ? '' : pct >= 90 ? 'bad' : pct >= 70 ? 'warn' : ''
  return (
    <div className="meter">
      <div className="bar-head"><span>{label}</span><strong className={tone}>{pct === null ? '-' : `${pct}%`}</strong></div>
      <div className="bar-track"><div className={`bar-fill ${tone}`} style={{ width: `${pct ?? 0}%` }} /></div>
      <small className="muted">{w ? resetText(w.resetsAt) : '아직 정보가 없습니다'}</small>
    </div>
  )
}

/** 등록한 Claude 계정(claude@이름)이 있으면 계정별로, 없으면 기본 로그인 하나. */
function providers(ids: string[]) {
  const accounts = ids.filter((id) => id.startsWith('claude@')).sort()
  return [
    ...(accounts.length ? accounts : ['claude']).map((id) => ({
      id, label: id === 'claude' ? 'Claude' : `Claude · ${id.slice(7)}`, empty: 'Claude를 한 번 실행하면 표시됩니다',
    })),
  ]
}

/** 선택한 프로젝트를 돌리는 Runner(PC)의 Claude 구독 플랜 5시간·주간 사용량. 등록한 계정마다 따로 보여 준다. */
export function Usage({ project }: { project: Project }) {
  const usage = usePoll(api.usage, 5000)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const mine = usage.data?.filter((x) => x.runnerId === project.runnerId) ?? []
  const waiting = mine.some((x) => !!x.refreshRequestedAt)

  async function refresh() {
    setBusy(true)
    setError(null)
    try {
      await api.refreshUsage(project.runnerId)
      usage.reload()
    } catch (e) {
      setError(e instanceof Error ? e.message : String(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <>
      <h1 className="page-title">플랜 사용량</h1>
      <p className="muted small">이 프로젝트를 실행하는 PC({project.runnerId})의 Claude 구독 사용량입니다. 일심에 등록한 계정마다 따로 보여 줍니다. 일심 밖에서 쓴 양도 포함되며, 같은 PC의 다른 프로젝트와 같은 값입니다.</p>
      {error && <p className="err">{error}</p>}
      {usage.error && <p className="err">{usage.error}</p>}
      {providers(mine.map((x) => x.provider ?? 'claude')).map((p) => {
        const u = mine.find((x) => (x.provider ?? 'claude') === p.id)
        return (
          <section className="card" key={p.id}>
            <h2>{p.label}</h2>
            <div className="meters">
              <Meter label="5시간 사용량" w={u?.fiveHour ?? null} />
              <Meter label="주간 사용량" w={u?.sevenDay ?? null} />
            </div>
            <small className="muted">
              {u?.observedAt ? `${ago(u.observedAt)} 기준` : p.empty}
              {u?.status && u.status !== 'allowed' && <> · <span className="err">{u.status}</span></>}
            </small>
          </section>
        )
      })}
      <div className="row actions">
        <span className="grow" />
        <button className="secondary" disabled={busy || waiting || !project.online} onClick={refresh}>
          {waiting ? 'Runner 확인 중…' : project.online ? '지금 확인' : 'Runner 오프라인'}
        </button>
      </div>
      <p className="muted small">Runner가 5분마다, 그리고 "지금 확인"을 누르면 읽습니다. Claude의 <code>/usage</code>로 읽어 모델을 부르지 않고 토큰이 들지 않습니다.</p>
    </>
  )
}
