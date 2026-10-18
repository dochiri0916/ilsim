import QRCode from 'qrcode'
import { useEffect, useState } from 'react'
import { api } from './api'
import { usePoll } from './usePoll'

/** 로그인한 계정의 비밀번호 변경. 계정 생성은 관리자가 DB 에서 직접 한다. */
function Account() {
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [done, setDone] = useState(false)
  const [busy, setBusy] = useState(false)
  const mismatch = confirm !== '' && confirm !== next

  async function change(e: React.FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    setDone(false)
    try {
      await api.changePassword(current, next)
      setCurrent('')
      setNext('')
      setConfirm('')
      setDone(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section>
      <h2>비밀번호 변경</h2>
      <p className="sub">계정은 관리자가 DB 에서 직접 만듭니다. 여기서는 내 비밀번호만 바꿀 수 있습니다.</p>
      <form className="row" onSubmit={change}>
        <input type="password" placeholder="현재 비밀번호" autoComplete="current-password" value={current}
          onChange={(e) => setCurrent(e.target.value)} />
        <input type="password" placeholder="새 비밀번호 (8자 이상)" autoComplete="new-password" value={next}
          onChange={(e) => setNext(e.target.value)} />
        <input type="password" placeholder="새 비밀번호 확인" autoComplete="new-password" value={confirm}
          onChange={(e) => setConfirm(e.target.value)} />
        <button disabled={busy || !current || next.length < 8 || next !== confirm}>변경</button>
      </form>
      {mismatch && <p className="err">새 비밀번호가 서로 다릅니다</p>}
      {error && <p className="err">{error}</p>}
      {done && <p className="sub">변경했습니다</p>}
    </section>
  )
}

/** Google Authenticator 2단계 인증. 켜면 비밀번호로 새로 로그인할 때 코드를 묻고, 자동 로그인된 기기는 묻지 않는다. */
function TwoFactor() {
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [setup, setSetup] = useState<{ secret: string; uri: string } | null>(null)
  const [qr, setQr] = useState<string | null>(null)
  const [disabling, setDisabling] = useState(false)
  const [code, setCode] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  useEffect(() => { api.me().then((a) => setEnabled(a.twoFactor ?? null)).catch(() => setEnabled(null)) }, [])
  useEffect(() => {
    if (setup) QRCode.toDataURL(setup.uri, { margin: 1, width: 200 }).then(setQr).catch(() => setQr(null))
  }, [setup])

  async function run(fn: () => Promise<void>) {
    setBusy(true)
    setError(null)
    try {
      await fn()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setBusy(false)
    }
  }

  const codeInput = (
    <input className="otp" inputMode="numeric" autoComplete="one-time-code" placeholder="6자리 코드" maxLength={6}
      value={code} onChange={(e) => setCode(e.target.value.replace(/\D/g, ''))} />
  )
  const reset = () => { setSetup(null); setQr(null); setDisabling(false); setCode('') }

  return (
    <section>
      <h2>2단계 인증</h2>
      <p className="sub">켜 두면 비밀번호로 새로 로그인할 때 Google Authenticator 코드를 한 번 더 묻습니다. 자동 로그인된 기기는 묻지 않습니다.</p>
      <p>상태: {enabled === null ? <span>확인할 수 없음</span> : enabled ? <strong>사용 중</strong> : <span>사용 안 함</span>}</p>

      {enabled === false && !setup && (
        <button disabled={busy} onClick={() => run(async () => setSetup(await api.totpSetup()))}>등록하기</button>
      )}
      {setup && (
        <form className="col totp-setup" onSubmit={(e) => {
          e.preventDefault()
          run(async () => { await api.totpEnable(code); reset(); setEnabled(true) })
        }}>
          <p className="small">1. Google Authenticator에서 QR 코드를 스캔하세요</p>
          {qr && <img src={qr} alt="2단계 인증 QR 코드" width={200} height={200} />}
          <small className="muted">스캔이 안 되면 키 직접 입력: <code>{setup.secret}</code></small>
          <p className="small">2. 앱에 표시된 코드를 입력하세요</p>
          <div className="row">{codeInput}<button disabled={busy || code.length !== 6}>확인</button>
            <button type="button" className="secondary" onClick={reset}>취소</button></div>
        </form>
      )}

      {enabled && !disabling && <button className="secondary" onClick={() => setDisabling(true)}>끄기</button>}
      {enabled && disabling && (
        <form className="row" onSubmit={(e) => {
          e.preventDefault()
          run(async () => { await api.totpDisable(code); reset(); setEnabled(false) })
        }}>
          {codeInput}<button disabled={busy || code.length !== 6}>끄기</button>
          <button type="button" className="secondary" onClick={reset}>취소</button>
        </form>
      )}
      {error && <p className="err">{error}</p>}
    </section>
  )
}

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
    <>
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
    <Account />
    <TwoFactor />
    </>
  )
}
