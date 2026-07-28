# Agent 服务（agent/）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现多Agent软件开发协同平台的 Python Agent 服务：五角色（PM/架构师/Coder/Tester/Reviewer）LangGraph 状态图编排 + 人审中断点 + FastAPI 对内 API（启动/SSE事件流/resume/cancel）。

**Architecture:** FastAPI 提供内网 API，LangGraph StateGraph 编排五个角色节点与三个人审 interrupt 门，checkpoint 可插拔（测试用内存、生产用 MySQL）。LLM 通过工厂函数注入（生产指向 LiteLLM 网关的 OpenAI 兼容接口，测试用 FakeLLM），测试执行器同样注入，保证全部编排逻辑可离线单测。

**Tech Stack:** Python 3.11+, FastAPI, LangGraph (>=0.2.60), langchain-openai, pytest, uvicorn

**对应设计文档：** `docs/superpowers/specs/2026-07-28-multi-agent-dev-platform-design.md` 第 2.3、3、4.1、4.2(内网API)、5、6 节

**MVP 偏差说明（有意为之）：** SSE 事件 MVP 阶段不含 `node_start` 与 token 级流式 delta；`agent_message` 在节点结束时一次性携带完整 content（`data.delta` 等于 `data.content`）。协议字段不变，后续增强不破坏兼容。

---

## 文件结构

```
agent/
  requirements.txt
  app/
    __init__.py
    config.py          # 环境变量配置（AGENT_ 前缀）
    state.py           # GraphState 共享状态定义 + 代码块解析
    schemas.py         # API 请求/响应 + AgentEvent 事件模型
    llm.py             # LLM 工厂（OpenAI兼容 → LiteLLM 网关）
    sandbox.py         # subprocess 测试执行（白名单/限时/截断）
    workspace.py       # 任务工作目录与产物落盘
    graph/
      __init__.py
      prompts.py       # 五角色默认 system prompt
      nodes.py         # 角色节点工厂 + 人审门节点
      builder.py       # 状态图组装（边/条件路由/checkpointer）
    runner.py          # TaskManager：启动/事件队列/resume/cancel
    main.py            # FastAPI 路由 + SSE + 内部鉴权
  tests/
    conftest.py        # FakeLLM、fake test_runner 等公共 fixture
    test_state.py
    test_nodes.py
    test_graph_flow.py
    test_api.py
```

---

### Task 1: 项目骨架与配置

**Files:**
- Create: `agent/requirements.txt`, `agent/app/__init__.py`, `agent/app/config.py`, `agent/tests/__init__.py`(空), `.gitignore`(仓库根)
- Test: `agent/tests/test_config.py`

- [ ] **Step 1: 创建 requirements 与 .gitignore**

`agent/requirements.txt`:
```
fastapi>=0.115
uvicorn[standard]>=0.30
langgraph>=0.2.60
langgraph-checkpoint-mysql>=2.0
pymysql>=1.1
langchain-openai>=0.2
langchain-core>=0.3
pydantic-settings>=2.4
pytest>=8.0
httpx>=0.27
```

仓库根 `.gitignore`:
```
__pycache__/
*.pyc
.venv/
venv/
workspace/
.env
node_modules/
dist/
target/
.idea/
.vscode/
```

- [ ] **Step 2: 写失败测试**

`agent/tests/test_config.py`:
```python
from app.config import Settings

def test_defaults():
    s = Settings(_env_file=None)
    assert s.llm_base_url == "http://localhost:4000"
    assert s.max_fix_rounds == 3
    assert s.mysql_dsn == ""

def test_env_override(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_TOKEN", "secret-1")
    s = Settings(_env_file=None)
    assert s.internal_token == "secret-1"
```

- [ ] **Step 3: 运行确认失败**

Run: `cd agent; python -m venv .venv; .venv\Scripts\pip install -r requirements.txt; .venv\Scripts\python -m pytest tests/test_config.py -v`
Expected: FAIL（`ModuleNotFoundError: app.config`）

- [ ] **Step 4: 实现 config.py**

`agent/app/config.py`:
```python
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_", env_file=".env")

    llm_base_url: str = "http://localhost:4000"   # LiteLLM 网关
    llm_api_key: str = "sk-litellm"
    internal_token: str = "change-me"             # 与 SpringBoot 共享的内网密钥
    workspace_root: str = "./workspace"
    mysql_dsn: str = ""                           # 空 = 内存 checkpointer
    max_fix_rounds: int = 3
    test_timeout_seconds: int = 120


settings = Settings()
```

`agent/app/__init__.py` 与 `agent/tests/__init__.py` 为空文件。

- [ ] **Step 5: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_config.py -v` → Expected: 2 passed
```bash
git add agent .gitignore
git commit -m "feat(agent): 项目骨架与配置模块"
```

---

### Task 2: GraphState 与事件模型

**Files:**
- Create: `agent/app/state.py`, `agent/app/schemas.py`
- Test: `agent/tests/test_state.py`

- [ ] **Step 1: 写失败测试**

`agent/tests/test_state.py`:
```python
from app.state import parse_code_blocks
from app.schemas import AgentEvent


def test_parse_code_blocks():
    text = (
        "说明文字\n"
        "===FILE: calc.py===\n"
        "def add(a, b):\n    return a + b\n"
        "===END===\n"
        "===FILE: sub/util.py===\nX = 1\n===END===\n"
    )
    files = parse_code_blocks(text)
    assert files == [
        {"path": "calc.py", "content": "def add(a, b):\n    return a + b"},
        {"path": "sub/util.py", "content": "X = 1"},
    ]


def test_parse_code_blocks_empty():
    assert parse_code_blocks("没有代码块") == []


def test_agent_event_serialize():
    ev = AgentEvent(event="node_end", task_id="T1", agent="pm", seq=1, data={"x": 1})
    d = ev.model_dump()
    assert d["event"] == "node_end" and d["ts"] > 0
```

- [ ] **Step 2: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_state.py -v` → Expected: FAIL（模块不存在）

- [ ] **Step 3: 实现**

`agent/app/state.py`:
```python
import re
from typing import TypedDict


class CodeFile(TypedDict):
    path: str
    content: str


class GraphState(TypedDict, total=False):
    task_id: str
    requirement: str
    auto_mode: bool               # True = 跳过人审门
    role_models: dict[str, str]   # role -> litellm 模型名
    prd: str
    design_doc: str
    code_files: list[CodeFile]
    test_report: str
    review_comments: str
    review_passed: bool
    iteration_count: int          # Reviewer→Coder 返工轮数
    human_feedback: str           # 人审驳回意见，回传给上游节点


_BLOCK_RE = re.compile(r"===FILE: (.+?)===\n(.*?)\n===END===", re.DOTALL)


def parse_code_blocks(text: str) -> list[CodeFile]:
    """解析模型输出中的 ===FILE: path=== ... ===END=== 代码块。"""
    return [
        {"path": m.group(1).strip(), "content": m.group(2)}
        for m in _BLOCK_RE.finditer(text)
    ]
```

`agent/app/schemas.py`:
```python
import time

from pydantic import BaseModel, Field


def _now_ms() -> int:
    return int(time.time() * 1000)


class AgentEvent(BaseModel):
    """SSE 事件协议，见设计文档 4.1。"""
    event: str                    # node_end/agent_message/artifact_created/interrupt/task_done/task_failed
    task_id: str
    agent: str | None = None
    seq: int = 0
    data: dict = Field(default_factory=dict)
    ts: int = Field(default_factory=_now_ms)


class StartTaskRequest(BaseModel):
    task_id: str
    requirement: str
    auto_mode: bool = False
    role_models: dict[str, str] = Field(default_factory=dict)
    role_prompts: dict[str, str] = Field(default_factory=dict)  # 覆盖默认 prompt，可空


class ResumeRequest(BaseModel):
    decision: str                 # pass | reject
    comment: str = ""
```

- [ ] **Step 4: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_state.py -v` → Expected: 3 passed
```bash
git add agent/app/state.py agent/app/schemas.py agent/tests/test_state.py
git commit -m "feat(agent): GraphState 与事件/请求模型"
```

---

### Task 3: LLM 工厂、sandbox、workspace

**Files:**
- Create: `agent/app/llm.py`, `agent/app/sandbox.py`, `agent/app/workspace.py`
- Test: `agent/tests/test_sandbox.py`

- [ ] **Step 1: 写失败测试**

`agent/tests/test_sandbox.py`:
```python
import pytest

from app.sandbox import run_command
from app.workspace import write_files, task_dir


def test_run_command_ok(tmp_path):
    code, out = run_command(["python", "-c", "print('hi')"], cwd=str(tmp_path))
    assert code == 0 and "hi" in out


def test_run_command_rejects_non_whitelist(tmp_path):
    with pytest.raises(ValueError):
        run_command(["curl", "http://x"], cwd=str(tmp_path))


def test_write_files(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    paths = write_files("T1", [{"path": "a/b.py", "content": "X = 1"}])
    assert (tmp_path / "T1" / "a" / "b.py").read_text(encoding="utf-8") == "X = 1"
    assert paths == [str(tmp_path / "T1" / "a" / "b.py")]
    assert task_dir("T1") == str(tmp_path / "T1")
```

- [ ] **Step 2: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_sandbox.py -v` → Expected: FAIL

- [ ] **Step 3: 实现**

`agent/app/llm.py`:
```python
from langchain_openai import ChatOpenAI

from .config import settings

DEFAULT_MODEL = "deepseek-v3"


def default_llm_factory(role: str, state: dict):
    """按角色取模型名，统一走 LiteLLM 网关（OpenAI 兼容）。测试时整体替换本工厂。"""
    model = (state.get("role_models") or {}).get(role, DEFAULT_MODEL)
    return ChatOpenAI(
        model=model,
        base_url=settings.llm_base_url,
        api_key=settings.llm_api_key,
        temperature=0.2,
        max_retries=2,
    )
```

`agent/app/sandbox.py`:
```python
import subprocess

from .config import settings

ALLOWED_COMMANDS = {"python", "pytest", "node", "npm", "npx"}
MAX_OUTPUT = 64 * 1024


def run_command(cmd: list[str], cwd: str, timeout: int | None = None) -> tuple[int, str]:
    """白名单 + 限时 + 输出截断的命令执行（设计文档第 5 节）。"""
    if not cmd or cmd[0] not in ALLOWED_COMMANDS:
        raise ValueError(f"command not allowed: {cmd[:1]}")
    try:
        proc = subprocess.run(
            cmd, cwd=cwd, capture_output=True, text=True,
            timeout=timeout or settings.test_timeout_seconds,
        )
        return proc.returncode, (proc.stdout + proc.stderr)[:MAX_OUTPUT]
    except subprocess.TimeoutExpired:
        return -1, "TIMEOUT: 测试执行超时"


def run_pytest(cwd: str) -> tuple[int, str]:
    return run_command(["python", "-m", "pytest", "-v", "--tb=short"], cwd=cwd)
```

`agent/app/workspace.py`:
```python
import os

from .config import settings


def task_dir(task_id: str) -> str:
    d = os.path.join(settings.workspace_root, task_id)
    os.makedirs(d, exist_ok=True)
    return d


def write_files(task_id: str, files: list[dict]) -> list[str]:
    """把代码/文档文件写入任务工作目录，返回绝对路径列表。"""
    base = task_dir(task_id)
    written = []
    for f in files:
        path = os.path.abspath(os.path.join(base, f["path"]))
        if not path.startswith(os.path.abspath(base)):
            raise ValueError(f"path escape: {f['path']}")
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8") as fp:
            fp.write(f["content"])
        written.append(path)
    return written
```

- [ ] **Step 4: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_sandbox.py -v` → Expected: 3 passed
```bash
git add agent/app/llm.py agent/app/sandbox.py agent/app/workspace.py agent/tests/test_sandbox.py
git commit -m "feat(agent): LLM工厂/沙箱执行/工作目录模块"
```

---

### Task 4: 五角色节点与人审门

**Files:**
- Create: `agent/app/graph/__init__.py`(空), `agent/app/graph/prompts.py`, `agent/app/graph/nodes.py`, `agent/tests/conftest.py`
- Test: `agent/tests/test_nodes.py`

- [ ] **Step 1: 写公共 fixture**

`agent/tests/conftest.py`:
```python
from langchain_core.messages import AIMessage


class FakeLLM:
    """按顺序返回预置响应的假模型。"""
    def __init__(self, responses: list[str]):
        self.responses = list(responses)

    def invoke(self, messages):
        return AIMessage(content=self.responses.pop(0))


def make_fake_factory(responses_by_role: dict[str, list[str]]):
    llms = {role: FakeLLM(rs) for role, rs in responses_by_role.items()}

    def factory(role: str, state: dict):
        return llms[role]
    return factory


def fake_test_runner_ok(cwd: str):
    return 0, "2 passed"
```

- [ ] **Step 2: 写失败测试**

`agent/tests/test_nodes.py`:
```python
from app.graph.nodes import (
    make_pm_node, make_architect_node, make_coder_node,
    make_tester_node, make_reviewer_node,
)
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE_REPLY = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TEST_REPLY = "===FILE: test_calc.py===\nfrom calc import add\n\ndef test_add():\n    assert add(1, 2) == 3\n===END==="


def test_pm_node():
    node = make_pm_node(make_fake_factory({"pm": ["PRD内容"]}))
    out = node({"requirement": "做一个计算器", "role_models": {}})
    assert out["prd"] == "PRD内容"


def test_architect_node():
    node = make_architect_node(make_fake_factory({"architect": ["设计文档"]}))
    out = node({"prd": "PRD内容"})
    assert out["design_doc"] == "设计文档"


def test_coder_node_parses_files():
    node = make_coder_node(make_fake_factory({"coder": [CODE_REPLY]}))
    out = node({"design_doc": "设计", "iteration_count": 0})
    assert out["code_files"][0]["path"] == "calc.py"


def test_tester_node(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    node = make_tester_node(make_fake_factory({"tester": [TEST_REPLY]}), fake_test_runner_ok)
    out = node({"task_id": "T1", "code_files": [{"path": "calc.py", "content": "def add(a,b):\n    return a+b"}]})
    assert "2 passed" in out["test_report"]


def test_reviewer_pass_and_fail():
    node = make_reviewer_node(make_fake_factory({"reviewer": ["PASS\n代码质量良好", "FAIL\n缺少边界处理"]}))
    ok = node({"code_files": [], "test_report": "1 passed", "iteration_count": 0})
    assert ok["review_passed"] is True
    bad = node({"code_files": [], "test_report": "1 failed", "iteration_count": 0})
    assert bad["review_passed"] is False and bad["iteration_count"] == 1
    assert "缺少边界处理" in bad["review_comments"]
```

- [ ] **Step 3: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_nodes.py -v` → Expected: FAIL

- [ ] **Step 4: 实现 prompts 与 nodes**

`agent/app/graph/prompts.py`:
```python
PM_PROMPT = """你是资深产品经理。把用户的一句话需求扩写为结构化 PRD，用 Markdown 输出，
必须包含：## 需求背景、## 功能列表（编号）、## 验收标准（可测试的条目）。
如有人审驳回意见，必须针对意见修改。"""

ARCHITECT_PROMPT = """你是资深软件架构师。根据 PRD 产出技术设计文档，用 Markdown 输出，
必须包含：## 技术选型、## 模块划分、## 接口定义、## 文件清单（每个文件一行：路径 - 职责）。
如有人审驳回意见，必须针对意见修改。"""

CODER_PROMPT = """你是资深开发工程师。严格按设计文档的文件清单逐个实现完整可运行的代码。
每个文件必须用如下格式输出，不要输出其他代码块格式：
===FILE: 相对路径===
文件完整内容
===END===
如有审查意见，只修改被指出的问题并重新输出全部文件。"""

TESTER_PROMPT = """你是测试工程师。为给定代码编写 pytest 测试文件，覆盖主要功能与边界。
每个测试文件用如下格式输出：
===FILE: 相对路径===
文件完整内容
===END==="""

REVIEWER_PROMPT = """你是代码审查员。根据 PRD、代码与测试报告审查质量与需求符合度。
第一行只输出 PASS 或 FAIL，后续行给出具体审查意见。测试未通过时必须 FAIL。"""

DEFAULT_PROMPTS = {
    "pm": PM_PROMPT,
    "architect": ARCHITECT_PROMPT,
    "coder": CODER_PROMPT,
    "tester": TESTER_PROMPT,
    "reviewer": REVIEWER_PROMPT,
}
```

`agent/app/graph/nodes.py`:
```python
from langchain_core.messages import HumanMessage, SystemMessage
from langgraph.types import interrupt

from ..state import GraphState, parse_code_blocks
from ..workspace import task_dir, write_files
from .prompts import DEFAULT_PROMPTS


def _prompt(state: GraphState, role: str) -> str:
    return (state.get("role_prompts") or {}).get(role, DEFAULT_PROMPTS[role])


def _call(llm_factory, role: str, state: GraphState, user_content: str) -> str:
    llm = llm_factory(role, state)
    resp = llm.invoke([SystemMessage(content=_prompt(state, role)),
                       HumanMessage(content=user_content)])
    return resp.content


def make_pm_node(llm_factory):
    def pm(state: GraphState) -> dict:
        content = f"用户需求：{state['requirement']}"
        if state.get("human_feedback"):
            content += f"\n\n人审驳回意见：{state['human_feedback']}"
        return {"prd": _call(llm_factory, "pm", state, content), "human_feedback": ""}
    return pm


def make_architect_node(llm_factory):
    def architect(state: GraphState) -> dict:
        content = f"PRD：\n{state['prd']}"
        if state.get("human_feedback"):
            content += f"\n\n人审驳回意见：{state['human_feedback']}"
        return {"design_doc": _call(llm_factory, "architect", state, content), "human_feedback": ""}
    return architect


def make_coder_node(llm_factory):
    def coder(state: GraphState) -> dict:
        content = f"设计文档：\n{state['design_doc']}"
        if state.get("review_comments"):
            content += f"\n\n上一轮审查意见（必须修复）：{state['review_comments']}"
        reply = _call(llm_factory, "coder", state, content)
        return {"code_files": parse_code_blocks(reply)}
    return coder


def make_tester_node(llm_factory, test_runner):
    def tester(state: GraphState) -> dict:
        code_text = "\n\n".join(f"# {f['path']}\n{f['content']}" for f in state["code_files"])
        reply = _call(llm_factory, "tester", state, f"待测代码：\n{code_text}")
        test_files = parse_code_blocks(reply)
        write_files(state["task_id"], state["code_files"] + test_files)
        code, output = test_runner(task_dir(state["task_id"]))
        report = f"exit_code={code}\n{output}"
        return {"test_report": report}
    return tester


def make_reviewer_node(llm_factory):
    def reviewer(state: GraphState) -> dict:
        code_text = "\n\n".join(f"# {f['path']}\n{f['content']}" for f in state["code_files"])
        reply = _call(llm_factory, "reviewer", state,
                      f"代码：\n{code_text}\n\n测试报告：\n{state['test_report']}")
        passed = reply.strip().upper().startswith("PASS")
        out = {"review_passed": passed, "review_comments": reply}
        if not passed:
            out["iteration_count"] = state.get("iteration_count", 0) + 1
        return out
    return reviewer


def make_human_gate(gate_name: str, question: str):
    """人审门节点：auto_mode 直接放行；否则 interrupt 等待 resume。"""
    def gate(state: GraphState) -> dict:
        if state.get("auto_mode"):
            return {"human_feedback": ""}
        decision = interrupt({"gate": gate_name, "question": question,
                              "payload": {"prd": state.get("prd", ""),
                                          "design_doc": state.get("design_doc", "")}})
        if decision.get("decision") == "reject":
            return {"human_feedback": decision.get("comment", "驳回")}
        return {"human_feedback": ""}
    return gate
```

- [ ] **Step 5: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_nodes.py -v` → Expected: 5 passed
```bash
git add agent/app/graph agent/tests/conftest.py agent/tests/test_nodes.py
git commit -m "feat(agent): 五角色节点与人审门实现"
```

---

### Task 5: 状态图组装与流转测试

**Files:**
- Create: `agent/app/graph/builder.py`
- Test: `agent/tests/test_graph_flow.py`

- [ ] **Step 1: 写失败测试**

`agent/tests/test_graph_flow.py`:
```python
from langgraph.checkpoint.memory import MemorySaver
from langgraph.types import Command

from app.graph.builder import build_graph
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _graph(responses, runner=fake_test_runner_ok):
    return build_graph(make_fake_factory(responses), MemorySaver(), runner)


def _cfg(tid):
    return {"configurable": {"thread_id": tid}}


def _base(tid, auto):
    return {"task_id": tid, "requirement": "做计算器", "auto_mode": auto,
            "iteration_count": 0, "role_models": {}}


def test_auto_mode_happy_path(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                "tester": [TESTF], "reviewer": ["PASS\n好"]})
    result = g.invoke(_base("T1", True), _cfg("T1"))
    assert result["prd"] == "PRD" and result["review_passed"] is True
    assert "2 passed" in result["test_report"]


def test_review_fail_loops_back_to_coder(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE, CODE],
                "tester": [TESTF, TESTF], "reviewer": ["FAIL\n改", "PASS\n好"]})
    result = g.invoke(_base("T2", True), _cfg("T2"))
    assert result["review_passed"] is True and result["iteration_count"] == 1


def test_max_rounds_forces_exit(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE] * 4,
                "tester": [TESTF] * 4, "reviewer": ["FAIL\n改"] * 4})
    result = g.invoke(_base("T3", True), _cfg("T3"))
    assert result["review_passed"] is False and result["iteration_count"] == 3


def test_interrupt_and_reject_reruns_pm(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    g = _graph({"pm": ["PRD-v1", "PRD-v2"], "architect": ["设计"], "coder": [CODE],
                "tester": [TESTF], "reviewer": ["PASS\n好"]})
    cfg = _cfg("T4")
    r1 = g.invoke(_base("T4", False), cfg)
    assert "__interrupt__" in r1                      # 停在 PRD 人审门
    r2 = g.invoke(Command(resume={"decision": "reject", "comment": "重写"}), cfg)
    assert "__interrupt__" in r2                      # 驳回后重跑 PM，再次停在门口
    assert g.get_state(cfg).values["prd"] == "PRD-v2"
    g.invoke(Command(resume={"decision": "pass"}), cfg)      # 过 PRD 门 → 停设计门
    g.invoke(Command(resume={"decision": "pass"}), cfg)      # 过设计门 → 停最终验收
    final = g.invoke(Command(resume={"decision": "pass"}), cfg)
    assert final["review_passed"] is True
```

- [ ] **Step 2: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_graph_flow.py -v` → Expected: FAIL

- [ ] **Step 3: 实现 builder.py**

`agent/app/graph/builder.py`:
```python
from langgraph.graph import END, StateGraph

from ..config import settings
from ..state import GraphState
from .nodes import (
    make_architect_node, make_coder_node, make_human_gate,
    make_pm_node, make_reviewer_node, make_tester_node,
)


def build_graph(llm_factory, checkpointer, test_runner):
    """组装设计文档 3.2 的状态图。"""
    g = StateGraph(GraphState)
    g.add_node("pm", make_pm_node(llm_factory))
    g.add_node("prd_gate", make_human_gate("prd_gate", "请确认 PRD"))
    g.add_node("architect", make_architect_node(llm_factory))
    g.add_node("design_gate", make_human_gate("design_gate", "请确认设计文档"))
    g.add_node("coder", make_coder_node(llm_factory))
    g.add_node("tester", make_tester_node(llm_factory, test_runner))
    g.add_node("reviewer", make_reviewer_node(llm_factory))
    g.add_node("accept_gate", make_human_gate("accept_gate", "请最终验收"))

    g.set_entry_point("pm")
    g.add_edge("pm", "prd_gate")
    g.add_conditional_edges(
        "prd_gate",
        lambda s: "pm" if s.get("human_feedback") else "architect",
        {"pm": "pm", "architect": "architect"},
    )
    g.add_edge("architect", "design_gate")
    g.add_conditional_edges(
        "design_gate",
        lambda s: "architect" if s.get("human_feedback") else "coder",
        {"architect": "architect", "coder": "coder"},
    )
    g.add_edge("coder", "tester")
    g.add_edge("tester", "reviewer")

    def after_review(s: GraphState) -> str:
        if s.get("review_passed") or s.get("iteration_count", 0) >= settings.max_fix_rounds:
            return "accept_gate"
        return "coder"

    g.add_conditional_edges("reviewer", after_review,
                            {"accept_gate": "accept_gate", "coder": "coder"})
    g.add_edge("accept_gate", END)
    return g.compile(checkpointer=checkpointer)
```

- [ ] **Step 4: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_graph_flow.py -v` → Expected: 4 passed
```bash
git add agent/app/graph/builder.py agent/tests/test_graph_flow.py
git commit -m "feat(agent): LangGraph 状态图组装与流转测试"
```

---

### Task 6: TaskManager 运行器

**Files:**
- Create: `agent/app/runner.py`
- Test: `agent/tests/test_runner.py`

- [ ] **Step 1: 写失败测试**

`agent/tests/test_runner.py`:
```python
import asyncio

import pytest
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.runner import TaskManager
from app.schemas import StartTaskRequest
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="


def _manager(responses):
    graph = build_graph(make_fake_factory(responses), MemorySaver(), fake_test_runner_ok)
    return TaskManager(graph)


async def _collect(mgr, task_id, stop_events):
    events = []
    async for ev in mgr.stream(task_id):
        events.append(ev)
        if ev.event in stop_events:
            break
    return events


@pytest.mark.asyncio
async def test_auto_task_emits_done(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    mgr = _manager({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                    "tester": [TESTF], "reviewer": ["PASS\n好"]})
    mgr.start(StartTaskRequest(task_id="T1", requirement="计算器", auto_mode=True))
    events = await asyncio.wait_for(_collect(mgr, "T1", {"task_done", "task_failed"}), 10)
    kinds = [e.event for e in events]
    assert kinds[-1] == "task_done"
    assert "agent_message" in kinds and "artifact_created" in kinds
    seqs = [e.seq for e in events]
    assert seqs == sorted(seqs) and len(set(seqs)) == len(seqs)


@pytest.mark.asyncio
async def test_interrupt_then_resume(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    mgr = _manager({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                    "tester": [TESTF], "reviewer": ["PASS\n好"]})
    mgr.start(StartTaskRequest(task_id="T2", requirement="计算器", auto_mode=False))
    events = await asyncio.wait_for(_collect(mgr, "T2", {"interrupt"}), 10)
    assert events[-1].data["gate"] == "prd_gate"
    for _ in range(3):                                  # 连过三道门
        mgr.resume("T2", "pass", "")
        events = await asyncio.wait_for(
            _collect(mgr, "T2", {"interrupt", "task_done"}), 10)
    assert events[-1].event == "task_done"
```

并在 `agent/requirements.txt` 追加一行 `pytest-asyncio>=0.24`，然后 `.venv\Scripts\pip install pytest-asyncio`；在 `agent/` 下新建 `pytest.ini`：
```ini
[pytest]
asyncio_mode = auto
```

- [ ] **Step 2: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_runner.py -v` → Expected: FAIL

- [ ] **Step 3: 实现 runner.py**

`agent/app/runner.py`:
```python
import asyncio
import logging

from langgraph.types import Command

from .schemas import AgentEvent, StartTaskRequest
from .workspace import write_files

logger = logging.getLogger(__name__)

# 节点结束时要作为产物落库的字段: (state字段, 产物文件名, 类型)
_ARTIFACTS = {
    "pm": ("prd", "PRD.md", "prd"),
    "architect": ("design_doc", "DESIGN.md", "design"),
    "tester": ("test_report", "TEST_REPORT.md", "test_report"),
}


class TaskManager:
    """任务运行器：启动图执行、事件队列、resume/cancel。"""

    def __init__(self, graph):
        self.graph = graph
        self.queues: dict[str, asyncio.Queue] = {}
        self.seqs: dict[str, int] = {}
        self.jobs: dict[str, asyncio.Task] = {}

    def _emit(self, task_id: str, event: str, agent: str | None = None, data: dict | None = None):
        self.seqs[task_id] = self.seqs.get(task_id, 0) + 1
        ev = AgentEvent(event=event, task_id=task_id, agent=agent,
                        seq=self.seqs[task_id], data=data or {})
        self.queues[task_id].put_nowait(ev)

    def start(self, req: StartTaskRequest):
        if req.task_id in self.jobs and not self.jobs[req.task_id].done():
            raise ValueError(f"task {req.task_id} already running")
        self.queues.setdefault(req.task_id, asyncio.Queue())
        payload = {"task_id": req.task_id, "requirement": req.requirement,
                   "auto_mode": req.auto_mode, "iteration_count": 0,
                   "role_models": req.role_models, "role_prompts": req.role_prompts}
        self.jobs[req.task_id] = asyncio.create_task(self._run(req.task_id, payload))

    def resume(self, task_id: str, decision: str, comment: str):
        if task_id not in self.queues:
            raise KeyError(task_id)
        cmd = Command(resume={"decision": decision, "comment": comment})
        self.jobs[task_id] = asyncio.create_task(self._run(task_id, cmd))

    def cancel(self, task_id: str):
        job = self.jobs.get(task_id)
        if job and not job.done():
            job.cancel()
            self._emit(task_id, "task_failed", data={"error": "canceled"})

    async def stream(self, task_id: str):
        queue = self.queues.setdefault(task_id, asyncio.Queue())
        while True:
            ev = await queue.get()
            yield ev
            if ev.event in ("task_done", "task_failed"):
                return

    def _handle_node_end(self, task_id: str, node: str, out: dict):
        out = out or {}
        content = ""
        if node in _ARTIFACTS:
            field, fname, ftype = _ARTIFACTS[node]
            content = out.get(field, "")
            if content:
                write_files(task_id, [{"path": fname, "content": content}])
                self._emit(task_id, "artifact_created", agent=node,
                           data={"name": fname, "type": ftype, "path": fname})
        if node == "coder":
            for f in out.get("code_files", []):
                self._emit(task_id, "artifact_created", agent=node,
                           data={"name": f["path"], "type": "code", "path": f["path"]})
            content = f"生成了 {len(out.get('code_files', []))} 个代码文件"
        if node == "reviewer":
            content = out.get("review_comments", "")
        if content:
            self._emit(task_id, "agent_message", agent=node,
                       data={"delta": content, "content": content})
        self._emit(task_id, "node_end", agent=node, data={"node": node})

    async def _run(self, task_id: str, payload):
        config = {"configurable": {"thread_id": task_id}}
        try:
            interrupted = False
            async for update in self.graph.astream(payload, config, stream_mode="updates"):
                for node, out in update.items():
                    if node == "__interrupt__":
                        interrupted = True
                        self._emit(task_id, "interrupt", data=dict(out[0].value))
                    else:
                        self._handle_node_end(task_id, node, out)
            if not interrupted:
                state = self.graph.get_state(config).values
                self._emit(task_id, "task_done",
                           data={"review_passed": state.get("review_passed", False),
                                 "iteration_count": state.get("iteration_count", 0)})
        except asyncio.CancelledError:
            raise
        except Exception as e:  # noqa: BLE001 —— 设计文档第5节：兜底转 task_failed
            logger.exception("task %s failed", task_id)
            self._emit(task_id, "task_failed", data={"error": str(e)})
```

- [ ] **Step 4: 跑测试通过后提交**

Run: `.venv\Scripts\python -m pytest tests/test_runner.py -v` → Expected: 2 passed
```bash
git add agent/app/runner.py agent/tests/test_runner.py agent/pytest.ini agent/requirements.txt
git commit -m "feat(agent): TaskManager 运行器与事件流"
```

---

### Task 7: FastAPI 路由与内网鉴权

**Files:**
- Create: `agent/app/main.py`
- Test: `agent/tests/test_api.py`

- [ ] **Step 1: 写失败测试**

`agent/tests/test_api.py`:
```python
import json

import pytest
from fastapi.testclient import TestClient
from langgraph.checkpoint.memory import MemorySaver

from app.graph.builder import build_graph
from app.main import create_app
from app.runner import TaskManager
from tests.conftest import make_fake_factory, fake_test_runner_ok

CODE = "===FILE: calc.py===\ndef add(a, b):\n    return a + b\n===END==="
TESTF = "===FILE: test_calc.py===\ndef test_add():\n    assert True\n===END==="
HEADERS = {"X-Internal-Token": "change-me"}


@pytest.fixture
def client(tmp_path, monkeypatch):
    from app import workspace
    monkeypatch.setattr(workspace.settings, "workspace_root", str(tmp_path))
    graph = build_graph(
        make_fake_factory({"pm": ["PRD"], "architect": ["设计"], "coder": [CODE],
                           "tester": [TESTF], "reviewer": ["PASS\n好"]}),
        MemorySaver(), fake_test_runner_ok)
    app = create_app(TaskManager(graph))
    return TestClient(app)


def test_auth_required(client):
    r = client.post("/agent/tasks", json={"task_id": "T1", "requirement": "x"})
    assert r.status_code == 401


def test_start_and_stream_done(client):
    r = client.post("/agent/tasks", headers=HEADERS,
                    json={"task_id": "T1", "requirement": "计算器", "auto_mode": True})
    assert r.status_code == 200
    events = []
    with client.stream("GET", "/agent/tasks/T1/stream", headers=HEADERS) as resp:
        for line in resp.iter_lines():
            if line.startswith("data:"):
                events.append(json.loads(line[5:]))
    assert events[-1]["event"] == "task_done"


def test_resume_flow(client):
    client.post("/agent/tasks", headers=HEADERS,
                json={"task_id": "T2", "requirement": "计算器", "auto_mode": False})
    with client.stream("GET", "/agent/tasks/T2/stream", headers=HEADERS) as resp:
        for line in resp.iter_lines():
            if line.startswith("data:") and json.loads(line[5:])["event"] == "interrupt":
                break
    r = client.post("/agent/tasks/T2/resume", headers=HEADERS,
                    json={"decision": "pass", "comment": ""})
    assert r.status_code == 200
```

- [ ] **Step 2: 运行确认失败**

Run: `.venv\Scripts\python -m pytest tests/test_api.py -v` → Expected: FAIL

- [ ] **Step 3: 实现 main.py**

`agent/app/main.py`:
```python
from fastapi import Depends, FastAPI, Header, HTTPException

from .config import settings
from .graph.builder import build_graph
from .llm import default_llm_factory
from .runner import TaskManager
from .sandbox import run_pytest
from .schemas import ResumeRequest, StartTaskRequest


def check_token(x_internal_token: str = Header(default="")):
    if x_internal_token != settings.internal_token:
        raise HTTPException(status_code=401, detail="invalid internal token")


def get_checkpointer():
    if settings.mysql_dsn:
        from langgraph.checkpoint.mysql.pymysql import PyMySQLSaver
        saver = PyMySQLSaver.from_conn_string(settings.mysql_dsn).__enter__()
        saver.setup()
        return saver
    from langgraph.checkpoint.memory import MemorySaver
    return MemorySaver()


def create_app(manager: TaskManager | None = None) -> FastAPI:
    app = FastAPI(title="Multi-Agent Dev Service")
    if manager is None:
        graph = build_graph(default_llm_factory, get_checkpointer(), run_pytest)
        manager = TaskManager(graph)
    app.state.manager = manager

    @app.post("/agent/tasks", dependencies=[Depends(check_token)])
    async def start_task(req: StartTaskRequest):
        try:
            manager.start(req)
        except ValueError as e:
            raise HTTPException(status_code=409, detail=str(e))
        return {"code": 0, "message": "started"}

    @app.get("/agent/tasks/{task_id}/stream", dependencies=[Depends(check_token)])
    async def stream(task_id: str):
        from fastapi.responses import StreamingResponse

        async def gen():
            async for ev in manager.stream(task_id):
                yield f"data:{ev.model_dump_json()}\n\n"
        return StreamingResponse(gen(), media_type="text/event-stream")

    @app.post("/agent/tasks/{task_id}/resume", dependencies=[Depends(check_token)])
    async def resume(task_id: str, req: ResumeRequest):
        try:
            manager.resume(task_id, req.decision, req.comment)
        except KeyError:
            raise HTTPException(status_code=404, detail="task not found")
        return {"code": 0, "message": "resumed"}

    @app.post("/agent/tasks/{task_id}/cancel", dependencies=[Depends(check_token)])
    async def cancel(task_id: str):
        manager.cancel(task_id)
        return {"code": 0, "message": "canceled"}

    return app


app = create_app.__wrapped__() if hasattr(create_app, "__wrapped__") else None
```

注意：最后一行删掉，改为惰性启动入口（uvicorn 用工厂模式）：

```python
# 模块底部只保留：
def app_factory() -> FastAPI:
    return create_app()
```

启动命令：`uvicorn app.main:app_factory --factory --host 0.0.0.0 --port 8001`

- [ ] **Step 4: 跑全部测试通过后提交**

Run: `.venv\Scripts\python -m pytest -v` → Expected: 全部 passed（约 19 个）
```bash
git add agent/app/main.py agent/tests/test_api.py
git commit -m "feat(agent): FastAPI 路由/SSE/内网鉴权"
```

---

### Task 8: 冒烟脚本与说明文档

**Files:**
- Create: `agent/smoke_test.py`, `agent/README.md`

- [ ] **Step 1: 写冒烟脚本（接真实网关，手动执行）**

`agent/smoke_test.py`:
```python
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
```

- [ ] **Step 2: 写 README**

`agent/README.md` 内容：模块职责一段话 + 环境变量表（AGENT_LLM_BASE_URL / AGENT_LLM_API_KEY / AGENT_INTERNAL_TOKEN / AGENT_MYSQL_DSN / AGENT_WORKSPACE_ROOT）+ 启动命令（uvicorn 工厂模式）+ 测试命令（pytest）+ 冒烟步骤（先起网关再跑 smoke_test.py）。

- [ ] **Step 3: 全量回归后提交**

Run: `.venv\Scripts\python -m pytest -v` → Expected: 全部 passed
```bash
git add agent/smoke_test.py agent/README.md
git commit -m "docs(agent): 冒烟脚本与README"
```

---

## 自审记录

1. **Spec 覆盖**：设计文档 2.3（编排/工具/SSE/checkpoint 可插拔）→ Task 4/5/6；3.1-3.3（五角色/状态图/3轮上限/人审门/auto_mode/角色模型可配/产物落盘）→ Task 2/4/5/6；4.1 事件协议 → Task 2/6（MVP 偏差已在文档头声明）；4.2 内网 API 四个端点 + X-Internal-Token → Task 7；5 错误处理（白名单/限时/截断/兜底 task_failed/checkpoint 保留）→ Task 3/6；6 测试策略（mock LLM 单测 + 冒烟）→ 全部任务 + Task 8。Token 上限属于 SpringBoot 侧对账逻辑，留给计划2。
2. **占位符扫描**：无 TBD/TODO；README 步骤为内容清单式描述（可接受，非代码）。
3. **类型一致性**：`llm_factory(role, state)`、`test_runner(cwd) -> (int, str)`、`build_graph(llm_factory, checkpointer, test_runner)` 签名在 Task 3/4/5/6/7 一致；`GraphState` 字段与节点读写一致；`role_prompts` 在 schemas 与 nodes 中一致。
