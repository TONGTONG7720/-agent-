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
    role_prompts: dict[str, str]  # role -> 自定义 system prompt
    prd: str
    design_doc: str
    code_files: list[CodeFile]
    test_report: str
    review_comments: str
    review_passed: bool
    iteration_count: int          # Reviewer→Coder 返工轮数
    human_feedback: str           # 人审驳回意见，回传给上游节点
    reject_target: str            # 定向回退目标角色（pm/architect/coder）
    iterate_feedback: str         # 完成后的多轮迭代修改意见（直接进 coder）
    documents: list               # 通用产出累加器 [{key,name,content}]（自定义流水线用）
    knowledge: str                # RAG 检索到的知识库参考片段（注入 SystemMessage）
    input_tokens: int             # 全图累计 token 用量（读 usage_metadata）
    output_tokens: int


_BLOCK_RE = re.compile(r"===FILE: (.+?)===\n(.*?)\n===END===", re.DOTALL)


def parse_code_blocks(text: str) -> list[CodeFile]:
    """解析模型输出中的 ===FILE: path=== ... ===END=== 代码块。"""
    return [
        {"path": m.group(1).strip(), "content": m.group(2)}
        for m in _BLOCK_RE.finditer(text)
    ]
