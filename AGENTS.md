# AGENTS.md —— AI长视频解析平台开发规范

本项目严格对齐简历「项目二：AI长视频解析平台」，聚焦高并发与高可靠 Java 后端架构。

## MQ 消费者开发规范（Skill）

### 背景
本项目所有视频处理任务（音频提取、ASR 语音转写、DeepSeek 结构化总结）统一通过 RocketMQ 异步消费。
每个消费者必须严格遵循以下**标准六步流程**，不允许跳步或改变顺序：

1. **解析消息体**：提取 `videoMd5` 和 `taskId`。
2. **获取分布式锁**：用 Redisson 基于 `videoMd5` 获取分布式锁（Key 格式：`lock:video:{videoMd5}`），调用 `tryLock()` 且不指定固定 `leaseTime`，启用 WatchDog 自动续期。
3. **抢锁失败处理**：直接 `return`（ACK 确认），说明有其他线程正在处理同一视频任务。
4. **二次幂等校验**：抢锁成功后查 MySQL 任务状态，若已完成则释放锁并 `return`。
5. **执行核心业务逻辑**：这是唯一允许变化的部分（第三方 API 调用必须包裹 `1s -> 2s -> 4s` 指数退避重试，最多 3 次）。
6. **状态落盘与唯一索引兜底**：更新 MySQL 任务状态为“已完成”（捕获 `DuplicateKeyException` 唯一键冲突作为物理防重兜底），更新 Redis 状态，在 `finally` 块中释放锁。

### 异常处理与锁释放铁律
- 核心业务逻辑中的未恢复异常必须抛出，不允许 `catch` 后吞掉，交由 RocketMQ 阶梯延迟重试（最多 3 次后转入死信队列标记为“失败”）。
- 锁的释放必须放在 `finally` 块中，且释放前必须检查 `lock.isHeldByCurrentThread()`。
- 禁止在消费者中使用本地锁（`synchronized` / `ReentrantLock`），禁止硬编码锁过期时间。

## 重要提示

- 写任何代码前必须完整阅读 memory-bank/@architecture.md
- 写任何代码前必须完整阅读 memory-bank/@design-document.md
- 每完成一个重大功能或里程碑后，必须更新 memory-bank/@architecture.md
