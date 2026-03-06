# 📰 网站资讯智能采集与推送系统

> 基于 Spring Boot + Jsoup + LLM 的每日资讯自动采集、智能摘要与多渠道推送系统

## 一、项目简介

本系统用于监控政府官网、监管机构网站等信息源，**每日自动采集最新资讯**，借助 **LLM 大模型** 生成智能摘要，并通过 **邮件** 和 **企业微信** 推送给指定人员。

### 典型使用场景

- 监控反洗钱相关政策动态
- 跟踪金融监管总局最新公告
- 收集合规相关的法规变更
- 行业资讯的自动化整理与分发

## 二、核心功能

| 功能 | 说明 |
|------|------|
| 🔍 **网站抓取** | 基于 Jsoup 的通用网页爬虫，支持通过 CSS 选择器配置抓取规则 |
| 📅 **当日过滤** | 自动识别文章日期，只采集当天最新发布的内容 |
| 🔑 **关键词过滤** | 支持输入关键词，只采集与关键词相关的资讯 |
| 🤖 **LLM 智能摘要** | 调用大模型对每篇文章和整体资讯生成专业分析摘要 |
| 📧 **邮件推送** | 发送精美 HTML 格式的资讯日报邮件 |
| 💬 **企业微信推送** | 通过 Webhook 发送 Markdown 格式的资讯摘要 |
| ⏰ **定时执行** | 基于 Spring @Scheduled + Cron 表达式，支持自定义执行时间 |
| 🌐 **动态管理网站** | 提供 REST API，运行时动态添加/删除监控网站 |
| 📄 **附件识别** | 自动提取文章中的 PDF、Word、Excel 等附件链接 |
| 🖼️ **图片提取** | 自动提取文章正文中的图片链接 |

## 三、技术栈

| 技术 | 版本 | 用途 |
|------|------|------|
| Spring Boot | 2.7.18 | 基础框架 |
| JDK | 1.8 | 运行环境 |
| Jsoup | 1.17.2 | 网页抓取与解析 |
| Hutool | 5.8.25 | 工具类库（日期、正则等） |
| Fastjson2 | 2.0.47 | JSON 处理 |
| Spring Mail | - | 邮件发送 |
| Lombok | - | 简化代码 |

## 四、项目结构

```
web-info-collector/
├── pom.xml                                     # Maven 配置
├── README.md                                   # 项目说明
└── src/main/
    ├── java/com/info/collector/
    │   ├── WebInfoCollectorApplication.java    # 启动类
    │   ├── config/
    │   │   ├── CollectorProperties.java        # 配置属性映射
    │   │   └── RestTemplateConfig.java         # RestTemplate 配置
    │   ├── model/
    │   │   ├── SiteConfig.java                 # 网站配置模型
    │   │   ├── Article.java                    # 文章模型
    │   │   └── CollectResult.java              # 采集结果模型
    │   ├── dto/
    │   │   ├── CollectRequest.java             # 采集请求 DTO
    │   │   └── ApiResponse.java                # 统一响应封装
    │   ├── service/
    │   │   ├── CrawlerService.java             # 网页爬虫服务
    │   │   ├── LlmService.java                 # LLM 大模型服务
    │   │   ├── NotifyService.java              # 通知推送服务
    │   │   └── CollectorService.java           # 采集编排服务（核心）
    │   ├── scheduler/
    │   │   └── CollectScheduler.java           # 定时任务调度
    │   └── controller/
    │       └── CollectorController.java        # REST API 控制器
    └── resources/
        └── application.yml                      # 应用配置文件
```

## 五、快速开始

### 5.1 环境要求

- JDK 1.8+
- Maven 3.6+
- 通义千问 API Key（[阿里云百炼平台](https://bailian.console.aliyun.com/) 申请）

### 5.2 获取通义千问 API Key

1. 访问 [阿里云百炼平台](https://bailian.console.aliyun.com/)
2. 注册/登录阿里云账号
3. 开通 DashScope 服务
4. 在「API-KEY 管理」中创建 API Key
5. 新用户有免费额度，`qwen-plus` 性价比最高

### 5.3 修改配置

编辑 `src/main/resources/application.yml`：

```yaml
collector:
  # 1. 通义千问 LLM 配置
  llm:
    api-url: https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
    api-key: sk-your-dashscope-api-key   # 替换为你的 API Key
    model: qwen-plus                      # 可选: qwen-turbo(快) / qwen-plus(推荐) / qwen-max(强)

  # 2. 邮件通知（必填 - 以QQ邮箱为例）
  notify:
    mail-to: your-email@qq.com

  # 3. 定时任务（默认每天 8:30）
  schedule:
    cron: "0 30 8 * * ?"

# 4. 邮箱 SMTP 配置
spring:
  mail:
    host: smtp.qq.com
    username: your-email@qq.com
    password: your-authorization-code   # QQ邮箱授权码
```

### 5.4 启动项目

```bash
cd web-info-collector
mvn spring-boot:run
```

## 六、API 接口文档

### 6.1 手动触发采集

```bash
# 采集所有网站当天全部资讯（并推送通知）
curl -X POST http://localhost:8083/api/collector/collect

# 按关键词采集
curl -X POST http://localhost:8083/api/collector/collect \
  -H "Content-Type: application/json" \
  -d '{"keyword": "反洗钱"}'

# 指定网站 + 关键词
curl -X POST http://localhost:8083/api/collector/collect \
  -H "Content-Type: application/json" \
  -d '{
    "keyword": "反洗钱",
    "siteNames": ["中国人民银行-反洗钱"],
    "notify": true
  }'
```

### 6.2 预览（不推送通知）

```bash
curl -X POST http://localhost:8083/api/collector/preview \
  -H "Content-Type: application/json" \
  -d '{"keyword": "监管"}'
```

### 6.3 管理监控网站

```bash
# 查看所有网站
curl http://localhost:8083/api/collector/sites

# 动态添加网站
curl -X POST http://localhost:8083/api/collector/sites \
  -H "Content-Type: application/json" \
  -d '{
    "name": "中国证监会",
    "url": "http://www.csrc.gov.cn/csrc/c100028/common_list.shtml",
    "listSelector": ".list-group li",
    "titleSelector": "a",
    "linkSelector": "a",
    "dateSelector": "span.date",
    "dateFormat": "yyyy-MM-dd",
    "contentSelector": ".detail-content",
    "enabled": true
  }'

# 删除动态添加的网站
curl -X DELETE http://localhost:8083/api/collector/sites/中国证监会
```

## 七、配置网站抓取规则

每个监控网站需要配置以下 **CSS 选择器**，告诉爬虫如何从网页中提取信息：

| 配置项 | 说明 | 示例 |
|--------|------|------|
| `listSelector` | 列表页中每条新闻的容器 | `.newslist ul li` |
| `titleSelector` | 标题元素（相对于容器） | `a` |
| `linkSelector` | 链接元素（取 href 属性） | `a` |
| `dateSelector` | 日期元素 | `span` |
| `dateFormat` | 日期格式 | `yyyy-MM-dd` |
| `contentSelector` | 详情页正文容器 | `#zoom`、`.content` |

### 如何获取 CSS 选择器？

1. 用 Chrome 打开目标网站
2. 按 `F12` 打开开发者工具
3. 使用 **元素选择器**（左上角箭头图标）点击页面中的新闻标题
4. 在 Elements 面板中查看 HTML 结构，确定合适的 CSS 选择器

## 八、LLM 对接说明

本系统使用 **OpenAI Chat Completions 兼容接口**，可对接以下服务：

| LLM 服务 | api-url 配置 | api-key | 推荐模型 |
|-----------|-------------|---------|----------|
| **通义千问（默认）** | `https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions` | 阿里云百炼 API Key | `qwen-plus` |
| Ollama（本地） | `http://localhost:11434/v1/chat/completions` | 随意填写 | `qwen2.5:7b` |
| OpenAI | `https://api.openai.com/v1/chat/completions` | OpenAI API Key | `gpt-4o-mini` |
| 其他兼容服务 | 对应的 API 地址 | 对应的 Key | - |

### 通义千问可用模型对比

| 模型 | 特点 | 适用场景 |
|------|------|----------|
| `qwen-turbo` | 速度快、成本低 | 简单摘要、大批量处理 |
| `qwen-plus` | **性价比最高（推荐）** | 日常资讯分析、摘要生成 |
| `qwen-max` | 能力最强 | 复杂政策分析、深度解读 |

## 九、企业微信推送配置

1. 在企业微信群中添加 **群机器人**
2. 复制机器人的 **Webhook 地址**
3. 填入配置文件：

```yaml
collector:
  notify:
    wechat-webhook: https://qyapi.weixin.qq.com/cgi-bin/webhook/send?key=YOUR_KEY
```

## 十、邮件效果预览

邮件报告包含以下内容：

```
┌──────────────────────────────────┐
│     📰 每日资讯摘要              │
│     2026年03月04日               │
│     🔍 关键词：反洗钱            │
├──────────────────────────────────┤
│                                  │
│  ■ 中国人民银行-反洗钱           │
│  ┌─ LLM 智能摘要 ─────────────┐ │
│  │ ## 今日要闻概览              │ │
│  │ 央行发布反洗钱新规...        │ │
│  │ ## 重点资讯                  │ │
│  │ - **标题1** - 摘要...       │ │
│  │ - **标题2** - 摘要...       │ │
│  │ ## 合规关注点                │ │
│  │ ⚠️ 新规要求...              │ │
│  └─────────────────────────────┘ │
│                                  │
│  ┌─ 文章卡片 ────────────────┐   │
│  │ 标题（可点击跳转原文）      │   │
│  │ 📅 2026-03-04 | 🏛️ 央行  │   │
│  │ 摘要内容...                 │   │
│  │ 📄 附件1.pdf  📄 附件2.doc │   │
│  └────────────────────────────┘   │
│                                  │
├──────────────────────────────────┤
│  由「资讯采集系统」自动生成       │
└──────────────────────────────────┘
```

## 十一、常见问题

### Q1: 抓取不到文章？

- 检查 CSS 选择器是否正确（用浏览器 F12 确认）
- 部分网站可能有反爬机制，尝试调大 `request-interval`
- 查看日志文件 `logs/web-info-collector.log` 了解详情

### Q2: 日期过滤不生效？

- 确认 `dateFormat` 与网页上的日期格式一致
- 系统支持自动识别多种日期格式，但特殊格式需手动配置
- 如果日期无法解析，文章不会被日期过滤掉（会全部保留）

### Q3: LLM 调用超时？

- 本地 Ollama 首次加载模型较慢，需等待几分钟
- 可在配置中增加 `RestTemplate` 的读取超时时间
- 检查 LLM 服务是否正常运行：`curl http://localhost:11434/v1/models`

### Q4: 邮件发送失败？

- QQ邮箱需使用**授权码**而非登录密码
- 确认 SMTP 服务已开启（QQ邮箱 → 设置 → 账户 → POP3/SMTP服务）
- 检查端口和 SSL 配置是否正确

## 十二、扩展建议

- **持久化存储**：将采集结果存入数据库（MySQL/MongoDB），支持历史查询
- **Web 管理界面**：开发前端页面，可视化管理网站配置和查看历史报告
- **多线程采集**：使用线程池并行抓取多个网站，提升效率
- **代理 IP 池**：接入代理服务，避免 IP 被封
- **钉钉/飞书推送**：扩展 NotifyService 支持更多推送渠道
- **Redis 缓存**：避免重复采集相同文章
