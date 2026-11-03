# 系统架构映射（Architecture Map）

本文档记录 `DOVideo-AI-v2`（AI长视频解析平台）的目录结构、核心文件职责及模块边界。每次完成实施计划中的一个步骤后必须同步更新。

## 1. 整体架构分层（IDEA 视图）

```text
DOVideo-AI-v2/
├── AGENTS.md                         # AI 协作与开发铁律（含 MQ 消费者六步规范与 memory-bank 规则）
├── CLAUDE.md                         # 与 AGENTS.md 保持同步的指令文件
├── docker-compose.yml                # MySQL 8 + Redis 7 (AOF) + MinIO + RocketMQ 5.3.4 一键编排
├── rocketmq/
│   └── broker.conf                   # RocketMQ Broker 配置文件（含 18 级延迟消息与刷盘说明）
├── memory-bank/                      # 项目长期记忆真源（Source of Truth）
└── server/                           # Spring Boot 3 后端工程（在 IDEA 中打开此目录）
    ├── pom.xml                       # Maven 依赖配置
    └── src/
        ├── main/
        │   ├── java/com/dovideo/
        │   │   ├── ServerApplication.java               # 启动类（@MapperScan("com.dovideo.mapper")）
        │   │   ├── common/                              # 【公共层】统一返回体与错误码
        │   │   │   ├── ErrorCode.java
        │   │   │   └── Result.java
        │   │   ├── config/                              # 【配置与拦截器层】
        │   │   │   ├── AppProperties.java               # 外部化业务参数
        │   │   │   ├── AuthInterceptor.java             # Redis Token 登录鉴权拦截器
        │   │   │   └── WebMvcConfig.java                # 拦截器注册与跨域配置
        │   │   ├── controller/                          # 【控制层 Controller】
        │   │   │   ├── HealthController.java            # 健康检查接口 (/api/health)
        │   │   │   └── UserController.java              # 用户注册/登录/退出接口 (/api/user/*)
        │   │   ├── service/                             # 【业务逻辑层 Service】
        │   │   │   └── AuthService.java                 # 用户注册、登录、Redis 会话管理
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
        │   │   │   └── AuthResponse.java
        │   │   ├── enums/                               # 【业务状态机枚举层】
        │   │   │   ├── AccountTypeEnum.java             # 0-免费用户，1-付费VIP
        │   │   │   └── TaskStatusEnum.java              # 0-待分析，1-排队中，2-处理中，3-已完成，4-失败，5-合并中
        │   │   └── exception/                           # 【异常处理层】
        │   │       ├── BusinessException.java
        │   │       └── GlobalExceptionHandler.java
        │   └── resources/
        │       ├── application.yml                      # 应用主配置
        │       └── db/
        │           └── schema.sql                       # 4 张核心业务表建表 SQL（含索引与唯一约束）
        └── test/
            └── java/com/dovideo/
                ├── exception/
                │   └── GlobalExceptionHandlerTest.java
                └── service/
                    └── AuthServiceTest.java             # 注册/登录/唯一键冲突单元测试
```
