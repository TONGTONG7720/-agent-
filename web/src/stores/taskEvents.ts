import { defineStore } from 'pinia'
import { api, type TaskEventDto } from '../api'
import { getToken } from '../api/http'

export interface GateInfo {
  gate: string
  question: string
  payload?: Record<string, unknown>
}

export interface AgentMessage {
  agent: string
  content: string
  seq: number
}

function dataOf(e: TaskEventDto): Record<string, unknown> {
  return typeof e.data === 'string'
    ? (JSON.parse(e.data || '{}') as Record<string, unknown>)
    : (e.data ?? {})
}

export const useTaskEventsStore = defineStore('taskEvents', {
  state: () => ({
    events: [] as TaskEventDto[],
    pendingGate: null as GateInfo | null,
    finished: false,
    failed: false,
    source: null as EventSource | null,
    taskId: 0
  }),
  getters: {
    maxSeq: (s) => (s.events.length ? s.events[s.events.length - 1].seq : 0),
    messages(s): AgentMessage[] {
      return s.events
        .filter((e) => e.event === 'agent_message' && e.agent)
        .map((e) => ({
          agent: e.agent as string,
          content: String(dataOf(e).content ?? ''),
          seq: e.seq
        }))
    },
    currentAgent(s): string {
      for (let i = s.events.length - 1; i >= 0; i--) {
        if (s.events[i].agent) {
          return s.events[i].agent as string
        }
      }
      return ''
    },
    /** 最新的 token 累计用量（取带 input_tokens 的最大 seq 事件）。 */
    tokenUsage(s): { input: number; output: number } | null {
      for (let i = s.events.length - 1; i >= 0; i--) {
        const d = dataOf(s.events[i])
        if (d.input_tokens !== undefined) {
          return { input: Number(d.input_tokens), output: Number(d.output_tokens ?? 0) }
        }
      }
      return null
    }
  },
  actions: {
    /** 按 seq 有序插入，重复 seq 忽略；并根据事件类型推进状态。 */
    pushEvent(e: TaskEventDto) {
      const idx = this.events.findIndex((x) => x.seq >= e.seq)
      if (idx >= 0 && this.events[idx].seq === e.seq) {
        return
      }
      if (idx < 0) {
        this.events.push(e)
      } else {
        this.events.splice(idx, 0, e)
      }
      this.applyEffect(e)
    },

    /** 补拉的历史事件与已收实时事件合并（去重靠 pushEvent）。 */
    mergeHistory(history: TaskEventDto[]) {
      for (const e of history) {
        this.pushEvent(e)
      }
    },

    applyEffect(e: TaskEventDto) {
      const data = dataOf(e)
      switch (e.event) {
        case 'interrupt':
          this.pendingGate = {
            gate: String(data.gate ?? ''),
            question: String(data.question ?? ''),
            payload: data.payload as Record<string, unknown> | undefined
          }
          break
        case 'task_done':
          this.finished = true
          this.failed = false
          this.pendingGate = null
          break
        case 'task_failed':
          this.finished = true
          this.failed = true
          this.pendingGate = null
          break
      }
    },

    /** 审批提交后本地清除待审门（等待后续事件推进）。 */
    clearGate() {
      this.pendingGate = null
    },

    /** 进入工作台：先补历史，再接 SSE；断线自动重连并按 maxSeq 补拉。 */
    async connect(taskId: number) {
      this.reset()
      this.taskId = taskId
      const history = await api.listEvents(taskId, 0)
      this.mergeHistory(history)
      if (this.finished) {
        return
      }
      this.openSource()
    },

    openSource() {
      this.source?.close()
      const url = `/api/tasks/${this.taskId}/stream?satoken=${encodeURIComponent(getToken())}`
      const es = new EventSource(url)
      this.source = es
      const onEvent = (msg: MessageEvent) => {
        try {
          this.pushEvent(JSON.parse(msg.data as string) as TaskEventDto)
        } catch {
          /* 忽略坏帧 */
        }
        if (this.finished) {
          es.close()
        }
      }
      // 服务端用 event name 区分类型，统一监听全部已知类型
      for (const name of ['node_end', 'agent_message', 'artifact_created',
                          'interrupt', 'task_done', 'task_failed']) {
        es.addEventListener(name, onEvent)
      }
      es.onerror = () => {
        es.close()
        if (this.finished || this.taskId === 0) {
          return
        }
        // 断线：3秒后补拉缺口并重连
        setTimeout(async () => {
          if (this.taskId === 0) return
          try {
            this.mergeHistory(await api.listEvents(this.taskId, this.maxSeq))
          } catch {
            /* 下次重连再补 */
          }
          if (!this.finished) {
            this.openSource()
          }
        }, 3000)
      }
    },

    disconnect() {
      this.source?.close()
      this.source = null
      this.taskId = 0
    },

    reset() {
      this.disconnect()
      this.events = []
      this.pendingGate = null
      this.finished = false
      this.failed = false
    }
  }
})
