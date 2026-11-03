-- =========================================================================
-- AI长视频解析平台 - 4张核心业务表（100%对齐《项目拷打》PDF第9~11页规范）
-- 规范要点：
-- 1. 全表标配 create_time, update_time, is_deleted (逻辑删除)
-- 2. 状态与账户等级全部采用 TINYINT，绝不滥用 VARCHAR
-- 3. 依据真实查询建联合索引：(user_id, create_time) 与 (status, create_time)
-- 4. 大字段垂直分表：将万字逐字稿 LONGTEXT 与 AI 结构化 JSON 剥离出主表
-- 5. 底层物理防重兜底：对 video_md5 / task_id 建立 UNIQUE 唯一索引
-- =========================================================================

CREATE DATABASE IF NOT EXISTS dovideo_ai DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE dovideo_ai;

-- 1. 用户主表 (user_info)
CREATE TABLE IF NOT EXISTS user_info (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '用户主键ID',
    username      VARCHAR(64)  NOT NULL COMMENT '登录用户名',
    password_hash VARCHAR(128) NOT NULL COMMENT '密码SHA-256加盐哈希',
    nickname      VARCHAR(64)  NOT NULL COMMENT '用户昵称',
    account_type  TINYINT      NOT NULL DEFAULT 0 COMMENT '账户权限级别：0-免费用户，1-付费VIP用户',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='用户主表';

-- 2. 视频任务流水核心表 (video_task_record)
CREATE TABLE IF NOT EXISTS video_task_record (
    id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '任务主键ID(taskId)',
    user_id       BIGINT       NOT NULL COMMENT '关联用户ID',
    file_name     VARCHAR(255) NOT NULL COMMENT '视频原始文件名',
    total_size    BIGINT       NOT NULL DEFAULT 0 COMMENT '视频文件总字节数',
    total_chunks  INT          NOT NULL DEFAULT 1 COMMENT '分片总数',
    video_md5     VARCHAR(32)  NOT NULL COMMENT '视频文件128位MD5指纹(32位hex)',
    file_url      VARCHAR(512) NULL     COMMENT 'MinIO中的对象存储路径(videos/{md5}/{name})',
    status        TINYINT      NOT NULL DEFAULT 0 COMMENT '任务状态机：0-待上传/已上传待分析，1-排队中，2-处理中，3-已完成，4-失败，5-合并中',
    error_msg     VARCHAR(512) NULL     COMMENT '失败错误原因(供前端轮询弹窗提示)',
    create_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time   DATETIME(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    is_deleted    TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    KEY idx_video_md5 (video_md5),
    KEY idx_user_create (user_id, create_time),
    KEY idx_status_create (status, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频任务流水核心主表';

-- 3. 原始转录明细表 (video_transcription) —— 垂直分表剥离万字长文本
CREATE TABLE IF NOT EXISTS video_transcription (
    id                    BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id               BIGINT      NOT NULL COMMENT '关联的任务ID',
    video_md5             VARCHAR(32) NOT NULL COMMENT '视频MD5指纹(内容级唯一防重)',
    transcription_content LONGTEXT    NOT NULL COMMENT 'ASR语音转写出的长文本逐字稿',
    word_count            INT         NOT NULL DEFAULT 0 COMMENT '总字数统计',
    create_time           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time           DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    is_deleted            TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_transcription_md5 (video_md5),
    UNIQUE KEY uk_transcription_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='视频原始转录长文本明细表(垂直拆分)';

-- 4. AI分析结果表 (ai_summary_result) —— 垂直分表剥离结构化JSON产物
CREATE TABLE IF NOT EXISTS ai_summary_result (
    id              BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键ID',
    task_id         BIGINT      NOT NULL COMMENT '关联的任务ID',
    video_md5       VARCHAR(32) NOT NULL COMMENT '视频MD5指纹(内容级唯一防重)',
    structured_data JSON        NOT NULL COMMENT '大模型输出的结构化总结JSON(summary/key_points/chapters)',
    token_cost      INT         NOT NULL DEFAULT 0 COMMENT '本次调用消耗的Token数',
    create_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) COMMENT '创建时间',
    update_time     DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3) COMMENT '更新时间',
    is_deleted      TINYINT     NOT NULL DEFAULT 0 COMMENT '逻辑删除：0-未删除，1-已删除',
    PRIMARY KEY (id),
    UNIQUE KEY uk_summary_md5 (video_md5),
    UNIQUE KEY uk_summary_task (task_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci COMMENT='AI结构化总结结果表(垂直拆分)';
