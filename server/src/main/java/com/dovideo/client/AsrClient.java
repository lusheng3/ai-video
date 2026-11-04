package com.dovideo.client;

import java.nio.file.Path;

/**
 * 语音转写（ASR）客户端抽象。
 * 通过面向接口编程，可随时把硅基流动 SenseVoice 替换为阿里云、腾讯云、讯飞或本地 faster-whisper，
 * 业务层代码无需任何改动。
 */
public interface AsrClient {

    /**
     * 将音频文件转写为逐字稿文本
     *
     * @param audioFile 16kHz 单声道音频文件（wav/mp3）
     * @return 逐字稿文本
     */
    String transcribe(Path audioFile);
}
