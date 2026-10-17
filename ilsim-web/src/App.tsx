import { useEffect, useState } from 'react'
import { tokenStore } from './api'
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

function TokenGate({ onSaved }: { onSaved: () => void }) {
  const [value, setValue] = useState('')
  return (
    <main>
      <h1>일심 Ilsim</h1>
      <p className="sub">접속 토큰을 입력하세요</p>
      <form
        className="row"
        onSubmit={(e) => {
          e.preventDefault()
          tokenStore.set(value.trim())
          onSaved()
        }}
      >
        <input type="password" value={value} onChange={(e) => setValue(e.target.value)} autoFocus />
        <button disabled={!value.trim()}>확인</button>
      </form>
    </main>
  )
}

export function App() {
  const [authed, setAuthed] = useState(!!tokenStore.get())
  const hash = useHash()
  if (!authed) return <TokenGate onSaved={() => setAuthed(true)} />

  const match = hash.match(/^#\/tasks\/(.+)$/)
  return (
    <main>
      <header>
        <a href="#/" className="brand">일심 Ilsim</a>
        <span>
          <a href="#/settings" className="link">설정</a>{' '}
          <button className="link" onClick={() => { tokenStore.clear(); setAuthed(false) }}>로그아웃</button>
        </span>
      </header>
      {match ? <TaskDetail id={match[1]} /> : hash === '#/settings' ? <Settings /> : <Dashboard />}
    </main>
  )
}
