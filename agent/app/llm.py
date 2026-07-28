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
