import type { Task } from '../api'

const STATUS_TEXT: Record<Task['status'], string> = {
  pending: '待启动',
  running: '执行中',
  waiting_review: '待人审',
  done: '已完成',
  failed: '失败',
  canceled: '已取消'
}

const STATUS_TYPE: Record<Task['status'], 'primary' | 'success' | 'warning' | 'danger' | 'info'> = {
  pending: 'info',
  running: 'primary',
  waiting_review: 'warning',
  done: 'success',
  failed: 'danger',
  canceled: 'info'
}

export function statusText(s: Task['status']): string {
  return STATUS_TEXT[s] ?? s
}

export function statusType(s: Task['status']): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  return STATUS_TYPE[s] ?? 'info'
}

export const AGENT_META: Record<string, { name: string; icon: string }> = {
  pm: { name: '产品经理', icon: '🧭' },
  architect: { name: '架构师', icon: '🏗️' },
  coder: { name: '开发工程师', icon: '💻' },
  tester: { name: '测试工程师', icon: '🧪' },
  reviewer: { name: '代码审查员', icon: '🔍' }
}

export const AGENT_ORDER = ['pm', 'architect', 'coder', 'tester', 'reviewer']

export const GATE_TEXT: Record<string, string> = {
  prd_gate: '请确认 PRD',
  design_gate: '请确认设计文档',
  accept_gate: '请最终验收'
}
