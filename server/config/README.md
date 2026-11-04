# 真实 API Key 本地配置说明

## 1. 复制模板

在 `server/config/` 目录下：

```powershell
Copy-Item application-local.yml.example application-local.yml
```

`application-local.yml` 已被 `.gitignore` 忽略，**不会被提交到 GitHub**。

## 2. 申请 Key

| 服务 | 地址 | 用途 |
|---|---|---|
| 硅基流动 SiliconFlow | https://cloud.siliconflow.cn/account/ak | ASR（SenseVoice）+ 可选的 DeepSeek 模型 |
| DeepSeek 官方 | https://platform.deepseek.com/api_keys | 结构化总结（deepseek-chat） |

> 如果只申请一个 Key：可以把 ASR 和 LLM 都指向硅基流动，
> `llm.base-url` 改为 `https://api.siliconflow.cn/v1`，
> `llm.model` 改为 `deepseek-ai/DeepSeek-V3`。

## 3. 填入 Key

编辑 `server/config/application-local.yml`：

```yaml
app:
  mock-enabled: false
  ai:
    asr:
      api-key: sk-你的硅基流动Key
    llm:
      api-key: sk-你的DeepSeekKey
```

## 4. 用 local profile 启动

```powershell
cd server
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

启动日志中应看到 `The following 1 profile is active: "local"`，并且不再出现 Mock ASR / Mock LLM 的日志。

## 5. 安全提醒

- 不要把 `application-local.yml` 提交到 Git；
- 不要把 Key 贴在公开聊天/截图里；
- 如果 Key 泄露，立即去控制台吊销并重新生成。
