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
