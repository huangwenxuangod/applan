#!/usr/bin/env python3
"""
applan 调试用最小 Mock 后端（零依赖，仅 Python 标准库）
==================================================
- 监听 0.0.0.0:8787
- POST /v1/chat/completions : 返回 SSE 流，结尾发一个工具调用
      ACTION = "exit_app"    -> 模拟"意图通过、放行"
      ACTION = "lock_screen" -> 模拟"锁屏"
- GET  /v1/models           : 返回模型列表，方便探针

用途：不依赖任何 LLM API Key，先把 App <-> 服务器整条链路
      （SSE 流式 + function calling 解析）跑通，定位问题到底在
      网络 / 协议 / 模型 / 还是 App 端。

运行：python3 mock_backend.py
"""
import json
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST, PORT = "0.0.0.0", 8787
# === 调试开关：exit_app=放行, lock_screen=锁屏 ===
ACTION = "exit_app"


def build_sse(chunks):
    out = []
    for c in chunks:
        out.append("data: " + json.dumps(c, ensure_ascii=False) + "\n\n")
    out.append("data: [DONE]\n\n")
    return "".join(out).encode("utf-8")


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, body: bytes, ctype="application/json"):
        self.send_response(code)
        self.send_header("Content-Type", ctype)
        self.send_header("Cache-Control", "no-cache")
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        if self.path.startswith("/v1/models"):
            self._send(200, json.dumps(
                {"object": "list", "data": [{"id": "mock-debug", "object": "model"}]},
                ensure_ascii=False).encode("utf-8"))
        else:
            self._send(404, b'{"error":"not found"}')

    def do_POST(self):
        if not self.path.startswith("/v1/chat/completions"):
            self._send(404, b'{"error":"not found"}')
            return
        try:
            length = int(self.headers.get("Content-Length", 0))
            req = json.loads(self.rfile.read(length) or b"{}")
        except Exception:
            req = {}

        # 先吐一句内容，再发工具调用，最后 [DONE]
        # 这和真实 LLM 在"意图通过"时发出的 SSE 结构一致
        chunks = [
            {"choices": [{"index": 0, "delta": {"role": "assistant", "content": "行，去干。"}}]},
            {"choices": [{"index": 0, "delta": {"tool_calls": [
                {"index": 0, "id": "call_debug", "type": "function",
                 "function": {"name": ACTION, "arguments": ""}}]}}]},
            {"choices": [{"index": 0, "delta": {"tool_calls": [
                {"index": 0, "function": {"arguments": "{}"}}]}}]},
        ]
        body = build_sse(chunks)
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-cache")
        self.send_header("Connection", "keep-alive")
        self.end_headers()
        self.wfile.write(body)

    def log_message(self, fmt, *args):
        sys.stderr.write("[mock] " + (fmt % args) + "\n")


if __name__ == "__main__":
    print(f"Mock backend 启动: http://{HOST}:{PORT}  (ACTION={ACTION})")
    print("把 App 设置里的服务器地址填成 http://<本机IP>:8787 即可调试")
    ThreadingHTTPServer((HOST, PORT), Handler).serve_forever()
