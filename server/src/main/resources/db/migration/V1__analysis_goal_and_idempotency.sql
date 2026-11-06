SET @video_goal_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_task_record' AND column_name = 'user_goal'
);
SET @video_goal_sql = IF(
    @video_goal_exists = 0,
    'ALTER TABLE video_task_record ADD COLUMN user_goal VARCHAR(500) NULL AFTER video_md5',
    'SELECT 1'
);
PREPARE video_goal_stmt FROM @video_goal_sql;
EXECUTE video_goal_stmt;
DEALLOCATE PREPARE video_goal_stmt;

SET @video_goal_digest_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'video_task_record' AND column_name = 'goal_digest'
);
SET @video_goal_digest_sql = IF(
    @video_goal_digest_exists = 0,
    'ALTER TABLE video_task_record ADD COLUMN goal_digest VARCHAR(64) NULL AFTER user_goal',
    'SELECT 1'
);
PREPARE video_goal_digest_stmt FROM @video_goal_digest_sql;
EXECUTE video_goal_digest_stmt;
DEALLOCATE PREPARE video_goal_digest_stmt;

SET @summary_goal_digest_exists = (
    SELECT COUNT(1) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'ai_summary_result' AND column_name = 'goal_digest'
);
SET @summary_goal_digest_sql = IF(
    @summary_goal_digest_exists = 0,
    'ALTER TABLE ai_summary_result ADD COLUMN goal_digest VARCHAR(64) NULL AFTER video_md5',
    'SELECT 1'
);
PREPARE summary_goal_digest_stmt FROM @summary_goal_digest_sql;
EXECUTE summary_goal_digest_stmt;
DEALLOCATE PREPARE summary_goal_digest_stmt;

UPDATE ai_summary_result
SET goal_digest = SHA2(CONCAT('legacy:', video_md5), 256)
WHERE goal_digest IS NULL OR goal_digest = '';

ALTER TABLE ai_summary_result
    MODIFY COLUMN goal_digest VARCHAR(64) NOT NULL;

SET @summary_md5_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_summary_result' AND index_name = 'uk_summary_md5'
);
SET @summary_md5_index_sql = IF(
    @summary_md5_index_exists > 0,
    'ALTER TABLE ai_summary_result DROP INDEX uk_summary_md5',
    'SELECT 1'
);
PREPARE summary_md5_index_stmt FROM @summary_md5_index_sql;
EXECUTE summary_md5_index_stmt;
DEALLOCATE PREPARE summary_md5_index_stmt;

SET @summary_video_goal_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE() AND table_name = 'ai_summary_result' AND index_name = 'uk_summary_video_goal'
);
SET @summary_video_goal_index_sql = IF(
    @summary_video_goal_index_exists = 0,
    'ALTER TABLE ai_summary_result ADD UNIQUE KEY uk_summary_video_goal (video_md5, goal_digest)',
    'SELECT 1'
);
PREPARE summary_video_goal_index_stmt FROM @summary_video_goal_index_sql;
EXECUTE summary_video_goal_index_stmt;
DEALLOCATE PREPARE summary_video_goal_index_stmt;
