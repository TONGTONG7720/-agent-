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
