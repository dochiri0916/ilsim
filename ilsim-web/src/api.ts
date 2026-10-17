export type StageStatus = 'PENDING' | 'RUNNING' | 'WAITING_APPROVAL' | 'PASSED' | 'FAILED'

export interface StageState {
  stage: 'REQUIREMENTS' | 'TEST_CASES' | 'TEST_CODE' | 'IMPLEMENTATION'
  status: StageStatus
  attempt: number
  feedback: string | null
  error: string | null
  diff: string | null
  artifactHash: string | null
  artifacts: Record<string, string>
  validation: Record<string, string>
  routing?: Record<string, string>
  routingLog?: string[]
  costUsd?: number
  costByModel?: Record<string, number>
  /** 요구사항 단계에서 사용자가 보낸 대화 메시지와, 지금 말을 걸 수 있는지 */
  messages: string[]
  conversable: boolean
}

export interface Task {
  id: string
  projectId: string
  feature: string
  requirement: string
  createdAt: string
  status: 'ACTIVE' | 'COMPLETED' | 'BLOCKED'
  branch: string | null
  stages: StageState[]
  costUsd?: number
  costByModel?: Record<string, number>
}

export interface TaskEvent {
  at: string
  stage: string
  type: string
  data: string
}

export interface Project {
  id: string
  name: string
  runnerId: string
  lastSeen: string | null
  online: boolean
}

export class Unauthorized extends Error {}

async function call<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(path, {
    ...init,
    headers: { 'Content-Type': 'application/json', 'X-Requested-With': 'ilsim', ...init?.headers },
  })
  if (res.status === 401 && !path.startsWith('/api/auth/')) throw new Unauthorized()
  if (!res.ok) {
    const body = await res.json().catch(() => ({}))
    throw new Error(body.error ?? `HTTP ${res.status}`)
  }
  const text = await res.text()
  return (text ? JSON.parse(text) : undefined) as T
}

const post = (path: string, body?: unknown) =>
  call<void>(path, { method: 'POST', body: body === undefined ? undefined : JSON.stringify(body) })

export interface Settings {
  jev: { configured: boolean; last4: string | null; updatedAt: string | null }
}

export interface RuleView {
  id: string
  kind: string
  description: string
  severity: string | null
  approved: boolean
  rejected: boolean
  decidedBy: string | null
  reviewVerdict: string | null
  reviewReason: string | null
}

export interface MemoryView {
  id: string
  kind: string
  ruleId: string
  file: string
  summary: string
  recommendation: string
  status: 'CANDIDATE' | 'ACTIVE' | 'SUPERSEDED' | string
  createdAt: string | null
  approvedAt: string | null
  supersededReason: string | null
}

export interface Knowledge {
  architecture: RuleView[]
  convention: RuleView[]
  memory: MemoryView[]
}

export interface Stats {
  tasks: number
  costUsd: number
  byModel: Record<string, { stages: number; costUsd: number }>
  byStage: { stage: string; stages: number; averageAttempts: number; costUsd: number }[]
  escalatedStages: number
}

export interface Analysis {
  projectId: string
  status: 'REQUESTED' | 'RUNNING' | 'DONE' | 'FAILED'
  requestedAt: string
  startedAt: string | null
  finishedAt: string | null
  report: string | null
  error: string | null
}

export type RuleSet = 'architecture' | 'convention'

export interface AuthState {
  user?: string
}

export const api = {
  me: () => call<AuthState>('/api/auth/me'),
  login: (username: string, password: string) =>
    call<AuthState>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => post('/api/auth/logout'),
  sendMessage: (taskId: string, text: string) => post(`/api/tasks/${taskId}/messages`, { text }),
  changePassword: (currentPassword: string, newPassword: string) =>
    post('/api/auth/password', { currentPassword, newPassword }),
  stats: () => call<Stats>('/api/stats'),
  knowledge: (projectId: string) => call<Knowledge>(`/api/projects/${projectId}/knowledge`),
  decideRules: (projectId: string, set: RuleSet, ids: string[], decision: 'approve' | 'reject') =>
    post(`/api/projects/${projectId}/knowledge/rules/${set}/${decision}`, { ids }),
  decideMemory: (projectId: string, ids: string[], decision: 'approve' | 'reject') =>
    post(`/api/projects/${projectId}/knowledge/memory/${decision}`, { ids }),
  analysis: (projectId: string) => call<Analysis | undefined>(`/api/projects/${projectId}/knowledge/analysis`),
  analyze: (projectId: string) => call<Analysis>(`/api/projects/${projectId}/knowledge/analysis`, { method: 'POST' }),
  settings: () => call<Settings>('/api/settings'),
  saveJevKey: (key: string) => call<Settings>('/api/settings/jev-key', { method: 'PUT', body: JSON.stringify({ key }) }),
  deleteJevKey: () => call<Settings>('/api/settings/jev-key', { method: 'DELETE' }),
  projects: () => call<Project[]>('/api/projects'),
  tasks: () => call<Task[]>('/api/tasks'),
  task: (id: string) => call<{ task: Task; events: TaskEvent[] }>(`/api/tasks/${id}`),
  createTask: (projectId: string, feature: string, requirement: string) =>
    call<Task>('/api/tasks', { method: 'POST', body: JSON.stringify({ projectId, feature, requirement }) }),
  approve: (id: string) => post(`/api/tasks/${id}/approve`),
  reject: (id: string, feedback: string) => post(`/api/tasks/${id}/reject`, { feedback }),
  retry: (id: string) => post(`/api/tasks/${id}/retry`),
}
