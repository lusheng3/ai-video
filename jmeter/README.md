# JMeter 压测说明

## 1. 前置条件

1. 后端已启动：`http://127.0.0.1:8080`
2. Docker 中间件全部启动（MySQL / Redis / MinIO / RocketMQ）
3. 已存在一个可分析的视频任务（先走一遍上传 + 合并，拿到 `taskId`）
4. 已存在测试账号（或用 `-Jusername` / `-Jpassword` 指定）

> `taskId` 是必填变量。可以在数据库执行 `SELECT id FROM video_task_record ORDER BY id DESC LIMIT 1;` 获取。

## 2. 运行方式（命令行，推荐）

```bash
jmeter -n -t dovideo-benchmark.jmx ^
       -l result.jtl ^
       -e -o report ^
       -Jhost=127.0.0.1 -Jport=8080 ^
       -JtaskId=1 ^
       -Jusername=jmeter_user -Jpassword=123456
```

跑完打开 `report/index.html` 查看 HTML 报告。

## 3. 线程组说明

| 线程组 | 配置 | 目的 |
|---|---|---|
| setUp - 登录 | 1 线程 × 1 次 | 登录并提取 `token`，供后续线程组复用 |
| 1. 异步提交 RT | 1 线程 × 5 次 | 测量 `/api/analysis/submit` 热路径 RT；首个请求期望 202，其余同任务提交期望 409 |
| 2. 3s 状态轮询 | 10 线程 × 10 次，间隔 3s | 模拟前端轮询压力，测量 `/api/analysis/status` RT |
| 3. 令牌桶限流 | 1 线程 × 10 次，无间隔 | 前 5 次通过限流（202/409），后 5 次期望 429 |
| 4. 同步对照组 | 默认禁用 | 打开后与线程组 1 对比「同步阻塞 vs 异步 50ms」 |

## 4. 结果解读

- **线程组 1**：`Aggregate Report` 的 `Average` / `99% Line` 即异步提交 RT；同任务当前采用任务级活动 Key，因此重复目标会返回 409；
- **线程组 2**：轮询接口的 P95 应远低于 50ms（只查 Redis / MySQL）；
- **线程组 3**：`Error %` 会显示约 50%，这是**预期行为**（429 被断言视为通过），日志中可看到 `rate_limit_exceeded`；
- **线程组 4**：`elapsedMs` 约 3000ms（Mock 延迟 1.5s + 1.5s），与线程组 1 的 ~50ms 形成鲜明对比。

## 5. 注意事项

- 线程组按顺序串行执行（`serialize_threadgroups=true`），避免互相抢令牌。
- 如果想测更高并发，先调大 `app.rate-limit.free-user-capacity` 或关闭限流：
  ```yaml
  app:
    rate-limit:
      enabled: false
  ```
- 当前 `app.mock-enabled=true`，ASR / LLM 是 1.5s 模拟延迟；真实模式下请评估费用后再压测。
- 不要在 GUI 模式下跑正式压测，GUI 仅用于打开脚本检查结构。

## 6. 并发幂等压测

脚本：`dovideo-idempotency.jmx`

准备条件：

1. 后端已启动。
2. 有一个上传完成、尚未分析的 `taskId`。
3. 为观察全部请求进入幂等分支，压测期间关闭接口限流：

```powershell
$env:APP_RATE_LIMIT_ENABLED="false"
```

运行：

```powershell
jmeter -n -t dovideo-idempotency.jmx `
  -l idempotency.jtl -e -o idempotency-report `
  -Jhost=127.0.0.1 -Jport=8080 `
  -JtaskId=1 `
  -Jusername=jmeter_user -Jpassword=123456 `
  -Jgoal="幂等压测目标" -Jthreads=50
```

预期结果：

```text
HTTP 202 = 1
HTTP 409 = 49
HTTP 429 = 0
```

结合 Prometheus 指标确认：

```text
dovideo_analysis_submit_total{result="accepted"}  增加 1
dovideo_analysis_submit_total{result="duplicate"} 增加 49
```

如果准备的不是全新任务，而是已有结果的任务，第一次请求可能返回 200（REUSED），其余请求仍应返回语义上的复用/重复，不应产生新的 MQ 消息。
