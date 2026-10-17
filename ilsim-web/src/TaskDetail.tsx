import { useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, type StageState } from './api'
import { usePoll } from './usePoll'
import { Dialog } from './Dialog'
import { StatusBadge } from './StatusBadge'
import { modelLabel, usd } from './taskView'

const STAGE_LABEL: Record<string, string> = {
  REQUIREMENTS: '요구사항 분석',
  TEST_CASES: '테스트 케이스',
  TEST_CODE: '테스트 코드',
  IMPLEMENTATION: '프로덕트 코드',
}

const DIFF = '\u0000diff'
const ROUTING = '\u0000routing'
const LOG = '\u0000log'

export function TaskDetail({ id }: { id: string }) {
  const { data, error, reload } = usePoll(() => api.task(id), 2000, [id])
  const [picked, setPicked] = useState<string | null>(null)
  const [feedback, setFeedback] = useState('')
  const [chat, setChat] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [viewing, setViewing] = useState<string | null>(null)

  if (!data) return <p className="muted">{error ?? '불러오는 중…'}</p>
  const { task, events } = data

  const current = task.stages.find((s) => s.status !== 'PASSED') ?? task.stages[task.stages.length - 1]
  const shown: StageState = task.stages.find((s) => s.stage === picked) ?? current

  async function act(fn: () => Promise<void>) {
    setActionError(null)
    try {
      await fn()
      setFeedback('')
      setChat('')
      reload()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e))
    }
  }

  const stageEvents = events.filter((e) => e.stage === shown.stage).slice(-150)

  return (
    <>
      <p><a href="#/" className="back">← 목록</a></p>
      <h1 className="page-title">{task.feature} <StatusBadge value={task.status} /></h1>
      <p className="muted pre">{task.requirement}</p>
      {task.branch && <p><small>브랜치: <code>{task.branch}</code></small></p>}
      {!!task.costUsd && (
        <p><small>
          Claude 비용 {usd(task.costUsd)}
          {Object.entries(task.costByModel ?? {}).map(([m, c]) => ` · ${modelLabel(m)} ${usd(c)}`)}
        </small></p>
      )}

      <ol className="stepper">
        {task.stages.map((s) => (
          <li key={s.stage} className={s.stage === shown.stage ? 'active' : ''}>
            <button className="link" onClick={() => setPicked(s.stage)}>
              {STAGE_LABEL[s.stage]}
              <StatusBadge value={s.status} />
              {s.attempt > 1 && <small>시도 {s.attempt}</small>}
              {!!s.costUsd && <small>{usd(s.costUsd)}</small>}
            </button>
          </li>
        ))}
      </ol>


      {shown.stage === 'REQUIREMENTS' && (shown.messages.length > 0 || shown.conversable) && (
        <section className="card chat">
          <h2>요구사항 다듬기</h2>
          <p className="muted small">승인하기 전에 말을 걸어 요구사항을 고칠 수 있습니다 (최대 10번)</p>
          <ul className="plain bubbles">
            {shown.messages.map((m, i) => <li key={i} className="bubble me pre">{m}</li>)}
            {shown.status === 'RUNNING' && shown.messages.length > 0 && <li className="bubble ai typing">수정하는 중…</li>}
          </ul>
          {shown.conversable && (
            <form className="chat-input" onSubmit={(e) => { e.preventDefault(); if (chat.trim()) act(() => api.sendMessage(id, chat.trim())) }}>
              <textarea rows={2} placeholder={shown.messages.length >= 10 ? '대화 횟수를 모두 썼습니다' : '예: 로그인 실패 시 잠금 조건을 추가해 줘'}
                value={chat} disabled={shown.messages.length >= 10} onChange={(e) => setChat(e.target.value)} />
              <button disabled={!chat.trim() || shown.messages.length >= 10}>보내기</button>
            </form>
          )}
        </section>
      )}

      {shown.status === 'WAITING_APPROVAL' && (
        <section className="card block">
          <strong>승인이 필요합니다</strong>
          <small>해시 {shown.artifactHash?.slice(0, 12)}</small>
          <textarea rows={3} placeholder="거절 시 수정 요청 사항" value={feedback} onChange={(e) => setFeedback(e.target.value)} />
          <div className="row">
            <button onClick={() => act(() => api.approve(id))}>승인</button>
            <button className="secondary" disabled={!feedback.trim()} onClick={() => act(() => api.reject(id, feedback))}>거절 후 수정 요청</button>
          </div>
        </section>
      )}

      {task.status === 'BLOCKED' && (
        <section className="card block">
          <strong>작업이 차단되었습니다</strong>
          <button onClick={() => act(() => api.retry(id))}>실패한 단계 다시 시도</button>
        </section>
      )}
      {actionError && <p className="err">{actionError}</p>}

      {shown.error && <pre className="err-box">{shown.error}</pre>}

      {Object.keys(shown.validation).length > 0 && (
        <p className="validation">
          {Object.entries(shown.validation).map(([k, v]) => (
            <span key={k}>{k} <StatusBadge value={v} /></span>
          ))}
        </p>
      )}

      <ul className="plain files">
        {Object.keys(shown.artifacts).map((path) => (
          <li key={path}><button className="link" onClick={() => setViewing(path)}>{path}</button></li>
        ))}
        {shown.diff && <li><button className="link" onClick={() => setViewing(DIFF)}>diff</button></li>}
        {shown.routingLog && shown.routingLog.length > 0 && (
          <li><button className="link" onClick={() => setViewing(ROUTING)}>모델 선택 이력</button></li>
        )}
        <li><button className="link" onClick={() => setViewing(LOG)}>진행 로그 ({stageEvents.length})</button></li>
      </ul>

      {viewing === ROUTING && (
        <Dialog title="모델 선택 이력" onClose={() => setViewing(null)}>
          <ul className="plain">{(shown.routingLog ?? []).map((l, i) => <li key={i}><small>{l}</small></li>)}</ul>
        </Dialog>
      )}
      {viewing === LOG && (
        <Dialog title={`진행 로그 (${stageEvents.length})`} onClose={() => setViewing(null)}>
          <ul className="plain log">
            {stageEvents.map((e, i) => (
              <li key={i} className={e.type}>
                <small className="time">{new Date(e.at).toLocaleTimeString()}</small>
                {e.type === 'tool' ? <span className="tool">{summarizeTool(e.data)}</span>
                  : e.type === 'text' ? <div className="said pre">{e.data}</div>
                  : <span className="pre">{e.type === 'result' ? summarizeResult(e.data) : e.data}</span>}
              </li>
            ))}
          </ul>
        </Dialog>
      )}
      {viewing === DIFF && (
        <Dialog title="diff" onClose={() => setViewing(null)}><pre>{shown.diff}</pre></Dialog>
      )}
      {viewing && viewing in shown.artifacts && (
        <Dialog title={viewing} onClose={() => setViewing(null)}>
          {viewing.endsWith('.md')
            ? <div className="markdown"><Markdown remarkPlugins={[remarkGfm]}>{shown.artifacts[viewing]}</Markdown></div>
            : <pre>{shown.artifacts[viewing]}</pre>}
        </Dialog>
      )}
    </>
  )
}

function summarizeResult(raw: string): string {
  try {
    const r = JSON.parse(raw)
    const u = r.usage ?? {}
    const tokens = (u.input_tokens ?? 0) + (u.output_tokens ?? 0)
    return `완료 · 비용 $${(r.total_cost_usd ?? 0).toFixed(4)} · 턴 ${r.num_turns ?? '?'} · 토큰 ${tokens}`
  } catch {
    return raw.slice(0, 200)
  }
}

const TOOL_LABEL: Record<string, string> = {
  Read: '읽음', Write: '작성', Edit: '수정', Glob: '파일 찾기', Grep: '검색', Bash: '명령 실행',
}

/** 도구 호출 JSON(길면 중간에 잘려 있다)에서 이름과 대상만 뽑아 한 줄로 보여 준다. */
function summarizeTool(raw: string): string {
  const [name, ...rest] = raw.split(' ')
  const json = rest.join(' ')
  const pick = (key: string) => json.match(new RegExp(`"${key}":"((?:[^"\\\\]|\\\\.)*)`))?.[1]
  const target = pick('file_path')?.replace(/^.*\/worktrees\/[^/]+\//, '') ?? pick('pattern') ?? pick('command')
  return `${TOOL_LABEL[name] ?? name}${target ? ` ${target}` : ''}`
}
