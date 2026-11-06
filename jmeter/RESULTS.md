# 压测结果

## 2026-11-07 并发幂等验证

环境：

- 本机 Docker Compose：MySQL 8.4、Redis 7、MinIO、RocketMQ 5.3.4
- Spring Boot 单实例，Mock ASR / LLM
- `APP_RATE_LIMIT_ENABLED=false`，确保所有请求都进入幂等分支
- 一个真实上传并通过完整 MD5 对拍的 `taskId`

场景：

```text
50 个 JMeter 线程
同一 taskId
同一 userGoal
同时调用 POST /api/analysis/submit
```

结果：

| 指标 | 结果 |
|---|---:|
| HTTP 202 Accepted | 1 |
| HTTP 409 Duplicate | 49 |
| HTTP 5xx / 401 / 429 | 0 |
| JMeter error rate | 0.00% |
| 平均响应时间 | 15.46 ms |
| 最小响应时间 | 11 ms |
| 最大响应时间 | 112 ms |

Prometheus 增量：

```text
dovideo_analysis_submit_total{result="accepted"}  1
dovideo_analysis_submit_total{result="duplicate"} 49
dovideo_analysis_lock_total{result="acquired"}    1
dovideo_analysis_result_total{result="completed"} 1
dovideo_analysis_processing_seconds_count{result="completed"} 1
```

数据库验证：

```text
同一视频逐字稿：1 条
不同 goalDigest 的结构化结果：每条目标 1 条
```

## 跨用户内容复用验证

第二个用户使用相同视频 MD5 请求 `/api/upload/check`：

```text
deduplicated = false
```

用户必须真实上传分片。合并后服务端重新计算完整 MD5，与客户端指纹一致后才复用第一个用户的 MinIO 成品对象：

```text
新 taskId = 8
finalFileUrl = videos/.../idempotency-sample.mp4
复制出来的临时成品已删除
```

这说明秒传不再仅凭“知道某个 MD5”就直接授权复用，跨用户内容去重发生在服务端完整校验之后。
