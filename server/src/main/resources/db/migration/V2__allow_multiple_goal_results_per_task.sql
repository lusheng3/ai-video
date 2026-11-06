SET @summary_task_unique_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_summary_result'
      AND index_name = 'uk_summary_task'
);
SET @summary_task_index_sql = IF(
    @summary_task_unique_exists > 0,
    'ALTER TABLE ai_summary_result DROP INDEX uk_summary_task',
    'SELECT 1'
);
PREPARE summary_task_index_stmt FROM @summary_task_index_sql;
EXECUTE summary_task_index_stmt;
DEALLOCATE PREPARE summary_task_index_stmt;

SET @summary_task_regular_index_exists = (
    SELECT COUNT(1) FROM information_schema.statistics
    WHERE table_schema = DATABASE()
      AND table_name = 'ai_summary_result'
      AND index_name = 'idx_summary_task'
);
SET @summary_task_regular_index_sql = IF(
    @summary_task_regular_index_exists = 0,
    'ALTER TABLE ai_summary_result ADD INDEX idx_summary_task (task_id)',
    'SELECT 1'
);
PREPARE summary_task_regular_index_stmt FROM @summary_task_regular_index_sql;
EXECUTE summary_task_regular_index_stmt;
DEALLOCATE PREPARE summary_task_regular_index_stmt;
