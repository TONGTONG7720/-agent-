from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="AGENT_", env_file=".env")

    llm_base_url: str = "http://localhost:4000"   # LiteLLM 网关
    llm_api_key: str = "sk-litellm"
    default_model: str = "gpt-5.6-terra"          # 角色未配置模型时的兼底（需在网关已注册）
    internal_token: str = "change-me"             # 与 SpringBoot 共享的内网密钥
    workspace_root: str = "./workspace"
    mysql_dsn: str = ""                           # 空 = 内存 checkpointer
    max_fix_rounds: int = 3
    test_timeout_seconds: int = 120
    # 测试执行沙箱：subprocess(默认，本机) | docker(容器隔离，需 Docker 运行中)
    sandbox_mode: str = "subprocess"
    sandbox_image: str = "magent-sandbox:latest"
    sandbox_memory: str = "512m"
    sandbox_cpus: str = "1.0"


settings = Settings()
