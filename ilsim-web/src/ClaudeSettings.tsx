import { useEffect, useState } from 'react'
import { api, type ClaudeLogin, type ClaudeSkill } from './api'
import { ago } from './taskView'
import { usePoll } from './usePoll'

const LOGIN_TEXT: Record<ClaudeLogin['status'], string> = {
  REQUESTED: 'Runner가 로그인 창을 여는 중…',
  WAITING_CODE: '아래 링크로 로그인한 뒤 화면의 코드를 붙여 넣으세요',
  CODE_SUBMITTED: '코드를 확인하는 중…',
  DONE: '로그인했습니다',
  FAILED: '로그인하지 못했습니다',
}

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

type Draft = { name: string; files: [string, string][]; existing: boolean }

const SKILL_TEMPLATE = '---\nname: my-skill\ndescription: 언제 이 스킬을 쓰는지 한 줄로\n---\n\n지시 내용. 파일 경로는 이 스킬 폴더 기준 상대 경로나 ~ 기준으로 쓴다.\n'

async function readFolder(list: FileList): Promise<Draft | null> {
  const files: [string, string][] = []
  let name = ''
  for (const file of Array.from(list)) {
    const [root, ...rest] = file.webkitRelativePath.split('/')
    const path = rest.join('/')
    if (!path || rest.some((p) => p.startsWith('.')) || file.size > 200_000) continue
    const text = await file.text()
    if (text.includes('\u0000')) continue
    name = root.toLowerCase().replace(/[^a-z0-9-]/g, '-')
    files.push([path, text])
  }
  if (!files.length) return null
  files.sort(([a], [b]) => (a === 'SKILL.md' ? -1 : b === 'SKILL.md' ? 1 : a.localeCompare(b)))
  return { name, files, existing: false }
}

export function ClaudeSkillsEditor() {
  const [skills, setSkills] = useState<ClaudeSkill[]>([])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = () => api.claudeSkills().then(setSkills).catch((err) => setError(err instanceof Error ? err.message : String(err)))
  useEffect(() => { load() }, [])

  async function run(fn: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await fn()
      await load()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const setFile = (i: number, path: string, text: string) =>
    setDraft((d) => d && { ...d, files: d.files.map((f, j) => (j === i ? [path, text] : f)) })

  async function importFolder(e: React.ChangeEvent<HTMLInputElement>) {
    const picked = e.target.files && await readFolder(e.target.files)
    e.target.value = ''
    if (picked) setDraft(picked)
    else setError('가져올 텍스트 파일이 없습니다')
  }

  return (
    <section>
      <h2>Claude 스킬</h2>
      <p className="sub">등록한 스킬은 모든 PC·계정의 skills 폴더에 똑같이 설치됩니다. 경로는 스킬 폴더 기준 상대 경로나 ~·$HOME 기준으로 쓰세요. PC마다 다른 /Users/… 같은 절대 경로는 저장되지 않습니다.</p>
      {skills.length > 0 && (
        <ul className="plain items">
          {skills.map((s) => (
            <li key={s.name} className="item">
              <div className="item-head">
                <strong>{s.name}</strong>
                <small className="muted">파일 {Object.keys(s.files).length}개{s.updatedAt && ` · ${ago(s.updatedAt)} 저장`}</small>
              </div>
              <div className="row actions">
                <button className="secondary" disabled={busy} onClick={() => setDraft({ name: s.name, files: Object.entries(s.files), existing: true })}>편집</button>
                <button className="secondary" disabled={busy} onClick={() => confirm(`${s.name} 스킬을 지울까요?`) && run(() => api.deleteClaudeSkill(s.name))}>삭제</button>
              </div>
            </li>
          ))}
        </ul>
      )}
      {!draft && (
        <div className="row">
          <button disabled={busy} onClick={() => setDraft({ name: '', files: [['SKILL.md', SKILL_TEMPLATE]], existing: false })}>스킬 추가</button>
          <label className="button secondary">
            폴더에서 가져오기
            <input type="file" multiple hidden onChange={importFolder} {...{ webkitdirectory: '' }} />
          </label>
        </div>
      )}
      {draft && (
        <form className="stack" onSubmit={(e) => {
          e.preventDefault()
          run(async () => {
            await api.saveClaudeSkill(draft.name.trim(), Object.fromEntries(draft.files.filter(([p]) => p.trim())))
            setDraft(null)
          })
        }}>
          <input value={draft.name} disabled={draft.existing} onChange={(e) => setDraft({ ...draft, name: e.target.value })} placeholder="스킬 이름 (영문 소문자·숫자·하이픈)" autoComplete="off" />
          {draft.files.map(([path, text], i) => (
            <div key={i} className="stack">
              <div className="row">
                <input value={path} onChange={(e) => setFile(i, e.target.value, text)} placeholder="파일 경로 (예: scripts/check.sh)" disabled={path === 'SKILL.md' && i === 0} />
                {i > 0 && <button type="button" className="secondary" onClick={() => setDraft({ ...draft, files: draft.files.filter((_, j) => j !== i) })}>파일 빼기</button>}
              </div>
              <textarea rows={path === 'SKILL.md' ? 10 : 6} value={text} onChange={(e) => setFile(i, path, e.target.value)} spellCheck={false} />
            </div>
          ))}
          <div className="row">
            <button type="button" className="secondary" onClick={() => setDraft({ ...draft, files: [...draft.files, ['', '']] })}>파일 추가</button>
            <button disabled={busy || !draft.name.trim()}>저장</button>
            <button type="button" className="secondary" onClick={() => setDraft(null)}>취소</button>
          </div>
        </form>
      )}
      {error && <p className="err">{error}</p>}
    </section>
  )
}
