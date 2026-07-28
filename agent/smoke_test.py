"""端到端冒烟：需要 LiteLLM 网关已运行且 AGENT_LLM_BASE_URL 正确。
用法: .venv\\Scripts\\python smoke_test.py
"""
import json
import threading

import httpx

BASE = "http://localhost:8001"
HEADERS = {"X-Internal-Token": "change-me"}
TASK_ID = "SMOKE-001"


def listen():
    with httpx.stream("GET", f"{BASE}/agent/tasks/{TASK_ID}/stream",
                      headers=HEADERS, timeout=600) as resp:
        for line in resp.iter_lines():
            if line.startswith("data:"):
                ev = json.loads(line[5:])
                print(f"[{ev['seq']:03d}] {ev['event']:18s} {ev.get('agent') or ''}")
                if ev["event"] in ("task_done", "task_failed"):
                    print(json.dumps(ev["data"], ensure_ascii=False, indent=2))
                    return


t = threading.Thread(target=listen)
r = httpx.post(f"{BASE}/agent/tasks", headers=HEADERS, json={
    "task_id": TASK_ID, "requirement": "写一个Python计算器函数，支持加减乘除",
    "auto_mode": True})
print("start:", r.json())
t.start()
t.join()
