package com.dovideo.client.impl;

import com.dovideo.client.AsrClient;
import com.dovideo.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/**
 * 本地压测 Mock ASR：不调用任何外部付费接口，按可配置延迟返回模拟逐字稿，
 * 支撑 JMeter 零成本高并发压测与本地无网络开发。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mock-enabled", havingValue = "true", matchIfMissing = true)
public class MockAsrClient implements AsrClient {

    private final AppProperties appProperties;

    @Override
    public String transcribe(Path audioFile) {
        sleepQuietly(appProperties.getMockProcessDelayMs());
        log.info("mock_asr_transcribe audioFile={}", audioFile == null ? "null" : audioFile.getFileName());
        return """
                00:00 大家好，今天我们来讲二叉树的前序遍历。
                00:45 前序遍历的顺序是：先访问根节点，再访问左子树，最后访问右子树。
                02:30 接下来我们看一下递归版本的代码实现。
                05:10 最后总结一下，前序遍历的时间复杂度是 O(n)，空间复杂度取决于递归栈的深度。
                """;
    }

    private void sleepQuietly(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
