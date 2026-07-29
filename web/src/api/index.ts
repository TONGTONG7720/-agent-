import { http } from './http'

export interface Project {
  id: number
  name: string
  ownerId: number
  createdAt: string
}

export interface Task {
  id: number
  projectId: number
  requirement: string
  status: 'pending' | 'running' | 'waiting_review' | 'done' | 'failed' | 'canceled'
  autoMode: boolean
  currentNode: string | null
  createdBy: number
  createdAt: string
}

export interface TaskEventDto {
  id?: number
  event: string
  task_id?: number
  taskId?: number
  agent: string | null
  seq: number
  data: Record<string, unknown> | string
  ts: number
}

export interface Artifact {
  id: number
  taskId: number
  name: string
  type: string
  path: string
  version: number
}

export interface LlmModelView {
  id: number
  name: string
  litellmModelName: string
  enabled: boolean
  apiKeyMasked: string
}

export interface RoleConfig {
  id: number
  role: string
  systemPrompt: string | null
  defaultModelId: number | null
}

export const api = {
  login: (username: string, password: string) =>
    http.post<{ token: string; username: string; role: string }>('/api/auth/login', { username, password }),

  listProjects: () => http.get<Project[]>('/api/projects'),
  createProject: (name: string) => http.post<Project>('/api/projects', { name }),

  listTasks: (projectId: number) => http.get<Task[]>(`/api/tasks?projectId=${projectId}`),
  getTask: (id: number) => http.get<Task>(`/api/tasks/${id}`),
  createTask: (projectId: number, requirement: string, autoMode: boolean) =>
    http.post<Task>('/api/tasks', { projectId, requirement, autoMode }),
  listEvents: (taskId: number, afterSeq: number) =>
    http.get<TaskEventDto[]>(`/api/tasks/${taskId}/events?afterSeq=${afterSeq}`),
  approve: (taskId: number, decision: 'pass' | 'reject', comment: string, target?: string) =>
    http.post<void>(`/api/tasks/${taskId}/approve`, { decision, comment, target: target ?? null }),
  retryTask: (taskId: number) => http.post<void>(`/api/tasks/${taskId}/retry`),
  iterateTask: (taskId: number, feedback: string) =>
    http.post<void>(`/api/tasks/${taskId}/iterate`, { feedback }),
  cancelTask: (taskId: number) => http.post<void>(`/api/tasks/${taskId}/cancel`),
  listArtifacts: (taskId: number) => http.get<Artifact[]>(`/api/tasks/${taskId}/artifacts`),
  artifactContent: (id: number) => http.get<string>(`/api/artifacts/${id}/content`),

  listModels: () => http.get<LlmModelView[]>('/api/models'),
  createModel: (name: string, litellmModelName: string, apiKey: string) =>
    http.post<LlmModelView>('/api/models', { name, litellmModelName, apiKey }),
  listRoleConfigs: () => http.get<RoleConfig[]>('/api/role-configs'),
  updateRoleConfig: (role: string, systemPrompt: string | null, defaultModelId: number | null) =>
    http.put<RoleConfig>(`/api/role-configs/${role}`, { systemPrompt, defaultModelId })
}
