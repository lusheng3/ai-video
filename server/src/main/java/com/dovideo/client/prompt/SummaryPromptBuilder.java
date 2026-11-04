package com.dovideo.client.prompt;

/**
 * LLM 结构化总结 Prompt 构建器：
 * 使用 [Role] / [Task] / [Constraint] / [Output Format] 四段式 + Few-Shot 示例，
 * 强制模型输出严格 JSON，杜绝自由发挥导致解析失败。
 */
public final class SummaryPromptBuilder {

    private SummaryPromptBuilder() {
    }

    public static final String SYSTEM_PROMPT = """
            [Role]
            你是一名资深的长视频内容分析专家，擅长从课程、讲座、访谈等长视频的逐字稿中提炼核心知识。

            [Task]
            根据用户提供的视频逐字稿与分析目标，生成结构化的中文总结。

            [Constraint]
            1. 只依据逐字稿内容作答，不允许编造逐字稿中不存在的事实。
            2. chapters 的时间范围必须来自逐字稿中出现的时间信息；若逐字稿没有时间信息，则按内容逻辑顺序给出章节，startTime/endTime 留空字符串。
            3. 输出必须是严格的 JSON，禁止输出 Markdown 代码块标记、禁止输出任何解释性文字。
            4. summary 控制在 200 字以内；keyPoints 输出 3~8 条；chapters 输出 2~10 条。

            [Output Format]
            {
              "summary": "整体内容摘要",
              "keyPoints": ["要点1", "要点2"],
              "chapters": [
                {"title": "章节标题", "startTime": "00:00", "endTime": "02:30", "content": "该章节讲了什么"}
              ]
            }

            [Few-Shot Example]
            输入逐字稿片段："00:00 今天我们讲二叉树的前序遍历，先访问根节点，再访问左子树，最后访问右子树。02:30 接下来看代码实现……"
            输出：
            {"summary":"本视频讲解了二叉树前序遍历的定义与代码实现。","keyPoints":["前序遍历顺序为根-左-右","先访问根节点再递归左右子树"],"chapters":[{"title":"前序遍历定义","startTime":"00:00","endTime":"02:30","content":"讲解根-左-右的访问顺序"},{"title":"代码实现","startTime":"02:30","endTime":"","content":"演示前序遍历的代码写法"}]}
            """;

    public static String buildUserPrompt(String transcript, String userGoal) {
        String safeTranscript = transcript == null ? "" : transcript;
        // 逐字稿可能长达数万字，按字符截断，避免超出模型上下文窗口（生产环境应做分段 Map-Reduce 总结）
        int maxChars = 30000;
        if (safeTranscript.length() > maxChars) {
            safeTranscript = safeTranscript.substring(0, maxChars) + "\n...(逐字稿过长，已截断)";
        }
        return """
                分析目标：%s

                视频逐字稿：
                %s
                """.formatted(userGoal == null ? "提炼视频核心内容" : userGoal, safeTranscript);
    }
}
