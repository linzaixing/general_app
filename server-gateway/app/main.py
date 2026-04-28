import base64
import os
from typing import Any

import httpx
from fastapi import FastAPI, File, Form, Header, HTTPException, Request, UploadFile, WebSocket, WebSocketDisconnect
from fastapi.responses import JSONResponse


def _env(name: str, default: str = "") -> str:
    return os.getenv(name, default).strip()


HOST = _env("HOST", "0.0.0.0")
PORT = int(_env("PORT", "8000") or "8000")
GATEWAY_API_KEY = _env("GATEWAY_API_KEY")
PROVIDER = _env("PROVIDER", "dashscope").lower()  # dashscope / openai
UPSTREAM_BASE_URL = _env("UPSTREAM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1").rstrip("/")
UPSTREAM_API_KEY = _env("UPSTREAM_API_KEY")
DASHSCOPE_ASR_MODEL = _env("DASHSCOPE_ASR_MODEL", "qwen3-asr-flash")
UPSTREAM_TIMEOUT = float(_env("UPSTREAM_TIMEOUT", "120"))

app = FastAPI(title="fly-agent-gateway", version="1.0.0")


def _auth_or_401(authorization: str | None) -> None:
    if not GATEWAY_API_KEY:
        return
    if not authorization or not authorization.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Missing Bearer token")
    token = authorization.replace("Bearer ", "", 1).strip()
    if token != GATEWAY_API_KEY:
        raise HTTPException(status_code=401, detail="Invalid API key")


def _ws_auth_ok(authorization: str | None) -> bool:
    if not GATEWAY_API_KEY:
        return True
    if not authorization or not authorization.startswith("Bearer "):
        return False
    token = authorization.replace("Bearer ", "", 1).strip()
    return token == GATEWAY_API_KEY


def _upstream_headers() -> dict[str, str]:
    headers = {"Accept": "application/json"}
    if UPSTREAM_API_KEY:
        headers["Authorization"] = f"Bearer {UPSTREAM_API_KEY}"
    return headers


def _extract_chat_text(payload: dict[str, Any]) -> str:
    choices = payload.get("choices") or []
    if not choices:
        return ""
    message = (choices[0] or {}).get("message") or {}
    content = message.get("content")
    if isinstance(content, str):
        return content.strip()
    if isinstance(content, list):
        parts: list[str] = []
        for item in content:
            if isinstance(item, dict):
                txt = item.get("text")
                if isinstance(txt, str):
                    parts.append(txt)
        return "".join(parts).strip()
    return ""


@app.get("/healthz")
async def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.websocket("/v1/ws/commands")
@app.websocket("/ws/commands")
async def ws_commands(websocket: WebSocket) -> None:
    auth = websocket.headers.get("authorization")
    if not _ws_auth_ok(auth):
        # 1008 = policy violation
        await websocket.close(code=1008, reason="Unauthorized")
        return

    await websocket.accept()
    try:
        while True:
            text = await websocket.receive_text()
            await websocket.send_text(f"已经收到信息：{text}")
    except WebSocketDisconnect:
        return


@app.post("/v1/audio/transcriptions")
async def transcriptions(
    authorization: str | None = Header(default=None),
    model: str = Form(default=""),
    file: UploadFile | None = File(default=None),
    audio: UploadFile | None = File(default=None),
) -> JSONResponse:
    _auth_or_401(authorization)
    upload = file or audio
    if upload is None:
        raise HTTPException(status_code=400, detail="file/audio is required")
    raw = await upload.read()
    if not raw:
        raise HTTPException(status_code=400, detail="empty audio file")

    async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
        if PROVIDER == "dashscope":
            # DashScope OpenAI-compatible ASR: chat/completions + input_audio
            b64 = base64.b64encode(raw).decode("ascii")
            data_url = f"data:audio/wav;base64,{b64}"
            body = {
                "model": model or DASHSCOPE_ASR_MODEL,
                "messages": [
                    {
                        "role": "user",
                        "content": [
                            {
                                "type": "input_audio",
                                "input_audio": {"data": data_url},
                            }
                        ],
                    }
                ],
                "asr_options": {"language": "zh", "enable_itn": True},
                "stream": False,
            }
            resp = await client.post(
                f"{UPSTREAM_BASE_URL}/chat/completions",
                headers={**_upstream_headers(), "Content-Type": "application/json"},
                json=body,
            )
            if resp.status_code >= 400:
                return JSONResponse(
                    status_code=resp.status_code,
                    content={"error": f"Upstream error {resp.status_code}", "detail": resp.text[:500]},
                )
            payload = resp.json()
            return JSONResponse(content={"text": _extract_chat_text(payload)})

        # Generic OpenAI-compatible upstream that supports multipart /audio/transcriptions
        files = {
            "file": (upload.filename or "audio.wav", raw, upload.content_type or "audio/wav"),
            "audio": (upload.filename or "audio.wav", raw, upload.content_type or "audio/wav"),
        }
        data = {"model": model or "whisper-1", "language": "zh", "response_format": "json"}
        resp = await client.post(
            f"{UPSTREAM_BASE_URL}/audio/transcriptions",
            headers=_upstream_headers(),
            data=data,
            files=files,
        )
        if resp.status_code >= 400:
            return JSONResponse(
                status_code=resp.status_code,
                content={"error": f"Upstream error {resp.status_code}", "detail": resp.text[:500]},
            )
        return JSONResponse(content=resp.json())


@app.post("/v1/chat/completions")
async def chat_completions(request: Request, authorization: str | None = Header(default=None)) -> JSONResponse:
    _auth_or_401(authorization)
    payload = await request.json()
    async with httpx.AsyncClient(timeout=UPSTREAM_TIMEOUT) as client:
        resp = await client.post(
            f"{UPSTREAM_BASE_URL}/chat/completions",
            headers={**_upstream_headers(), "Content-Type": "application/json"},
            json=payload,
        )
    return JSONResponse(status_code=resp.status_code, content=resp.json())
