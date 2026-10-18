import { useEffect, useState } from 'react'
import { api, type ClaudeLogin } from './api'
import { ago } from './taskView'
import { usePoll } from './usePoll'

const LOGIN_TEXT: Record<ClaudeLogin['status'], string> = {
  REQUESTED: 'Runner가 로그인 창을 여는 중…',
  WAITING_CODE: '아래 링크로 로그인한 뒤 화면의 코드를 붙여 넣으세요',
  CODE_SUBMITTED: '코드를 확인하는 중…',
  DONE: '로그인했습니다',
  FAILED: '로그인하지 못했습니다',
}

/** 진행 중인 로그인 하나. 링크로 claude.ai 에 로그인하고(휴대폰도 됨) 마지막 화면의 코드를 넣으면 Runner 가 CLI 에 전달한다. */
function LoginCard({ login, onChange }: { login: ClaudeLogin; onChange: () => void }) {
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)

  async function submit(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.submitClaudeCode(login.id, code.trim())
      setCode('')
      onChange()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <div className="card">
      <div className="row"><strong className="grow">{login.name}</strong><small className="muted">{login.runnerId} · {ago(login.requestedAt)}</small></div>
      <p className={login.status === 'FAILED' ? 'err' : 'sub'}>{login.error ?? LOGIN_TEXT[login.status]}</p>
      {login.status === 'WAITING_CODE' && login.url && (
        <>
          <p><a href={login.url} target="_blank" rel="noreferrer">claude.ai 로그인 열기</a></p>
          <form className="row" onSubmit={submit}>
            <input value={code} onChange={(e) => setCode(e.target.value)} placeholder="로그인 후 보이는 코드" autoComplete="off" />
            <button disabled={!code.trim()}>입력</button>
          </form>
        </>
      )}
      {login.status === 'DONE' && <p className="sub">{login.email} · {login.plan ?? '구독 없음(실행에 쓰지 않음)'}</p>}
      {error && <p className="err">{error}</p>}
    </div>
  )
}

/**
 * 일심이 쓰는 Claude 계정. 계정마다 Runner PC 에 따로 로그인해 두고(로그인 정보는 그 PC 에만 있음),
 * 일심은 새 세션마다 남은 사용량이 가장 여유 있는 계정을 고른다. 실행 중 한도 초과가 나도 다른 계정으로 넘기지 않는다.
 */
export function ClaudeAccounts() {
  const data = usePoll(api.claudeAccounts, 3000)
  const projects = usePoll(api.projects, 10000)
  const runners = [...new Set((projects.data ?? []).map((p) => p.runnerId).filter(Boolean))]
  const [runnerId, setRunnerId] = useState('')
  const [name, setName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const runner = runnerId || runners[0] || ''
  const accounts = data.data?.accounts ?? []
  const logins = (data.data?.logins ?? []).filter((l) => l.status !== 'DONE' || Date.now() - new Date(l.requestedAt).getTime() < 10 * 60000)

  async function start(e: React.FormEvent) {
    e.preventDefault()
    setError(null)
    try {
      await api.startClaudeLogin(runner, name.trim())
      setName('')
      data.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  async function remove(runnerId: string, account: string) {
    if (!confirm(`${account} 계정을 이 PC(${runnerId})에서 로그아웃하고 지울까요?`)) return
    try {
      await api.removeClaudeAccount(runnerId, account)
      data.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <section>
      <h2>Claude 계정</h2>
      <p className="sub">
        계정마다 한 번 로그인하면 일심이 새 세션을 열 때 남은 5시간·주간 사용량이 가장 여유 있는 계정으로 실행합니다.
        이어 가는 대화는 처음 계정으로 계속하고, 실행 중 한도에 걸리면 다른 계정으로 넘기지 않고 멈춥니다. 구독이 없는 계정은 쓰지 않습니다.
        등록한 계정이 없으면 PC의 기본 로그인(~/.claude)을 씁니다.
      </p>
      {accounts.length > 0 && (
        <table className="table">
          <thead><tr><th>이름</th><th>PC</th><th>계정</th><th>플랜</th><th /></tr></thead>
          <tbody>
            {accounts.map((a) => (
              <tr key={`${a.runnerId}/${a.name}`}>
                <td>{a.name}</td>
                <td>{a.runnerId}</td>
                <td>{a.loggedIn ? a.email : <span className="err">로그인 필요</span>}</td>
                <td>{a.plan ?? <span className="muted">구독 없음</span>}</td>
                <td><button className="link" onClick={() => remove(a.runnerId, a.name)}>삭제</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      )}
      {logins.map((l) => <LoginCard key={l.id} login={l} onChange={data.reload} />)}
      <form className="row" onSubmit={start}>
        {runners.length > 1 && (
          <select value={runner} onChange={(e) => setRunnerId(e.target.value)}>
            {runners.map((r) => <option key={r} value={r}>{r}</option>)}
          </select>
        )}
        <input value={name} onChange={(e) => setName(e.target.value)} placeholder="계정 이름 (예: personal)" autoComplete="off" />
        <button disabled={!runner || !name.trim()}>계정 추가</button>
      </form>
      {!runner && <p className="muted small">연결된 Runner가 없습니다</p>}
      {error && <p className="err">{error}</p>}
    </section>
  )
}

/** 모든 PC·계정에 똑같이 까는 CLAUDE.md 와 settings.json. 저장하면 Runner 가 몇 분 안에 계정 디렉터리에 반영한다. */
export function ClaudeEnvironmentEditor() {
  const [claudeMd, setClaudeMd] = useState('')
  const [settingsJson, setSettingsJson] = useState('{}')
  const [saved, setSaved] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    api.claudeEnvironment().then((e) => {
      setClaudeMd(e.claudeMd)
      setSettingsJson(e.settingsJson)
      setSaved(e.updatedAt)
    }).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }, [])

  async function save(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const env = await api.saveClaudeEnvironment(claudeMd, settingsJson)
      setSaved(env.updatedAt)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>Claude 공통 설정</h2>
      <p className="sub">일심에 등록한 모든 Claude 계정에 같은 CLAUDE.md와 settings.json을 씁니다. PC마다 ~/.claude가 달라도 같은 조건으로 실행됩니다.</p>
      <form className="stack" onSubmit={save}>
        <label>CLAUDE.md<textarea rows={12} value={claudeMd} onChange={(e) => setClaudeMd(e.target.value)} spellCheck={false} /></label>
        <label>settings.json<textarea rows={8} value={settingsJson} onChange={(e) => setSettingsJson(e.target.value)} spellCheck={false} /></label>
        <div className="row">
          <button disabled={busy}>저장</button>
          {saved && <small className="muted">{ago(saved)} 저장</small>}
        </div>
      </form>
      {error && <p className="err">{error}</p>}
    </section>
  )
}
