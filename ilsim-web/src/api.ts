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
  unknownCalls?: number
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
  costIsUnverified?: boolean
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

export interface RoutingAdjustment { model: string; effort: string; enabled: boolean; successOffset: number }

export interface RoutingAdjustments { version: string; items: RoutingAdjustment[]; note: string | null; createdAt: string | null }

export interface OptionReport {
  model: string; effort: string; executions: number; passed: number; passRate: number | null
  averagePredicted: number | null; calibrationGap: number | null; brier: number | null
  averageActualCostUsd: number | null; averageExpectedCostUsd: number | null; over: number; under: number; recommendedOffset: number | null
}

export interface ExecutionReview {
  taskId: string; stage: string; executionId: string; model: string; effort: string; workload: string
  predictedSuccess: number | null; passed: boolean; failureKind: string | null; actualCostUsd: number | null; expectedCostUsd: number | null
  verdict: 'FIT' | 'OVER' | 'UNDER' | 'UNKNOWN'; cheaperOption: string | null; adjustmentVersion: string | null
}

export interface RoutingOverview {
  report: {
    options: OptionReport[]
    workloads: { workload: string; executions: number; mostUsed: string | null; cheapestQualified: string | null }[]
    recent: ExecutionReview[]
    verdicts: Partial<Record<ExecutionReview['verdict'], number>>
    minimumSamples: number
    qualityThreshold: number
  }
  current: RoutingAdjustments
  history: RoutingAdjustments[]
  models: Record<string, string>
  upgrades: string[]
  locked: string[]
}

export interface Settings {
  jev: { configured: boolean; last4: string | null; updatedAt: string | null }
}

export interface ClaudeEnvironment {
  claudeMd: string
  settingsJson: string
  version: string
  updatedAt: string | null
}

export interface ClaudeAccount {
  runnerId: string
  name: string
  loggedIn: boolean
  email: string | null
  plan: string | null
  usable: boolean
  reportedAt: string | null
}

export interface ClaudeLogin {
  id: string
  runnerId: string
  name: string
  status: 'REQUESTED' | 'WAITING_CODE' | 'CODE_SUBMITTED' | 'DONE' | 'FAILED'
  requestedAt: string
  url: string | null
  error: string | null
  email: string | null
  plan: string | null
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
  principle: string | null
  rationale: string | null
  compliant: number
  total: number
  examples: string[]
  recommended: boolean
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
  completedTasks: number
  costUsd: number
  byModel: Record<string, { stages: number; costUsd: number }>
  byStage: { stage: string; stages: number; averageAttempts: number; costUsd: number }[]
  escalatedStages: number
  unknownCalls: number
  costIsLowerBound: boolean
  analysisCostUsd: number
  decisionsByHandler: Record<string, number>
  runningExecutions: number
  legacyCostUsd: number
  costIsUnverified: boolean
  byPurpose: Record<string, { calls: number; costUsd: number; unknownCalls: number; inputTokens: number; outputTokens: number; cacheReadTokens: number; cacheWriteTokens: number; toolCalls: number; questions: number }>
  byCombination: { model: string; effort: string; stage: string; executions: number; successRate: number | null; costUsd: number; unknownCalls: number }[]
  reviewProfiles: { purpose: string; workload: string; model: string; effort: string; samples: number; passed: number; missed: number; falsePositives: number; meanCostUsd: number | null; eligible: boolean }[]
  reviewSamplesReserved: number
  reviewSampleBudgetReservedUsd: number
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

export interface UsageWindow {
  utilization: number
  resetsAt: string | null
}

export interface PlanUsage {
  runnerId: string
  provider: string
  status: string | null
  fiveHour: UsageWindow | null
  sevenDay: UsageWindow | null
  observedAt: string | null
  refreshRequestedAt: string | null
}

export type RuleSet = 'architecture' | 'convention'

export interface AuthState {
  user?: string
  otpRequired?: boolean
  twoFactor?: boolean | null
}

export const api = {
  me: () => call<AuthState>('/api/auth/me'),
  login: (username: string, password: string, remember: boolean) =>
    call<AuthState>('/api/auth/login', { method: 'POST', body: JSON.stringify({ username, password, remember }) }),
  otp: (code: string) => call<AuthState>('/api/auth/otp', { method: 'POST', body: JSON.stringify({ code }) }),
  totpSetup: () => call<{ secret: string; uri: string }>('/api/auth/totp/setup', { method: 'POST' }),
  totpEnable: (code: string) => post('/api/auth/totp/enable', { code }),
  totpDisable: (code: string) => post('/api/auth/totp/disable', { code }),
  logout: () => post('/api/auth/logout'),
  sendMessage: (taskId: string, text: string) => post(`/api/tasks/${taskId}/messages`, { text }),
  changePassword: (currentPassword: string, newPassword: string) =>
    post('/api/auth/password', { currentPassword, newPassword }),
  routing: (projectId?: string) => call<RoutingOverview>(`/api/routing${projectId ? `?projectId=${encodeURIComponent(projectId)}` : ''}`),
  saveRouting: (items: RoutingAdjustment[], note: string) =>
    call<RoutingAdjustments>('/api/routing/adjustments', { method: 'PUT', body: JSON.stringify({ items, note }) }),
  applyRecommendedRouting: () => call<RoutingAdjustments>('/api/routing/adjustments/recommended', { method: 'POST' }),
  restoreRouting: (version: string) => call<RoutingAdjustments>(`/api/routing/adjustments/${encodeURIComponent(version)}/restore`, { method: 'POST' }),
  stats: (projectId: string) => call<Stats>(`/api/stats?projectId=${encodeURIComponent(projectId)}`),
  usage: () => call<PlanUsage[]>('/api/usage'),
  refreshUsage: (runnerId: string) => call<PlanUsage>(`/api/usage/${encodeURIComponent(runnerId)}/refresh`, { method: 'POST' }),
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
  claudeEnvironment: () => call<ClaudeEnvironment>('/api/claude/environment'),
  saveClaudeEnvironment: (claudeMd: string, settingsJson: string) =>
    call<ClaudeEnvironment>('/api/claude/environment', { method: 'PUT', body: JSON.stringify({ claudeMd, settingsJson }) }),
  claudeAccounts: () => call<{ accounts: ClaudeAccount[]; logins: ClaudeLogin[] }>('/api/claude/accounts'),
  startClaudeLogin: (runnerId: string, name: string) => call<ClaudeLogin>('/api/claude/logins', { method: 'POST', body: JSON.stringify({ runnerId, name }) }),
  submitClaudeCode: (id: string, code: string) => call<ClaudeLogin>(`/api/claude/logins/${id}/code`, { method: 'POST', body: JSON.stringify({ code }) }),
  removeClaudeAccount: (runnerId: string, name: string) =>
    call<void>(`/api/claude/accounts/${encodeURIComponent(runnerId)}/${encodeURIComponent(name)}`, { method: 'DELETE' }),
  projects: () => call<Project[]>('/api/projects'),
  tasks: () => call<Task[]>('/api/tasks'),
  task: (id: string) => call<{ task: Task; events: TaskEvent[] }>(`/api/tasks/${id}`),
  createTask: (projectId: string, feature: string, requirement: string, continueFrom?: string) =>
    call<Task>('/api/tasks', { method: 'POST', body: JSON.stringify({ projectId, feature, requirement, continueFrom }) }),
  approve: (id: string) => post(`/api/tasks/${id}/approve`),
  reject: (id: string, feedback: string) => post(`/api/tasks/${id}/reject`, { feedback }),
  retry: (id: string, instruction?: string) => post(`/api/tasks/${id}/retry`, { instruction }),
  stop: (id: string) => post(`/api/tasks/${id}/stop`),
  deleteTask: (id: string) => call<void>(`/api/tasks/${id}`, { method: 'DELETE' }),
}
