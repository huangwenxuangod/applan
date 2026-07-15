applan 后端镜像
# 用 FastAPI 暴露 OpenAI 兼容接口 /v1/chat/completions (SSE + function calling)
# 自动注入 SOUL.md 人格，并忽略 App 写死的模型名（代理强制使用环境变量 MODEL）
FROM python:3.11-slim

WORKDIR /app

# 只装运行时依赖（httpx 用于逐字节透传 SSE，避免二次序列化丢字段）
RUN pip install --no-cache-dir fastapi uvicorn httpx

# 兜底复制（运行时用 bind mount 覆盖，便于改 SOUL / 改代码不重建镜像）
COPY proxy_backend.py /app/proxy_backend.py
COPY SOUL.md /app/SOUL.md

EXPOSE 8787

CMD ["python3", "proxy_backend.py"]
