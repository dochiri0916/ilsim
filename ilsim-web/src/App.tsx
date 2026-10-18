import { useEffect, useState } from 'react'
import { api, type AuthState } from './api'
import { Dashboard } from './Dashboard'
import { Knowledge } from './Knowledge'
import { ProjectPicker, useSelectedProject } from './ProjectPicker'
import { Settings } from './Settings'
import { Routing } from './Routing'
import { Stats } from './Stats'
import { TaskDetail } from './TaskDetail'
import { Usage } from './Usage'
import { usePoll } from './usePoll'

function useHash() {
  const [hash, setHash] = useState(location.hash)
  useEffect(() => {
    const on = () => setHash(location.hash)
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return hash
}

function LoginGate({ initial, onDone }: { initial: AuthState; onDone: (a: AuthState) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [remember, setRemember] = useState(true)
  const [code, setCode] = useState('')
  const [otp, setOtp] = useState(!!initial.otpRequired)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function run(fn: () => Promise<AuthState>) {
    setBusy(true)
    setError(null)
    try {
      const a = await fn()
      if (a.user) onDone(a)
      else if (a.otpRequired) setOtp(true)
    } catch (err) {
      const message = err instanceof Error ? err.message : String(err)
      if (message === '다시 로그인하세요') { setOtp(false); setCode('') }
      setError(message)
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth">
      <h1 className="brand-lg">ILSIM</h1>
      <p className="sub">AI 개발 작업을 단계별로 검증하고 승인합니다</p>
      {otp ? (
        <form className="login card" onSubmit={(e) => { e.preventDefault(); run(() => api.otp(code)) }}>
          <p className="muted small">Google Authenticator 앱의 6자리 코드를 입력하세요</p>
          <input className="otp" inputMode="numeric" autoComplete="one-time-code" placeholder="000000" maxLength={6} autoFocus
            value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
          {error && <p className="err">{error}</p>}
          <button disabled={busy || code.length !== 6}>확인</button>
          <button type="button" className="link" onClick={() => { setOtp(false); setCode(''); setError(null) }}>처음으로</button>
        </form>
      ) : (
        <form className="login card" onSubmit={(e) => { e.preventDefault(); run(() => api.login(username, password, remember)) }}>
          <input placeholder="아이디" autoComplete="username" value={username} autoFocus
            onChange={(e) => setUsername(e.target.value)} />
          <input type="password" placeholder="비밀번호" autoComplete="current-password" value={password}
            onChange={(e) => setPassword(e.target.value)} />
          <label className="check">
            <input type="checkbox" checked={remember} onChange={(e) => setRemember(e.target.checked)} />
            자동 로그인 (30일)
          </label>
          {error && <p className="err">{error}</p>}
          <button disabled={busy || username.trim() === '' || password === ''}>로그인</button>
        </form>
      )}
    </main>
  )
}

export function App() {
  const [auth, setAuth] = useState<AuthState | null>(null)
  const [error, setError] = useState<string | null>(null)
  const hash = useHash()
  useEffect(() => {
    api.me().then(setAuth).catch((e) => setError(e instanceof Error ? e.message : String(e)))
  }, [])
  if (error) return <main><p className="err">서버에 연결할 수 없습니다: {error}</p></main>
  if (!auth) return <main><p className="sub">불러오는 중…</p></main>
  if (!auth.user) return <LoginGate initial={auth} onDone={setAuth} />
  return <Shell hash={hash} onLogout={() => { api.logout().finally(() => setAuth({})) }} />
}

const TABS = [
  { href: '#/', label: '작업' },
  { href: '#/knowledge', label: '규칙·메모리' },
  { href: '#/usage', label: '사용량' },
  { href: '#/stats', label: '통계' },
  { href: '#/routing', label: '모델 선택' },
]

/** 프로젝트를 먼저 고르고, 그 프로젝트 안에서 탭을 오간다. 설정만 프로젝트와 무관하다. */
function Shell({ hash, onLogout }: { hash: string; onLogout: () => void }) {
  const projects = usePoll(api.projects, 5000)
  const [projectId, setProjectId] = useSelectedProject(projects.data)
  const match = hash.match(/^#\/tasks\/(.+)$/)
  const tab = match ? '#/' : TABS.some((t) => t.href === hash) || hash === '#/settings' ? hash : '#/'
  const project = projects.data?.find((p) => p.id === projectId)

  function pick(id: string) {
    setProjectId(id)
    if (match) location.hash = '#/'
  }

  return (
    <div className={match ? 'app-fixed' : undefined}>
      <header className="topbar">
        <div className="topbar-in">
          <a href="#/" className="brand">ILSIM</a>
          <ProjectPicker projects={projects.data} value={projectId} onChange={pick} />
          <span className="grow" />
          <a href="#/settings" className={`top-link ${tab === '#/settings' ? 'on' : ''}`}>설정</a>
          <button className="link" onClick={onLogout}>로그아웃</button>
        </div>
        {project && (
          <nav className="tabs-bar" aria-label="프로젝트 메뉴">
            <div className="tabs-in">
              {TABS.map((t) => <a key={t.href} href={t.href} className={tab === t.href ? 'on' : ''}>{t.label}</a>)}
            </div>
          </nav>
        )}
      </header>
      <main>
        {tab === '#/settings' ? <Settings />
          : !project ? (projects.data && (
            <p className="muted">
              Runner가 연결되면 프로젝트가 표시됩니다. <code>ilsim-runner add &lt;id&gt; &lt;path&gt;</code> 후 <code>ilsim-runner run</code>
            </p>
          ))
          : match ? <TaskDetail id={match[1]} />
          : tab === '#/knowledge' ? <Knowledge projectId={projectId} />
          : tab === '#/usage' ? <Usage project={project} />
          : tab === '#/stats' ? <Stats projectId={projectId} />
          : tab === '#/routing' ? <Routing projectId={projectId} />
          : <Dashboard project={project} />}
      </main>
    </div>
  )
}
