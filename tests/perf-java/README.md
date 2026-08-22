# 压测 / 基线工具（perf-java）

可重复的负载测试：对真实后端 + Ollama 打三个场景，输出分位数/RPS/错误率，
并支持与历史基线对比，作为性能回归的依据。

## 场景

| 场景 | 请求 | 说明 |
|------|------|------|
| `health` | `GET /api/health` | 纯 HTTP 吞吐基线，不触 LLM |
| `chat` | `POST /api/chat` | 非流式真实推理（异步 REST 路径，含推理闸门排队） |
| `stream` | `POST /api/chat/stream` | SSE 流式，额外记录首 token 延迟 |

## 运行

先启动 Ollama 与后端，然后：

```bash
cd backend/web
./mvnw.cmd -f ../../tests/perf-java/pom.xml test \
  -Dgroups=perf -DexcludedGroups= \
  -Dperf.concurrency=4 \
  -Dperf.healthIterations=300 \
  -Dperf.chatIterations=4 \
  -Dperf.streamIterations=4 \
  -Dperf.saveBaseline=perf-baseline.json
```

报告写入 `tests/perf-java/target/perf-report/perf-<ts>.json`；指定
`-Dperf.saveBaseline=...` 会把本轮结果存为基线，下次运行加
`-Dperf.baseline=...` 即可对比（P95 劣化 >20% 会打警告）。

## 参数

| 属性 | 默认 | 说明 |
|------|------|------|
| `perf.baseUrl` | `http://localhost:8080` | 后端地址 |
| `perf.username` / `perf.password` | `admin` / `admin123` | 登录凭据 |
| `perf.concurrency` | `4` | 最大并发请求数 |
| `perf.healthIterations` | `300` | health 请求数 |
| `perf.chatIterations` | `4` | 非流式聊天请求数 |
| `perf.streamIterations` | `4` | 流式聊天请求数 |
| `perf.requestTimeoutSeconds` | `300` | 单请求超时（须大于 chat_timeout） |
| `perf.saveBaseline` | 空 | 保存基线文件路径 |
| `perf.baseline` | 空 | 对比的历史基线文件路径 |

## 注意事项

- `chat` / `stream` 走真实 LLM 推理，耗时取决于本机 Ollama；默认并发 4 配合
  `inference_concurrency=1` 会形成排队，恰好能观察闸门行为。
- 结果对机器负载敏感，基线对比建议在相近负载条件下进行。
- 该套件 `@Tag("perf")`，pom 默认 `excludedGroups=perf`，普通 `mvn test` 会跳过，
  不影响日常 CI；CI 里已挂 `workflow_dispatch` 手动 job（`run_perf` 输入），
  跑完把报告作为 artifact 上传。
