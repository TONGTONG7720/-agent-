import { beforeEach, describe, expect, it } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { useTaskEventsStore } from '../src/stores/taskEvents'
import type { TaskEventDto } from '../src/api'

function ev(seq: number, event: string, agent: string | null = 'pm',
            data: Record<string, unknown> = {}): TaskEventDto {
  return { event, agent, seq, data, ts: seq * 1000 }
}

describe('taskEvents store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
  })

  it('pushEvent 按 seq 排序插入且重复 seq 忽略', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(2, 'node_end'))
    s.pushEvent(ev(1, 'agent_message', 'pm', { content: 'PRD' }))
    s.pushEvent(ev(2, 'node_end'))          // 重复
    expect(s.events.map(e => e.seq)).toEqual([1, 2])
    expect(s.maxSeq).toBe(2)
  })

  it('mergeHistory 与实时事件合并无重复', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(3, 'agent_message'))
    s.mergeHistory([ev(1, 'agent_message'), ev(2, 'node_end'), ev(3, 'agent_message')])
    expect(s.events.map(e => e.seq)).toEqual([1, 2, 3])
  })

  it('interrupt 置 pendingGate，task_done 清除并置终态', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(1, 'interrupt', null, { gate: 'prd_gate', question: '请确认 PRD' }))
    expect(s.pendingGate?.gate).toBe('prd_gate')
    expect(s.finished).toBe(false)
    s.pushEvent(ev(2, 'task_done', null, { review_passed: true }))
    expect(s.pendingGate).toBeNull()
    expect(s.finished).toBe(true)
    expect(s.failed).toBe(false)
  })

  it('task_failed 置失败终态', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(1, 'task_failed', null, { error: 'boom' }))
    expect(s.finished).toBe(true)
    expect(s.failed).toBe(true)
  })

  it('messages 按 agent 聚合 agent_message 内容', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(1, 'agent_message', 'pm', { content: 'PRD内容' }))
    s.pushEvent(ev(2, 'node_end', 'pm', { node: 'pm' }))
    s.pushEvent(ev(3, 'agent_message', 'coder', { content: '代码说明' }))
    expect(s.messages).toEqual([
      { agent: 'pm', content: 'PRD内容', seq: 1 },
      { agent: 'coder', content: '代码说明', seq: 3 }
    ])
    expect(s.currentAgent).toBe('coder')
  })

  it('reset 清空全部状态', () => {
    const s = useTaskEventsStore()
    s.pushEvent(ev(1, 'interrupt', null, { gate: 'prd_gate' }))
    s.reset()
    expect(s.events).toEqual([])
    expect(s.pendingGate).toBeNull()
    expect(s.maxSeq).toBe(0)
  })
})
