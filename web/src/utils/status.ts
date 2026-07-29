import type { Task } from '../api'
import avatarPm from '../assets/avatar-pm.png'
import avatarArchitect from '../assets/avatar-architect.png'
import avatarCoder from '../assets/avatar-coder.png'
import avatarTester from '../assets/avatar-tester.png'
import avatarReviewer from '../assets/avatar-reviewer.png'

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

/** 自绘状态胶囊的样式 class（.chip.<status>，定义在 theme.css）。 */
export function statusClass(s: Task['status']): string {
  return `chip ${s}`
}

export const AGENT_META: Record<string, { name: string; avatar: string }> = {
  pm: { name: '产品经理', avatar: avatarPm },
  architect: { name: '架构师', avatar: avatarArchitect },
  coder: { name: '开发工程师', avatar: avatarCoder },
  tester: { name: '测试工程师', avatar: avatarTester },
  reviewer: { name: '代码审查员', avatar: avatarReviewer }
}

export const AGENT_ORDER = ['pm', 'architect', 'coder', 'tester', 'reviewer']

export const GATE_TEXT: Record<string, string> = {
  prd_gate: '请确认 PRD',
  design_gate: '请确认设计文档',
  accept_gate: '请最终验收'
}

export const ARTIFACT_TYPE_TEXT: Record<string, string> = {
  prd: 'PRD',
  design: '设计',
  code: '代码',
  test_report: '报告'
}
