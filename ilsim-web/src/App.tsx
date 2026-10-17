import { useEffect, useState } from 'react'
import { api, type AuthState } from './api'
import { Dashboard } from './Dashboard'
import { Settings } from './Settings'
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

function LoginGate({ setup, onDone }: { setup: boolean; onDone: (a: AuthState) => void }) {
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const mismatch = setup && confirm !== '' && confirm !== password
  const ready = username.trim() !== '' && password !== '' && (!setup || (password === confirm && password.length >= 8))

  const submit = async (e: React.FormEvent) => {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      onDone(await (setup ? api.setup(username, password) : api.login(username, password)))
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <main>
      <h1>일심 Ilsim</h1>
      <p className="sub">{setup ? '처음 사용합니다. 관리자 계정을 만드세요' : '로그인'}</p>
      <form className="login" onSubmit={submit}>
        <input placeholder="아이디" autoComplete="username" value={username} autoFocus
          onChange={(e) => setUsername(e.target.value)} />
        <input type="password" placeholder={setup ? '비밀번호 (8자 이상)' : '비밀번호'}
          autoComplete={setup ? 'new-password' : 'current-password'} value={password}
          onChange={(e) => setPassword(e.target.value)} />
        {setup && (
          <input type="password" placeholder="비밀번호 확인" autoComplete="new-password" value={confirm}
            onChange={(e) => setConfirm(e.target.value)} />
        )}
        {mismatch && <p className="err">비밀번호가 서로 다릅니다</p>}
        {error && <p className="err">{error}</p>}
        <button disabled={!ready || busy}>{setup ? '계정 만들기' : '로그인'}</button>
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
  if (!auth.user) return <LoginGate setup={auth.setup} onDone={setAuth} />

  const match = hash.match(/^#\/tasks\/(.+)$/)
  return (
    <main>
      <header>
        <a href="#/" className="brand">일심 Ilsim</a>
        <span>
          <a href="#/settings" className="link">설정</a>{' '}
          <button className="link" onClick={() => { api.logout().finally(() => setAuth({ setup: false })) }}>로그아웃</button>
        </span>
      </header>
      {match ? <TaskDetail id={match[1]} /> : hash === '#/settings' ? <Settings /> : <Dashboard />}
    </main>
  )
}
