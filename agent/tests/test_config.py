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
