# 技术栈选型（Tech Stack）

本文档严格依据 `memory-bank/design-document.md` 与简历「项目二：AI长视频解析平台」确定最小可行、高可靠的技术栈。

## 1. 各层技术选型与理由

| 层级 | 选型 | 版本 | 选型理由（对齐面试话术） |
|---|---|---|---|
| **后端框架与运行时** | Java + Spring Boot | JDK 21 + Spring Boot 3.3.x | 主流企业级标准，生态成熟，配合 MyBatis-Plus、Redisson、RocketMQ 开箱即用 |
| **持久层与数据库** | MySQL + MyBatis-Plus | MySQL 8.0 + MyBatis-Plus 3.5.x | 支撑 4 张核心表（含 `LONGTEXT` 与 `JSON` 垂直分表）、联合索引优化与唯一约束物理防重 |
| **缓存、限流与分布式锁** | Redis + Redisson | Redis 7.x + Redisson 3.27.x | 一物三用（技术栈收敛）：① `Set` 存分片进度天然去重；② `Hash + Lua` 实现原子惰性令牌桶限流；③ `Redisson WatchDog` 解决长耗时转码锁过期问题 |
| **异步消息中间件** | Apache RocketMQ | RocketMQ 5.3.x (`rocketmq-spring-boot-starter`) | Java 同源生态，支持亿级消息堆积、原生 18 级阶梯延迟重试（`10s/30s/1min`）与死信队列（DLQ），契合长耗时重计算任务的削峰与容错 |
| **分布式对象存储** | MinIO | MinIO RELEASE.2024+ (`io.minio:minio:8.5.x`) | 100% 兼容 AWS S3 协议（切阿里云 OSS 零代码改动）；支持 `composeObject` 服务端原子合并、私有桶 Pre-signed URL 防盗链与 Lifecycle 自动清理僵尸分片 |
| **音视频与 AI 接口** | FFmpeg + 硅基流动 API | FFmpeg CLI + SiliconFlow (FunAudioLLM/SenseVoice + DeepSeek-V3) | 硅基流动同一平台覆盖 ASR 语音转写与 DeepSeek 结构化总结，一套 HTTP 客户端 + 指数退避重试器即可接入；同时内置 `mock-enabled` 开关供零成本压测 |
| **前端可视化工作台** | Vue 3 + Vite + Element Plus + SparkMD5 | Vue 3.4 + Vite 5 | 轻量实现 4 大验证板块（拖拽分片上传与 Web Worker 算 MD5、任务状态列表、转写稿展示、结构化总结与视频预签名播放） |
| **部署与测试** | Docker Compose + Flyway + JUnit 5 + JMeter | Compose v2 + JMeter 5.6 | Docker Compose 拉起 MySQL、Redis、MinIO、RocketMQ；Flyway 管理迁移；JUnit 5 + JMeter 验证并发、幂等与 RT |
| **可观测性** | Actuator + Micrometer + Prometheus | Spring Boot 3.5 | `/actuator/prometheus` 暴露提交、重复、锁等待、处理耗时和恢复指标 |

## 2. 关键配置约定
- **分片阈值**：固定 `5MB`（`5 * 1024 * 1024` 字节），匹配 MinIO `composeObject` 非末片最小 5MB 要求。
- **限流默认参数**：单用户每秒补充 `2` 个令牌、桶容量 `5`（压测 Profile 下可通过 `application.yml` 动态调大或关闭）。
- **重试默认参数**：内层第三方 API 指数退避 `1s -> 2s -> 4s`（最多 3 次）；外层 RocketMQ `maxReconsumeTimes = 3`。
