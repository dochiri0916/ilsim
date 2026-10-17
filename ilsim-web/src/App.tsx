import { useEffect, useState } from 'react'
import { api, type AuthState } from './api'
import { Dashboard } from './Dashboard'
import { Knowledge } from './Knowledge'
import { Settings } from './Settings'
import { Stats } from './Stats'
import { TaskDetail } from './TaskDetail'

function useHash() {
  const [hash, setHash] = useState(location.hash)
  useEffect(() => {
    const on = () => setHash(location.hash)
    window.addEventListener('hashchange', on)
    return () => window.removeEventListener('hashchange', on)
  }, [])
  return hash
}

function LoginGate({ onDone }: { onDone: (a: AuthState) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const ready = username.trim() !== '' && password !== ''

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onDone(await api.login(username, password))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main className="auth">
      <h1 className="brand-lg">일심</h1>
      <p className="sub">AI 개발 작업을 단계별로 검증하고 승인합니다</p>
      <form className="login card" onSubmit={submit}>
        <input placeholder="아이디" autoComplete="username" value={username} autoFocus
          onChange={(e) => setUsername(e.target.value)} />
        <input type="password" placeholder="비밀번호" autoComplete="current-password" value={password}
          onChange={(e) => setPassword(e.target.value)} />
        {error && <p className="err">{error}</p>}
        <button disabled={!ready || busy}>로그인</button>
      </form>
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
  if (!auth.user) return <LoginGate onDone={setAuth} />

  const match = hash.match(/^#\/tasks\/(.+)$/)
  const nav = [
    { href: '#/', label: '작업', on: !match && !['#/knowledge', '#/stats', '#/settings'].includes(hash) || !!match },
    { href: '#/knowledge', label: '규칙·메모리', on: hash === '#/knowledge' },
    { href: '#/stats', label: '통계', on: hash === '#/stats' },
    { href: '#/settings', label: '설정', on: hash === '#/settings' },
  ]
  return (
    <>
      <header className="topbar">
        <div className="topbar-in">
          <a href="#/" className="brand">일심</a>
          <nav className="nav" aria-label="메뉴">
            {nav.map((n) => <a key={n.href} href={n.href} className={n.on ? 'on' : ''}>{n.label}</a>)}
          </nav>
          <button className="link" onClick={() => { api.logout().finally(() => setAuth({})) }}>로그아웃</button>
        </div>
      </header>
      <main>
        {match ? <TaskDetail id={match[1]} />
          : hash === '#/settings' ? <Settings />
          : hash === '#/knowledge' ? <Knowledge />
          : hash === '#/stats' ? <Stats />
          : <Dashboard />}
      </main>
    </>
  )
}
