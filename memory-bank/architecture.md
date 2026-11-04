# 系统架构映射（Architecture Map）

本文档记录 `DOVideo-AI-v2`（AI长视频解析平台）的目录结构、核心文件职责及模块边界。每次完成实施计划中的一个步骤后必须同步更新。

## 1. 整体架构分层（IDEA 视图）

```text
DOVideo-AI-v2/
├── AGENTS.md                         # AI 协作与开发铁律（含 MQ 消费者六步规范与 memory-bank 规则）
├── CLAUDE.md                         # 与 AGENTS.md 保持同步的指令文件
├── docker-compose.yml                # MySQL 8 (13306) + Redis 7 (16379) + MinIO (9000/9001) + RocketMQ 5.3.4 一键编排
├── rocketmq/
│   └── broker.conf                   # RocketMQ Broker 配置文件（含 18 级延迟消息与刷盘说明）
├── memory-bank/                      # 项目长期记忆真源（Source of Truth）
└── server/                           # Spring Boot 3 后端工程（在 IDEA 中打开此目录）
    ├── pom.xml                       # Maven 依赖配置（含 spring-boot-starter-aop）
    └── src/
        ├── main/
        │   ├── java/com/dovideo/
        │   │   ├── ServerApplication.java               # 启动类（@MapperScan("com.dovideo.mapper")）
        │   │   ├── annotation/                          # 【自定义注解层】
        │   │   │   └── RateLimit.java                   # 令牌桶限流注解 (@RateLimit，支持 rate/capacity 接口级覆盖)
        │   │   ├── aspect/                              # 【AOP 切面层】
        │   │   │   └── RateLimitAspect.java             # Redis Hash + Lua 惰性令牌桶限流切面（区分免费/VIP配额）
        │   │   ├── client/                              # 【第三方 AI 客户端层（接口 + 多实现，可插拔）】
        │   │   │   ├── AsrClient.java                   # 语音转写接口（可换硅基/阿里/本地whisper）
        │   │   │   ├── LlmClient.java                   # 结构化总结接口（可换 DeepSeek 官方/硅基）
        │   │   │   ├── AiClientException.java           # 带 retryable 标记的第三方调用异常
        │   │   │   ├── dto/
        │   │   │   │   ├── Chapter.java                 # 章节时间轴
        │   │   │   │   └── SummaryResult.java           # 结构化总结结果
        │   │   │   ├── prompt/
        │   │   │   │   └── SummaryPromptBuilder.java    # 四段式 Prompt + Few-Shot
        │   │   │   └── impl/
        │   │   │       ├── OpenAiCompatibleAsrClient.java  # OpenAI 兼容 /audio/transcriptions
        │   │   │       ├── OpenAiCompatibleLlmClient.java  # OpenAI 兼容 /chat/completions
        │   │   │       ├── MockAsrClient.java           # 压测 Mock ASR
        │   │   │       └── MockLlmClient.java           # 压测 Mock LLM
        │   │   ├── common/                              # 【公共层】统一返回体与错误码
        │   │   │   ├── ErrorCode.java
        │   │   │   └── Result.java
        │   │   ├── config/                              # 【配置与拦截器层】
        │   │   │   ├── AppProperties.java               # 外部化业务参数（ASR/LLM 独立配置）
        │   │   │   ├── AuthInterceptor.java             # Redis Token 登录鉴权拦截器
        │   │   │   ├── MinioConfig.java                 # MinioClient Bean 初始化配置
        │   │   │   ├── AiClientConfig.java              # OkHttpClient（AI 调用专用超时配置）
        │   │   │   └── WebMvcConfig.java                # 拦截器注册与跨域配置
        │   │   ├── controller/                          # 【控制层 Controller】
        │   │   │   ├── HealthController.java            # 健康检查接口 (/api/health)
        │   │   │   ├── UserController.java              # 用户注册/登录/退出接口 (/api/user/*)
        │   │   │   └── UploadController.java            # 分片预检/上传/合并/预签名播放/列表 (/api/upload/*, /api/media/*)
        │   │   ├── service/                             # 【业务逻辑层 Service】
        │   │   │   ├── AuthService.java                 # 用户注册、登录、Redis 会话管理
        │   │   │   ├── MinioService.java                # MinIO 私有桶、Lifecycle规则、composeObject合并、预签名URL
        │   │   │   ├── UploadService.java               # 秒传预检、Redis Set 断点续传、先落盘后记账、合并锁与 composeObject
        │   │   │   ├── UploadReconciliationJob.java     # 后台定时校准卡在“合并中(status=5)”的任务，保障最终一致性
        │   │   │   └── AudioExtractionService.java      # MinIO 下载 + FFmpeg 提取 16kHz 音频 + 临时文件清理
        │   │   ├── mapper/                              # 【持久层 DAO / Mapper】
        │   │   │   ├── UserInfoMapper.java              # 用户主表 Mapper
        │   │   │   ├── VideoTaskRecordMapper.java       # 视频任务主表 Mapper
        │   │   │   ├── VideoTranscriptionMapper.java    # 转录长文本明细表 Mapper
        │   │   │   └── AiSummaryResultMapper.java       # AI 结构化总结明细表 Mapper
        │   │   ├── entity/                              # 【实体层 Entity（对应 4 张垂直分表）】
        │   │   │   ├── UserInfo.java                    # 表1: user_info
        │   │   │   ├── VideoTaskRecord.java             # 表2: video_task_record
        │   │   │   ├── VideoTranscription.java          # 表3: video_transcription (LONGTEXT)
        │   │   │   └── AiSummaryResult.java             # 表4: ai_summary_result (JSON)
        │   │   ├── dto/                                 # 【数据传输对象层 DTO】
        │   │   │   ├── AuthRequest.java
        │   │   │   ├── AuthResponse.java
        │   │   │   ├── UploadCheckResponse.java         # /upload/check 响应（秒传标记 + 已传分片集合）
        │   │   │   ├── UploadMergeRequest.java          # /upload/merge 请求体
        │   │   │   └── UploadMergeResponse.java         # /upload/merge 响应体
        │   │   ├── enums/                               # 【业务状态机枚举层】
        │   │   │   ├── AccountTypeEnum.java             # 0-免费用户，1-付费VIP
        │   │   │   └── TaskStatusEnum.java              # 0-待分析，1-排队中，2-处理中，3-已完成，4-失败，5-合并中
        │   │   └── exception/                           # 【异常处理层】
        │   │       ├── BusinessException.java
        │   │       └── GlobalExceptionHandler.java
        │   └── resources/
        │       ├── application.yml                      # 应用主配置
        │       ├── db/
        │       │   └── schema.sql                       # 4 张核心业务表建表 SQL（含索引与唯一约束）
        │       └── lua/
        │           └── token_bucket.lua                 # Redis Hash 惰性计算令牌桶 Lua 脚本
        └── test/
            └── java/com/dovideo/
                ├── aspect/
                │   └── RateLimitAspectTest.java         # 令牌桶 Lua 切面单元测试
                ├── client/impl/
                │   └── OpenAiCompatibleLlmClientTest.java # MockWebServer 模拟 429重试/401不重试/JSON围栏
                ├── exception/
                │   └── GlobalExceptionHandlerTest.java
                ├── util/
                │   └── ExponentialBackoffRetryTest.java # 1s->2s->4s 指数退避与永久异常快速失败
                └── service/
                    ├── AuthServiceTest.java             # 注册/登录/唯一键冲突单元测试
                    ├── MinioServiceTest.java            # MinIO Lifecycle/composeObject/预签名URL单元测试
                    └── UploadServiceTest.java           # 秒传/断点续传/分片MD5校验/先落盘后记账/合并锁/校准单测
```
