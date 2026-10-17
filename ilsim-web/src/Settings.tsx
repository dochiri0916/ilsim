import { useState } from 'react'
import { api } from './api'
import { usePoll } from './usePoll'

/** JEV API 키 등록. 키는 서버가 암호화해 MySQL 에 저장하며 화면에는 마지막 4자리만 보인다. */
export function Settings() {
  const settings = usePoll(api.settings, 10000)
  const [key, setKey] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const jev = settings.data?.jev

  async function save(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.saveJevKey(key.trim())
      setKey('')
      settings.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    if (!confirm('저장된 JEV 키를 삭제할까요?')) return
    setError(null)
    try {
      await api.deleteJevKey()
      settings.reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <section>
      <h2>JEV API 키</h2>
      <p className="sub">
        TypeSafe JEV 는 모델 선택, 문서 선택, 위험 후보 판정에 쓰입니다. 키는 서버가 암호화해 MySQL 에 저장하고 화면에 다시 보여 주지 않습니다.
        등록하지 않으면 규칙 기반으로 동작합니다.
      </p>
      <p>
        상태:{' '}
        {jev?.configured ? <strong>등록됨 (…{jev.last4})</strong> : <span>등록되지 않음</span>}
        {jev?.updatedAt && <small> · {new Date(jev.updatedAt).toLocaleString()}</small>}
      </p>
      <form className="row" onSubmit={save}>
        <input
          type="password"
          value={key}
          onChange={(e) => setKey(e.target.value)}
          placeholder={jev?.configured ? '새 키로 바꾸기' : 'JEV API 키'}
          autoComplete="off"
        />
        <button disabled={busy || !key.trim()}>저장</button>
        {jev?.configured && <button type="button" className="link" onClick={remove}>삭제</button>}
      </form>
      {error && <p className="err">{error}</p>}
    </section>
  )
}
