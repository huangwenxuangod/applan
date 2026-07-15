#!/usr/bin/env python3
"""
applan 后端（FastAPI + httpx）
================================
OpenAI 兼容的 /v1/chat/completions 透明代理，专门服务 applan App 的"守门人"功能。

做的事：
1. 接收 App 的 OpenAI 兼容请求（tools: lock_screen / exit_app + SSE 流）
2. 把 SOUL.md 作为 system 人格注入（五层漏斗守门人）
3. 把 applan.md（历史漏洞记录）注入 system，让模型能说"你第N次说这个了"
4. 原样透传 tools / tool_choice 给 DeepSeek（不重写、不丢弃）
5. 把 DeepSeek 的 SSE 字节流**逐字节转发**给 App，tool_calls 原样出现在流里
6. 每轮结束后，后台把本轮发现的新漏洞/重复借口写回 applan.md（SOUL 第 5 条）

设计要点：
- SSE 用 httpx 逐字节转发，避免二次序列化丢字段，100% 兼容 App 的解析器（ApplanClient.kt）。
- 记忆读写基于 server 端文件，因为 App 的 messageHistory 在重启/重置时会清空，
  跨会话记住借口只能靠后端持久化。
- 记忆写入是"后台异步、尽力而为"，绝不影响主 SSE 流；失败只记日志。

环境变量见 .env.example。
依赖：pip install fastapi uvicorn httpx
运行：
  python3 proxy_backend.py
  # 或：uvicorn proxy_backend:app --host 0.0.0.0 --port 8787
"""
import os
import re
import json
import asyncio
import logging
import pathlib
from datetime import datetime, timezone
from fastapi import FastAPI, Request, Response
from fastapi.responses import StreamingResponse
import httpx
import uvicorn

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
log = logging.getLogger("applan-proxy")

app = FastAPI()

BASE = os.getenv("DEEPSEEK_BASE_URL", "https://api.deepseek.com/v1").rstrip("/")
KEY = os.getenv("DEEPSEEK_API_KEY", "")
MODEL = os.getenv("MODEL", "deepseek-chat")
PORT = int(os.getenv("PORT", "8787"))
PROXY_KEY = os.getenv("PROXY_API_KEY", "")
MEMORY_FILE = os.getenv("MEMORY_FILE", str(pathlib.Path(__file__).parent / "applan.md"))
ENABLE_MEMORY_WRITE = os.getenv("ENABLE_MEMORY_WRITE", "true").lower() in ("1", "true", "yes", "on")

SOUL_PATH = pathlib.Path(__file__).parent / "SOUL.md"
SYSTEM_PROMPT = (
    SOUL_PATH.read_text(encoding="utf-8")
    if SOUL_PATH.exists()
    else "You are a strict phone gatekeeper."
)

# 允许透传给上游模型的额外参数白名单（避免把未知字段丢给上游出错）
PASS_THROUGH = (
    "temperature", "top_p", "max_tokens", "frequency_penalty",
    "presence_penalty", "stop", "seed", "logit_bias", "response_format",
)

UPSTREAM_TIMEOUT = httpx.Timeout(300.0, connect=15.0)
_memory_lock = asyncio.Lock()


# ---------------------------------------------------------------------------
# 记忆（applan.md）：读注入 + 后台写回（SOUL 第 5 条）
# ---------------------------------------------------------------------------
def load_memory_block() -> str:
    """读取 applan.md，包装成 system 里的一段「历史漏洞记录」上下文。"""
    p = pathlib.Path(MEMORY_FILE)
    if not p.exists():
        return ""
    try:
        text = p.read_text(encoding="utf-8").strip()
    except Exception as e:
        log.warning("读取 applan.md 失败: %s", e)
        return ""
    if not text:
        return ""
    return (
        "\n\n---\n## 历史漏洞记录（来自 applan.md，机密）\n"
        "以下是这个用户过去反复使用的借口 / 绕过方式。当用户再次说出相似内容时，"
        "直接引用其中的次数戳穿他（例如「你第 3 次说这个了」），然后锁屏。\n"
        "不要主动背诵整份记录，只在命中时引用。\n"
        f"{text}\n"
    )


def build_system() -> str:
    """SOUL.md 人格 + 历史漏洞记录。"""
    return SYSTEM_PROMPT + load_memory_block()


def _bump_count(line: str) -> str:
    """把一行漏洞记录里的「第N次」+1；没有就追加「（再次出现）」。"""
    m = re.search(r"第(\d+)次", line)
    if m:
        n = int(m.group(1)) + 1
        return line[: m.start()] + f"第{n}次" + line[m.end():]
    return line + "（再次出现）"


async def record_memory(messages: list):
    """后台任务：把本轮发现的新漏洞 / 重复借口写回 applan.md。
    失败只记日志，绝不影响主流程（包括主 SSE 流）。"""
    if not ENABLE_MEMORY_WRITE:
        return
    p = pathlib.Path(MEMORY_FILE)
    existing = ""
    if p.exists():
        try:
            existing = p.read_text(encoding="utf-8")
        except Exception:
            existing = ""

    last_user = ""
    for m in reversed(messages):
        if m.get("role") == "user":
            last_user = m.get("content", "")
            break
    if not last_user:
        return

    sys_prompt = (
        "你是 applan 守门人的记忆管理员。根据对话历史和现有漏洞记录，判断是否需要更新记忆。\n"
        "规则：\n"
        "1) 如果用户的话是一个新的、值得长期跟踪的「借口 / 绕过方式」，返回 action=add，"
        "line 写一句不带日期的漏洞记录（例如：借口「查资料」——无定义、无终止条件，已多次出现）。\n"
        "2) 如果用户的话与现有记录中的某条高度相似（同一个借口），返回 action=increment，"
        "match 原样复制那条记录的完整文本。\n"
        "3) 否则返回 action=none。\n"
        "只输出一个 JSON，不要任何解释："
        '{"action":"none"|"add"|"increment","line":"...","match":"..."}'
    )
    user_prompt = (
        f"现有漏洞记录：\n{existing if existing else '(空)'}\n\n"
        f"最近用户消息：{last_user}\n\n"
        "请判断并返回 JSON。"
    )

    try:
        async with httpx.AsyncClient(timeout=httpx.Timeout(60.0, connect=15.0)) as client:
            r = await client.post(
                f"{BASE}/chat/completions",
                headers={"Authorization": f"Bearer {KEY}", "Content-Type": "application/json"},
                json={
                    "model": MODEL,
                    "messages": [
                        {"role": "system", "content": sys_prompt},
                        {"role": "user", "content": user_prompt},
                    ],
                    "stream": False,
                    "temperature": 0,
                },
            )
        if r.status_code != 200:
            log.warning("记忆提取上游返回 %s，跳过", r.status_code)
            return
        data = r.json()
        content = data["choices"][0]["message"]["content"].strip()
        # 容错：模型可能用 ```json 包裹
        if content.startswith("```"):
            content = content.strip("`")
            if content.lower().startswith("json"):
                content = content[4:]
        decision = json.loads(content)
        action = decision.get("action")

        async with _memory_lock:
            if action == "add" and decision.get("line"):
                today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
                new_line = f"- {today} | {decision['line'].strip()}\n"
                with p.open("a", encoding="utf-8") as f:
                    f.write(new_line)
                log.info("记忆新增: %s", decision["line"].strip()[:60])
            elif action == "increment" and decision.get("match"):
                if not p.exists():
                    return
                lines = p.read_text(encoding="utf-8").splitlines()
                match = decision["match"].strip()
                for i, line in enumerate(lines):
                    if match in line or line.strip() == match:
                        lines[i] = _bump_count(line)
                        break
                else:
                    today = datetime.now(timezone.utc).strftime("%Y-%m-%d")
                    lines.append(f"- {today} | {match}")
                p.write_text("\n".join(lines) + "\n", encoding="utf-8")
                log.info("记忆计数+1: %s", match[:60])
    except Exception as e:
        log.warning("记忆写入失败（已忽略）: %s", e)


# ---------------------------------------------------------------------------
# 接口
# ---------------------------------------------------------------------------
@app.get("/v1/models")
async def models():
    return {"object": "list", "data": [{"id": MODEL, "object": "model"}]}


@app.get("/healthz")
async def healthz():
    return {"status": "ok", "model": MODEL, "has_key": bool(KEY),
            "memory": ENABLE_MEMORY_WRITE}


@app.post("/v1/chat/completions")
async def chat(req: Request):
    # 可选鉴权（内网可留空 PROXY_KEY 免鉴权）
    if PROXY_KEY:
        auth = req.headers.get("authorization", "")
        if auth != f"Bearer {PROXY_KEY}":
            return Response(
                status_code=401,
                media_type="application/json",
                content=json.dumps({"error": {"message": "unauthorized", "type": "invalid_request_error"}}),
            )

    body = await req.json()
    messages = body.get("messages", [])

    # 注入人格 system（仅当客户端没自己带 system 时；App 从不带 system）
    if not messages or messages[0].get("role") != "system":
        messages = [{"role": "system", "content": build_system()}] + messages

    # 构造发给 DeepSeek 的负载：忽略 App 假模型名，强制用真实 MODEL
    payload = {
        "model": MODEL,
        "messages": messages,
        "stream": bool(body.get("stream", True)),
    }
    if body.get("tools") is not None:
        payload["tools"] = body["tools"]
        payload["tool_choice"] = body.get("tool_choice", "auto")
    for k in PASS_THROUGH:
        if k in body:
            payload[k] = body[k]

    upstream_headers = {
        "Authorization": f"Bearer {KEY}",
        "Content-Type": "application/json",
    }
    upstream_url = f"{BASE}/chat/completions"

    log.info("→ upstream model=%s tools=%s stream=%s",
             MODEL, "yes" if "tools" in payload else "no", payload["stream"])

    if payload["stream"]:
        async def stream_proxy():
            async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
                try:
                    async with client.stream("POST", upstream_url, json=payload, headers=upstream_headers) as r:
                        if r.status_code != 200:
                            err = await r.aread()
                            log.error("upstream error %s: %s", r.status_code, err[:300])
                            msg = err.decode("utf-8", "replace")[:500]
                            # 用 content delta 把错误推到 App 聊天界面，避免静默失败
                            frame = json.dumps(
                                {"choices": [{"delta": {"content": f"[后端错误 {r.status_code}] {msg}"}}]}
                            )
                            yield ("data: " + frame + "\n\n").encode("utf-8")
                            yield b"data: [DONE]\n\n"
                            return
                        async for raw in r.aiter_raw():
                            yield raw
                except Exception as e:  # 连接级异常也兜底，保证 App 能干净关闭
                    log.exception("stream error")
                    frame = json.dumps(
                        {"choices": [{"delta": {"content": f"[后端连接异常] {e}"}}]}
                    )
                    yield ("data: " + frame + "\n\n").encode("utf-8")
                    yield b"data: [DONE]\n\n"
                    return

            # 主流转发完毕，后台写记忆（不阻塞、不影响 App）
            if ENABLE_MEMORY_WRITE:
                asyncio.create_task(record_memory(body.get("messages", [])))

        return StreamingResponse(
            stream_proxy(),
            media_type="text/event-stream",
            headers={
                "Cache-Control": "no-cache",
                "Connection": "keep-alive",
                "X-Accel-Buffering": "no",  # 防止 nginx/caddy 缓冲 SSE 导致卡顿
            },
        )
    else:
        async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
            r = await client.post(upstream_url, json=payload, headers=upstream_headers)
            return Response(
                content=r.content,
                status_code=r.status_code,
                media_type=r.headers.get("content-type", "application/json"),
            )


if __name__ == "__main__":
    if not KEY:
        log.warning("⚠️  未设置 DEEPSEEK_API_KEY，请在环境变量中配置后再运行（所有对话都会失败）")
    log.info("applan proxy 启动: model=%s memory=%s port=%s",
             MODEL, "on" if ENABLE_MEMORY_WRITE else "off", PORT)
    uvicorn.run(app, host="0.0.0.0", port=PORT)
