# REDIS_SYNC 失败处理重构设计

> **问题**：Redis 扣减成功但 Kafka 发送失败时，当前 `RedisSyncStrategy.createOrder` 职责混乱（模式判断 + 同步发送 + 降级采样 + 异步委托一锅炖），且降级模式用裸 `sendAsync` 兜底导致丢消息破一致性。
>
> **目标**：单次失败不应用层重试，直接返回失败 + 幂等回补库存；三层职责分离（编排 / 决策 / 发送），每个"改哪里"有唯一答案。
>
> **状态**：设计定稿，待实现。

---

## 一、设计决策（已拍板）

| 决策 | 结论 | 理由 |
|---|---|---|
| 应用层重试 | **不做** | producer 已有 `retries:3` + 幂等内部重试；应用层重试=叠加 3s 超时延迟（长尾来源） |
| 单次失败返回 | **Fail Fast：返回失败 + 回补库存** | 强一致策略语义：要么确定成功、要么明确失败；库存回池后用户可再抢 |
| 回补行为 | **非成功一律回补**（TRANSIENT/FATAL 都回补） | 对用户都是"这次没抢到"，库存必须回池；区分仅用于日志/告警级别 |
| 回补幂等 | `refund_stock.lua`（ordered 存在才 INCR+DEL） | 重复回补不 double-INCR |
| 回补失败兜底 | **新增 LostOrderReconciler**（本次一并设计） | 回补自身失败/placeOrder 崩溃 → 库存泄漏，对账兜底 |
| 降级后异步 | 委托 `RedisAsyncStrategy`（pending+回调+补投），不裸发 | 可靠性链路复用，不重复实现 |
| 分层 | 编排（placeOrder）/ 决策（controller）/ 发送（sender） | 职责单一、可测、可扩展 |

---

## 二、类结构总览

```
┌─────────────────────────────────────────────────────────────┐
│ placeOrder（SeckillService，编排层）                         │
│   outcome = strategy.createOrder(...)                       │
│   if outcome 非 CONFIRMED/ACCEPTED:                          │
│       strategy.refundStock(...)   // 统一幂等回补            │
│       throw BizException(503)                                │
│   return "ok"                                                │
└──────────────────────────────┬──────────────────────────────┘
                               │
┌──────────────────────────────▼──────────────────────────────┐
│ SeckillStrategy 接口（薄外观）                               │
│   SendOutcome createOrder(...)   // void → SendOutcome      │
└──────────────────────────────┬──────────────────────────────┘
                               │ 实现
              ┌────────────────┼────────────────┐
              ▼                ▼                ▼
    ┌─────────────────┐ ┌──────────────┐ ┌──────────────┐
    │ RedisSyncStrategy│ │RedisAsyncStrategy│ │DBQueueStrategy│
    │  模式判断+委托   │ │  复用 sender   │ │  直发+保序    │
    └───────┬─────────┘ └──────┬───────┘ └──────────────┘
            │                  │
    ┌───────▼─────────┐ ┌──────▼───────────────────┐
    │ SyncDegradeController│ │ KafkaOrderSender        │
    │  窗口/阈值/采样/恢复 │ │  纯发送器（无窗口依赖） │
    └─────────────────┘ └──────────────────────────┘
```

**依赖方向（定死，防职责交叉）**：
- `KafkaOrderSender` **不碰窗口**——纯发送，返回 `SendOutcome`
- 窗口上报在**策略层**做：`outcome = sender.sendSync(msg); controller.record(outcome)`
- 策略之间不互相依赖（RedisSync 委托 RedisAsync 改为**都依赖 KafkaOrderSender**）

---

## 三、接口设计

### 3.1 SendOutcome（枚举，语义定死）

```java
package com.jolumn.vtslseckill.model.enums;

/**
 * Kafka 发送结果。语义按发送方式区分：
 *  - CONFIRMED：同步发送，broker 已 ack（消息确认送达）
 *  - ACCEPTED ：异步发送，pending 已落（受理，不代表送达；送达由回调/补偿保证）
 *  - TRANSIENT：瞬时失败（超时/网络抖动），可重试——但本设计不应用层重试，统一回补
 *  - FATAL    ：不可恢复（producer 关闭/序列化失败）
 */
public enum SendOutcome {
    CONFIRMED,   // 同步确认送达
    ACCEPTED,    // 异步受理（pending 已落）
    TRANSIENT,   // 瞬时失败 → 回补
    FATAL        // 不可恢复 → 回补
}
```

### 3.2 SeckillStrategy 接口变更

```java
public interface SeckillStrategy {
    int deductStock(Long activityId, Long userId);
    SendOutcome createOrder(SeckillActivity activity, Long userId, String orderNo);  // void → SendOutcome
    void refundStock(Long activityId, Long userId);
}
```

> **改动波及**：3 个策略实现 + placeOrder 调用处 + TimeoutCancelScheduler 不涉及（它只调 refundStock）+ 现有测试。

### 3.3 KafkaOrderSender（纯发送器，共享）

```java
package com.jolumn.vtslseckill.biz.mq;

@Component
public class KafkaOrderSender {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;
    // 配置：send 超时 / pending TTL / 补偿队列名

    /** 同步发送：send().get(3s)，broker ack → CONFIRMED；超时/失败 → TRANSIENT/FATAL */
    public SendOutcome sendSync(String msg) { ... }

    /** 异步可靠发送：pending + whenComplete 回调 + 补偿队列（从 RedisAsyncStrategy 迁移） */
    public void sendAsyncReliable(String msg) { ... }
}
```

### 3.4 SyncDegradeController（降级决策，独立可测）

```java
package com.jolumn.vtslseckill.biz.mq;

@Component
public class SyncDegradeController {

    private final SlidingWindowStats window;
    private final AtomicReference<SeckillMode> mode = new AtomicReference<>(SeckillMode.REDIS_SYNC);
    private final AtomicInteger recoverStreak = new AtomicInteger();
    private volatile long degradedAt;

    public boolean isSyncMode() { ... }
    /** 同步模式：记录窗口，超阈值切 REDIS_ASYNC */
    public void record(SendOutcome outcome) { ... }
    /** 降级模式：按 sample-rate 决定是否探针 */
    public boolean shouldSample() { ... }
    /** 探针成功：连续 recover-threshold 次 → 恢复 REDIS_SYNC */
    public void onSampleSuccess() { ... }
    /** 探针失败：清 recoverStreak（防伪恢复） */
    public void onSampleFailure() { ... }
    public SeckillMode currentMode() { ... }
}
```

### 3.5 RedisSyncStrategy（薄外观）

```java
@Component
public class RedisSyncStrategy implements SeckillStrategy {

    private final StockService stockService;
    private final KafkaOrderSender sender;
    private final SyncDegradeController degrade;

    @Override
    public SendOutcome createOrder(SeckillActivity activity, Long userId, String orderNo) {
        String msg = userId + ":" + activity.getId() + ":" + orderNo;
        if (degrade.isSyncMode()) {
            SendOutcome outcome = sender.sendSync(msg);   // 窗口上报在策略层
            degrade.record(outcome);
            return outcome;                                // 失败由 placeOrder 统一回补
        }
        // 降级模式
        if (degrade.shouldSample()) {
            SendOutcome probe = sender.sendSync(msg);
            if (probe == SendOutcome.CONFIRMED) {
                degrade.onSampleSuccess();
            } else {
                degrade.onSampleFailure();
            }
            sender.sendAsyncReliable(msg);   // 探针消息也走可靠异步（防探针失败丢消息）
            return SendOutcome.ACCEPTED;
        }
        sender.sendAsyncReliable(msg);
        return SendOutcome.ACCEPTED;
    }
}
```

### 3.6 placeOrder（编排层，统一回补）

```java
// SeckillService.placeOrder 变更
SendOutcome outcome;
try {
    outcome = strategy.createOrder(activity, userId, orderNo);
} catch (RuntimeException e) {
    throw new BizException(503, "系统繁忙，请稍后重试");
}

if (outcome == SendOutcome.CONFIRMED || outcome == SendOutcome.ACCEPTED) {
    return "ok";
}
// TRANSIENT / FATAL → 统一幂等回补 + 503
strategy.refundStock(activityId, userId);
throw new BizException(503, "系统繁忙，请稍后重试");
```

> **回补职责归属**：放 placeOrder（编排层）——"扣了、补不补"是跨策略的编排动作，策略不自调自补。异常仍保留（`deductStock` 的 Redis 连接异常继续 throw → catch → 503），但发送失败走 outcome 不再靠 throw。

---

## 四、LostOrderReconciler（回补失败兜底，盲区 2 的补漏）

**场景**：Redis 扣减成功 → Kafka 发送失败 → placeOrder 回补，但：
1. placeOrder 在 refund 之前 JVM 崩溃 → ordered 标记在、DB 无订单 → 库存泄漏
2. refund 时 Redis 也挂了 → 回补没执行

```java
package com.jolumn.vtslseckill.scheduler;

@Component
public class LostOrderReconciler {

    /** 扫描 ordered:{activityId}:{userId} 标记存在但 DB 无对应订单的，超时（如 10min）后幂等回补 */
    @Scheduled(fixedDelayString = "${seckill.lost-order-scan-ms:600000}")
    public void reconcileLostOrders() {
        // 1. SCAN ordered:* 标记
        // 2. 解析 activityId:userId，查 t_seckill_order 是否有 (activity_id, user_id) 记录
        // 3. 无记录 + 标记存在超过阈值 → refund_stock.lua 幂等回补
        // 4. 带分布式锁（复用 ReconciliationScheduler 的锁模式）
    }
}
```

**关键点**：
- 依赖**同库查询**（`SeckillOrderRepository` 按 activityId+userId 查）
- 幂等：refund Lua 本身幂等，重复扫描安全
- 分布式锁：多实例只一个执行
- 这是之前 Q3 的 P0 改造项，本次一并落地，闭合"回补失败"的洞

---

## 五、迁移清单（RedisAsyncStrategy → KafkaOrderSender）

| 迁移项 | 从 | 到 |
|---|---|---|
| `createOrder` 的 pending 写入 + whenComplete 回调 + 补偿队列 | `RedisAsyncStrategy` | `KafkaOrderSender.sendAsyncReliable` |
| `refundStock` / `deductStock` | 保留在策略 | 不动 |
| 配置（pending-ttl / compensate-* / resend-*） | application.yml | 不变 |
| `RedisAsyncStrategy` 构造 | KafkaTemplate + RedisTemplate | 依赖 `KafkaOrderSender` |

⚠️ **迁移风险**：pending+回调链路是已验证的可靠性实现，迁移后需回归测试（模拟发送失败 → 回调补偿 → 补投），不是纯搬家。

---

## 六、测试点设计

| 类 | 测试点 |
|---|---|
| `SyncDegradeControllerTest` | 窗口未满不降级 / 平均耗时超阈值降级 / 超时率超阈值降级 / 连续 5 次探针成功恢复 / 探针失败清 streak / 10s 防抖 |
| `KafkaOrderSenderTest` | sendSync 成功→CONFIRMED / 超时→TRANSIENT / producer 异常→FATAL / sendAsyncReliable 写 pending+回调删 pending |
| `RedisSyncStrategyTest` | 同步模式失败→返回 outcome 不回补（回补在 placeOrder）/ 降级模式→ACCEPTED / 探针成功→恢复 |
| `SeckillServiceTest` | outcome=TRANSIENT→refundStock 被调+503 / CONFIRMED→"ok" / ACCEPTED→"ok" |
| `LostOrderReconcilerTest` | 有标记无订单→回补 / 有订单→跳过 / 幂等重复扫描 |

---

## 七、实施步骤（确认设计后按序执行）

1. `SendOutcome` 枚举（model/enums）
2. `KafkaOrderSender`（迁移 RedisAsync 的发送逻辑）
3. `SyncDegradeController`（从 RedisSyncStrategy 抽出窗口/采样/恢复）
4. `RedisSyncStrategy` 改为薄外观（依赖 sender + degrade）
5. `RedisAsyncStrategy` 改为依赖 sender
6. `placeOrder` 改 outcome 判定 + 统一回补
7. `LostOrderReconciler` 新增 + application.yml 配置
8. 编译 + 单测 + 回归
9. 更新学习文档《秒杀链路全解》第九/十章节
