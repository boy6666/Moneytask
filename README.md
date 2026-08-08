# Moneytask — 离线无感自动记账 App

一个基于 Android 通知监听的**无感自动记账**方案：你在美团、京东、微信、支付宝、银行卡消费后，通知被捕获 → 自动归并成**恰好一笔**账目 → 智能判断真实扣款账户 → 本地自动分类 → 写入本地账本。全程**不联网**、数据**只存本地**、可一键导出/恢复备份。

> 核心承诺（《MVP技术设计》）：
> **绝不重复记账**（一条真实消费 = 恰好一笔账目）、**绝不误并**（两笔真消费绝不合并）。

---

## 为什么"自动记账"难，以及我们怎么做

同一笔真实消费会从多个来源各推一条通知，例如你在美团用微信支付、实际扣的是招商银行卡：

| 来源 | 通知示例 | 渠道 |
|---|---|---|
| 美团 App | "您的美团订单已支付成功42.10元" | 商户 |
| 微信支付 | "使用招商银行信用卡(1356)支付￥42.10" | 支付 |
| 步步高/掌上生活 | "您尾号1356的招行信用卡消费42.10人民币" | 银行 |

如果 APP 每收到一条就记一笔，这笔消费会被记 **3 次**。Moneytask 用**三层去重防线**把它们归并为 1 笔，且不误并两笔真正的消费。

### 三层去重防线（绝不重复）

1. **单渠道幂等** — 同一通知 key（sourceKey）只处理一次。
2. **指纹去重** — 同内容指纹（SHA-256）只保留一条。
3. **链路归并** — 不同渠道、同金额、同 90s 时间窗、方向不冲突 → 归入同一 `CaptureGroup`，结算为一笔。

### 两块智能（不误并 + 真实账户）

- **绝不误并（宁可拆开）**：金额不同 / 超时间窗 / 方向冲突（支出 vs 收入）都拆成各自独立的一笔。
- **智能账户归因**：
  - 存在银行渠道 → 记真实扣款的**银行卡**（按卡尾号反查账户）；
  - 仅支付工具 → 记**微信钱包 / 支付宝余额**；
  - 信息不足 → 落默认账户并标待复核，不阻塞记账。

再加上**本地智能自动分类**（商户名 → 分类：滴滴→交通、美团→餐饮、京东→购物…）。

---

## 项目结构

目前仓库以 `ledger-core` 为核心：一个**纯 JVM / Kotlin** 模块，把产品最难、且开源项目（RealtimeLedger，MIT）没覆盖的差异点全部沉淀成可单测的逻辑。

```
ledger-core/
└── src/main/kotlin/com/moneytask/ledger/capture/
    ├── CaptureEvent.kt       采集事件（含 sourceKey / 指纹 / 方向 / 渠道）
    ├── CaptureGroup.kt       交易组状态机（OPEN / CLOSED / DISPUTED）与结论
    ├── CorrelationEngine.kt  ★ 链路归并引擎（不去重防误并的核心）
    ├── NotificationParser.kt 通知文本 → 结构化（金额多候选高/低权评分）
    ├── ChannelClassifier.kt  渠道判定（银行 > 支付 > 商户，来源名优先）
    ├── NotificationAdapter.kt 解析结果 → CaptureEvent（sourceKey + SHA-256 指纹）
    ├── AutoCategorizer.kt    商户名 → 分类（Rule 优先级）
    ├── LedgerWriter.kt       结论 → 账目（幂等落账 + 自动分类 + 待复核标记）
    ├── LedgerStore.kt        持久化抽象（Android 层以 Room 实现）
    ├── Account.kt / Category.kt / Transaction.kt / ParsedCapture.kt  领域模型
    └── ...（配套 19 个单元/集成测试）
```

**说明**：`ledger-core` 刻意不引入 Android / Room 依赖，目标是**无头可测**。Android 应用壳、`NotificationListenerService`、Room 实体与 Migration 属下一步（见路线图）。

---

## 构建 & 测试

```bash
# 需要 JDK 17+（本机以 JDK 21 编译、JVM target 17）
cd ledger-core
JAVA_HOME=/path/to/jdk ./gradlew test
```

19 个测试全绿，覆盖真实通知样本的**端到端**验证：

- 美团 6 条真实通知 → **恰好 1 笔**，账户=招商银行卡(1356)、分类=餐饮
- 滴滴 / 信用卡还款 → 各自 1 笔
- 落账幂等：同一结论重复写入 → 仍 1 笔（绝不重复记账的最终闸门）
- 两笔真消费 → 2 笔，绝不误并
- 金额歧义：`交易金额42.10` vs `可用额度￥58497.69` → 正确挑出 42.10

---

## 技术栈

| 领域 | 选型 |
|---|---|
| 核心逻辑 | Kotlin 2.x（纯 JVM，JVM target 17） |
| UI | Jetpack Compose + Material3（Android 壳） |
| 本地库 | Room + SQLite（Android 壳） |
| 系统能力 | NotificationListenerService / ForegroundService |
| 订阅/数据库 | Gradle（Kotlin DSL），JUnit 5 / kotlin.test |

---

## 路线图

- **[M1] 已基本完成**：链路归并引擎 + 解析器 + 智能归因 + 智能分类 + 落账（19 测试）。
- **Android 壳**：NotificationListenerService 监听、Room 实体 + Migration(1→2) + DatabaseSeeder、权限引导、常驻省电。
- **Phase 2**：截屏 OCR、周期/订阅记账、数据导入导出。
- **Phase 3**：手动录入兜底、账户/分类管理、报表。
- **Phase 4**：花销预测（同比/环比）、特殊时段群组（寒暑假等）。

> 许可证：核心逻辑为原创（MIT 式中立）；复用了 RealtimeLedger(MIT) 的解析/去重思路；AutoAccounting(GPL3) 仅作参考不并入。

---

*设计文档与调研材料不随仓库提交（见 `.gitignore`）。*
