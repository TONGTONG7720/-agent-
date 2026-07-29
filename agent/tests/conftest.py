from langchain_core.messages import AIMessage


class FakeLLM:
    """按顺序返回预置响应的假模型；可选携带 token 用量元数据。"""
    def __init__(self, responses: list[str], usages: list[tuple[int, int]] | None = None):
        self.responses = list(responses)
        self.usages = list(usages) if usages else None

    def invoke(self, messages):
        content = self.responses.pop(0)
        if self.usages:
            tin, tout = self.usages.pop(0)
            return AIMessage(content=content, usage_metadata={
                "input_tokens": tin, "output_tokens": tout, "total_tokens": tin + tout})
        return AIMessage(content=content)


def make_fake_factory(responses_by_role: dict[str, list[str]],
                      usages_by_role: dict[str, list[tuple[int, int]]] | None = None):
    llms = {role: FakeLLM(rs, (usages_by_role or {}).get(role))
            for role, rs in responses_by_role.items()}

    def factory(role: str, state: dict):
        return llms[role]
    return factory


def fake_test_runner_ok(cwd: str):
    return 0, "2 passed"
