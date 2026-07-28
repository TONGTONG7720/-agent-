# llm-gateway/ — LiteLLM 模型网关

把通义/DeepSeek/智谱/第三方运营商 Key 统一成一个 OpenAI 兼容端点（http://localhost:4000/v1）。
Agent 服务只面向本网关编程，换模型/换 Key 只改这里的配置，业务代码零改动。

## 准备

```powershell
Copy-Item .env.example .env    # 然后编辑 .env 填入你的真实 Key
```

## 启动方式一：pip（推荐本机开发）

```powershell
pip install "litellm[proxy]"
litellm --config litellm-config.yaml --port 4000
```

> litellm 会自动读取当前目录 `.env`。

## 启动方式二：Docker

```powershell
docker run -d --name llm-gateway -p 4000:4000 `
  --env-file .env `
  -v ${PWD}/litellm-config.yaml:/app/config.yaml `
  ghcr.io/berriai/litellm:main-latest `
  --config /app/config.yaml --port 4000
```

## 验证

```powershell
curl http://localhost:4000/v1/models -H "Authorization: Bearer sk-magent-local"
# 应返回 qwen-plus / deepseek-v3 / glm-4 / relay-gpt 模型列表
```

## 添加新模型/新运营商

在 `litellm-config.yaml` 的 `model_list` 追加一条（Key 放 `.env`），重启网关；
再到平台"系统管理→模型管理"登记同名 `model_name` 即可被各角色选用。
同一 `model_name` 注册多条 = 自动负载均衡 + Key 故障切换。
