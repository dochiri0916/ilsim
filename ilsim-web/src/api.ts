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

export interface AuthState {
  setup: boolean
  user?: string
}

export const api = {
  me: () => call<AuthState>('/api/auth/me'),
  setup: (username: string, password: string) =>
    call<AuthState>('/api/auth/setup', { method: 'POST', body: JSON.stringify({ username, password }) }),
  login: (username: string, password: string) =>
    call<AuthState>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password }) }),
  logout: () => post('/api/auth/logout'),
  accounts: () => call<string[]>('/api/accounts'),
  createAccount: (username: string, password: string) =>
    call<{ username: string }>('/api/accounts', { method: 'POST', body: JSON.stringify({ username, password }) }),
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
