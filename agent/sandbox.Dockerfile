# 测试执行沙箱镜像：容器断网无法 pip，故预装 pytest
# 一次性构建： docker build -t magent-sandbox:latest -f sandbox.Dockerfile .
FROM python:3.11-slim
RUN pip install --no-cache-dir pytest==8.* && rm -rf /root/.cache
WORKDIR /work
