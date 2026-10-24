import { useEffect, useRef, useState } from 'react'
import Markdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import { api, type StageState, type TaskEvent } from './api'
import { usePoll } from './usePoll'
import { Dialog } from './Dialog'
import { StatusBadge } from './StatusBadge'
import { StageDots } from './StageDots'
import { STAGE_LABEL, modelLabel, taskTitle, usd } from './taskView'

const STOPPED = '사용자가 중지했습니다'
const ANSWERED_MARK = '[ILSIM:ANSWERED]'

type Viewing =
  | { kind: 'artifact'; stage: string; path: string }
  | { kind: 'diff'; stage: string }
  | { kind: 'validation' }
  | { kind: 'routing' }

export function TaskDetail({ id }: { id: string }) {
  const { data, error, reload } = usePoll(() => api.task(id), 2000, [id])
  const [chat, setChat] = useState('')
  const [actionError, setActionError] = useState<string | null>(null)
  const [viewing, setViewing] = useState<Viewing | null>(null)
  const logRef = useRef<HTMLUListElement>(null)
  const stick = useRef(true)
  const eventCount = data?.events.length ?? 0
  const loaded = !!data

  useEffect(() => { stick.current = true }, [id])

  useEffect(() => {
    const el = logRef.current
    if (el && stick.current) el.scrollTop = el.scrollHeight
  }, [eventCount, loaded, id])

  if (!data) return <p className="muted">{error ?? '불러오는 중…'}</p>
  const { task, events } = data

  const current: StageState | undefined = task.stages.find((s) => s.status !== 'PASSED')
  const blocked = task.status === 'BLOCKED'
  const waiting = current?.status === 'WAITING_APPROVAL'
  const working = task.status === 'ACTIVE' && (current?.status === 'RUNNING' || current?.status === 'PENDING')
  const done = task.status === 'COMPLETED'
  const canReply = blocked || waiting
  const answeredOnly = done && task.stages.some((s) => s.status !== 'PASSED')

  async function act(fn: () => Promise<void>) {
    setActionError(null)
    try {
      await fn()
      setChat('')
      reload()
    } catch (e) {
      setActionError(e instanceof Error ? e.message : String(e))
    }
  }

  function send() {
    const text = chat.trim()
    if (blocked) act(() => api.retry(id, text || undefined))
    else if (waiting && text) act(() => current?.conversable ? api.sendMessage(id, text) : api.reject(id, text))
  }

  const placeholder = blocked ? 'Claude에게 답하기 (비우고 보내면 처음부터 다시 시도)'
    : waiting ? (current?.conversable ? '요구사항에 대해 말하기 — 같은 세션에서 문서를 고칩니다' : '수정 요청 — 이 단계를 다시 작성합니다')
    : done ? '완료된 작업입니다. 이어서 할 일은 Claude 앱에서 요청하세요'
    : 'Claude가 작업 중입니다'

  return (
    <div className="task-chat">
      <header className="chat-head">
        <a href="#/" className="back">←</a>
        <h1 className="page-title" title={task.feature}>{taskTitle(task)} <StatusBadge value={task.status} /></h1>
        <StageDots task={task} />
        {working && (
          <button className="secondary stop" onClick={() => confirm('진행 중인 작업을 중지할까요?') && act(() => api.stop(id))}>중지</button>
        )}
        {!working && (
          <button className="secondary stop danger" onClick={() => {
            const msg = done
              ? '작업과 대화 기록을 삭제할까요? 브랜치(작업 결과)는 남깁니다.'
              : '작업과 대화 기록을 삭제할까요? 작업 브랜치는 저장소에 남습니다.'
            if (confirm(msg)) act(async () => { await api.deleteTask(id); location.hash = '#/' })
          }}>삭제</button>
        )}
      </header>

      <nav className="refs">
        {task.stages.flatMap((s) => Object.keys(s.artifacts).map((path) => (
          <button key={s.stage + path} className="chip" onClick={() => setViewing({ kind: 'artifact', stage: s.stage, path })}>
            {artifactLabel(path)}
          </button>
        )))}
        {task.stages.filter((s) => s.diff).map((s) => (
          <button key={'diff' + s.stage} className="chip" onClick={() => setViewing({ kind: 'diff', stage: s.stage })}>
            변경 · {STAGE_LABEL[s.stage]}
          </button>
        ))}
        {task.stages.some((s) => Object.keys(s.validation).length > 0) && (
          <button className="chip" onClick={() => setViewing({ kind: 'validation' })}>검증 결과</button>
        )}
        {task.stages.some((s) => s.routingLog?.length) && (
          <button className="chip" onClick={() => setViewing({ kind: 'routing' })}>모델 선택</button>
        )}
        {(!!task.costUsd || task.stages.some((s) => s.unknownCalls)) && (
          <small className="muted cost">
            {usd(task.costUsd) || '$0'}{task.costIsUnverified ? ' (집계 검증 필요)' : task.stages.some((s) => s.unknownCalls) ? ' 이상' : ''}{Object.entries(task.costByModel ?? {}).map(([m, c]) => ` · ${modelLabel(m)} ${usd(c)}`)}
          </small>
        )}
      </nav>

      <ul className="plain log transcript" ref={logRef}
        onScroll={(e) => { const el = e.currentTarget; stick.current = el.scrollHeight - el.scrollTop - el.clientHeight < 40 }}>
        <li className="message"><div className="mine pre">{task.requirement}</div></li>
        {groupEvents(events).map((g, i) => <Entry key={i} item={g} />)}
        {working && <li className="muted small typing">{current ? `${STAGE_LABEL[current.stage]} 작업 중…` : '작업 중…'}</li>}
        {blocked && current?.error === STOPPED && <li className="muted small">중지했습니다. 아래에 답하면 이어 갑니다.</li>}
        {done && (answeredOnly
          ? <li className="muted small">질문에 답했습니다 · 코드는 바꾸지 않았습니다</li>
          : <li className="muted small">모든 단계를 마쳤습니다{task.branch ? ` · 브랜치 ${task.branch}` : ''}</li>)}
      </ul>

      {waiting && current && (
        <div className="approve-bar">
          <span>{STAGE_LABEL[current.stage]} 승인 대기</span>
          {Object.keys(current.artifacts).filter((p) => p.endsWith('.md')).slice(0, 1).map((path) => (
            <button key={path} className="link" onClick={() => setViewing({ kind: 'artifact', stage: current.stage, path })}>문서 보기</button>
          ))}
          <button onClick={() => act(() => api.approve(id))}>승인</button>
        </div>
      )}
      {actionError && <p className="err">{actionError}</p>}
      <form className="chat-input" onSubmit={(e) => { e.preventDefault(); send() }}>
        <textarea rows={2} value={chat} disabled={!canReply} placeholder={placeholder} onChange={(e) => setChat(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey && !e.nativeEvent.isComposing) { e.preventDefault(); e.currentTarget.form?.requestSubmit() }
          }} />
        <button disabled={!canReply || (!blocked && !chat.trim())}>{blocked && !chat.trim() ? '다시 시도' : '보내기'}</button>
      </form>

      {viewing?.kind === 'artifact' && (
        <Dialog title={viewing.path} onClose={() => setViewing(null)}>
          {(() => {
            const text = task.stages.find((s) => s.stage === viewing.stage)?.artifacts[viewing.path] ?? ''
            return viewing.path.endsWith('.md')
              ? <div className="markdown"><Markdown remarkPlugins={[remarkGfm]}>{text}</Markdown></div>
              : <pre>{text}</pre>
          })()}
        </Dialog>
      )}
      {viewing?.kind === 'diff' && (
        <Dialog title={`변경 · ${STAGE_LABEL[viewing.stage]}`} onClose={() => setViewing(null)}>
          <pre>{task.stages.find((s) => s.stage === viewing.stage)?.diff}</pre>
        </Dialog>
      )}
      {viewing?.kind === 'validation' && (
        <Dialog title="검증 결과" onClose={() => setViewing(null)}>
          {task.stages.filter((s) => Object.keys(s.validation).length > 0).map((s) => (
            <section key={s.stage}>
              <h3>{STAGE_LABEL[s.stage]}</h3>
              <Lights validation={s.validation} />
            </section>
          ))}
        </Dialog>
      )}
      {viewing?.kind === 'routing' && (
        <Dialog title="모델 선택 이력" onClose={() => setViewing(null)}>
          {task.stages.filter((s) => s.routingLog?.length).map((s) => (
            <section key={s.stage}>
              <h3>{STAGE_LABEL[s.stage]}</h3>
              <ul className="plain">{(s.routingLog ?? []).map((l, i) => <li key={i}><small>{l}</small></li>)}</ul>
            </section>
          ))}
        </Dialog>
      )}
    </div>
  )
}

type Item = TaskEvent | TaskEvent[] | { divider: string }

function groupEvents(events: TaskEvent[]): Item[] {
  const out: Item[] = []
  let stage: string | null = null
  for (const e of events.slice(-600)) {
    if (e.stage && e.stage !== stage) {
      stage = e.stage
      out.push({ divider: STAGE_LABEL[e.stage] ?? e.stage })
    }
    const last = out[out.length - 1]
    if (e.type !== 'tool') out.push(e)
    else if (Array.isArray(last)) last.push(e)
    else out.push([e])
  }
  return out
}

function Entry({ item }: { item: Item }) {
  if ('divider' in item) return <li className="divider"><span>{item.divider}</span></li>
  if (Array.isArray(item)) {
    return (
      <li className="tools">
        <details>
          <summary>도구 {item.length}건 · {summarizeTool(item[item.length - 1].data)}</summary>
          <ul className="plain">{item.map((e, j) => <li key={j}><span className="tool">{summarizeTool(e.data)}</span></li>)}</ul>
        </details>
      </li>
    )
  }
  switch (item.type) {
    case 'text': return <li className="text"><div className="said markdown"><Markdown remarkPlugins={[remarkGfm]}>{item.data.replace(ANSWERED_MARK, '').trimEnd()}</Markdown></div></li>
    case 'message': return <li className="message"><div className="mine pre">{item.data}</div></li>
    case 'verdict': return <li className="verdict"><Verdict raw={item.data} /></li>
    case 'failure': return (
      <li className="failure">
        <details className="failed"><summary>실패 내용</summary><pre>{item.data}</pre></details>
      </li>
    )
    case 'result': return null
    default: return <li className="log"><small className="muted pre">{item.data}</small></li>
  }
}

function Verdict({ raw }: { raw: string }) {
  const [head, ...rest] = raw.split('\n')
  const validation = Object.fromEntries(rest.map((l) => l.split('=')).filter((p) => p.length === 2)) as Record<string, string>
  const passed = head === 'PASSED'
  return (
    <div className={`verdict-row ${passed ? 'ok' : 'bad'}`}>
      <span className={`light ${passed ? 'green' : 'red'}`} />
      <strong>{passed ? '검증 통과' : '검증 실패'}</strong>
      <Lights validation={validation} />
    </div>
  )
}

const LIGHT: Record<string, string> = {
  PASSED: 'green', RED: 'green', FAILED: 'red', CONCERN: 'amber', LOW: 'amber', UNKNOWN: 'gray', SKIPPED: 'gray',
}

const CHECK_LABEL: Record<string, string> = {
  tests: '테스트', analysis: '정적 분석', architecture: '아키텍처', convention: '컨벤션', risk: '트랜잭션·동시성',
  'test-integrity': '테스트 무결성', mutation: '변이 테스트', traceability: '추적성', 'ai-audit': 'AI 검토',
}

function Lights({ validation }: { validation: Record<string, string> }) {
  return (
    <span className="lights">
      {Object.entries(validation).map(([k, v]) => (
        <span key={k} className="check" title={`${k}: ${v}`}>
          <span className={`light ${LIGHT[v] ?? 'gray'}`} />{CHECK_LABEL[k] ?? k}
        </span>
      ))}
    </span>
  )
}

function artifactLabel(path: string): string {
  if (path.endsWith('requirements.md')) return '요구사항 문서'
  if (path.endsWith('test-spec.md')) return '테스트 케이스'
  if (path === 'ai-audit.md') return 'AI 검토'
  return path.split('/').pop() ?? path
}

const TOOL_LABEL: Record<string, string> = {
  Read: '읽음', Write: '작성', Edit: '수정', Glob: '파일 찾기', Grep: '검색', Bash: '명령 실행',
}

function summarizeTool(raw: string): string {
  const [name, ...rest] = raw.split(' ')
  const json = rest.join(' ')
  const pick = (key: string) => json.match(new RegExp(`"${key}":"((?:[^"\\\\]|\\\\.)*)`))?.[1]
  const target = pick('file_path')?.replace(/^.*\/worktrees\/[^/]+\//, '') ?? pick('pattern') ?? pick('command')
  return `${TOOL_LABEL[name] ?? name}${target ? ` ${target}` : ''}`
}
