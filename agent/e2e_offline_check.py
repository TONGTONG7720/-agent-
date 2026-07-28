"""离线链路联调（无模型Key）：验证 鉴权/任务启动/SSE事件链路。
预期：网关未启动 → PM 节点 LLM 调用失败 → 收到 task_failed 事件。
用法: .venv\\Scripts\\python e2e_offline_check.py
"""
import json
import sys

import httpx

BASE = "http://localhost:8001"
HEADERS = {"X-Internal-Token": "change-me"}
TASK_ID = "OFFLINE-E2E-001"

# 1. 鉴权：无 token 应 401
r = httpx.post(f"{BASE}/agent/tasks", json={"task_id": "x", "requirement": "x"})
assert r.status_code == 401, f"鉴权失效! got {r.status_code}"
print("[1/3] 无token返回401 ✓")

# 2. 启动任务应 200
r = httpx.post(f"{BASE}/agent/tasks", headers=HEADERS, json={
    "task_id": TASK_ID, "requirement": "写一个计算器", "auto_mode": True})
assert r.status_code == 200, f"启动失败! {r.status_code}: {r.text}"
print("[2/3] 任务启动200 ✓")

# 3. SSE 应在重试耗尽后收到 task_failed（网关不可达）
events = []
with httpx.stream("GET", f"{BASE}/agent/tasks/{TASK_ID}/stream",
                  headers=HEADERS, timeout=300) as resp:
    for line in resp.iter_lines():
        if not line.startswith("data:"):
            continue
        ev = json.loads(line[5:])
        events.append(ev["event"])
        print(f"    事件: seq={ev['seq']} {ev['event']}")
        if ev["event"] in ("task_done", "task_failed"):
            if ev["event"] == "task_failed":
                print(f"    error: {str(ev['data'].get('error'))[:120]}")
            break

assert events[-1] == "task_failed", f"预期task_failed, got {events}"
print("[3/3] 收到task_failed事件（网关不可达按预期兜底）✓")
print("\n离线链路联调通过：鉴权/编排启动/异常兜底/SSE事件链路全部正常")
sys.exit(0)
