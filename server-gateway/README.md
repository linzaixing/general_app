# fly_agent server gateway

给手机 App 提供统一公网入口（OpenAI 兼容），并把请求转发到上游模型服务。

支持接口：

- `POST /v1/audio/transcriptions`
- `POST /v1/chat/completions`
- `GET /healthz`

## 快速部署（你的服务器 211.101.239.208）

```bash
cd server-gateway
cp .env.example .env
# 编辑 .env 填写 GATEWAY_API_KEY / UPSTREAM_API_KEY
docker compose up -d --build
```

验证：

```bash
curl http://127.0.0.1:8000/healthz
```

公网验证：

```bash
curl http://211.101.239.208:8000/healthz
```

## App 里怎么填

- Base URL: `http://211.101.239.208:8000/v1`
- API Key: 填 `.env` 里的 `GATEWAY_API_KEY`
- ASR 路径: `/audio/transcriptions`
- ASR 模型: `whisper-1`（或任意，网关会按上游处理）
- VLA 路径: `/chat/completions`
- VLA 模型: 你上游支持的模型名

## 注意

- 生产建议改成 HTTPS（Nginx + 证书）
- 确保 8000 端口在云安全组和系统防火墙都已放行
- `PROVIDER=dashscope` 时，ASR 会自动转换为 DashScope 的 `input_audio` 协议
