# 异步削峰优化

> 瓶颈定位与优化前后对比。

---

## 优化前：Kafka 同步发送

### 问题

SkyWalking 链路追踪发现尾部延迟异常，最长 **3342ms**。四阶段控制变量法定位根因为"Kafka 同步阻塞 + Redis 跨 VM 网络"双重叠加。

### 根因定位

| 阶段 | 变更 | P99 | Max | 结论 |
|------|------|:---:|:---:|------|
| 基线 | 同步 Kafka + 远端 Redis | 264ms | 3,342ms | 原始状态 |
| 仅 Redis 本地化 | 同步 Kafka + 本地 Redis | 132ms | 305ms | 网络 IO 为主要瓶颈 |
| 仅 Kafka 异步 | 异步 Kafka + 远端 Redis | 87ms | 334ms | Kafka 同步阻塞为次要瓶颈 |
| 组合优化 | 异步 Kafka + 本地 Redis | — | — | 彻底解耦 |

> Redis 本地化仅 Max RTT 从 3342ms 收敛至 305ms，证实跨 VM 网络延迟是极端尾延迟主因。

### 优化手段

1. **Kafka 异步削峰**：`kafkaTemplate.send(msg)` 替代 `send().get(3s)`（纯异步，不阻塞请求线程）
2. **Redis 本地化**：Redis 从 VM 迁移到应用本地（Max RTT 从 379ms 降至 ≈1ms）

### 效果

| 指标 | 优化前 (G1 ph5) | 优化后 (ZGC 异步) | 提升 |
|------|:--------------:|:----------------:|:----:|
| TPS | 1,191 | 1,513 | +27% |
| P99 | 269ms | **87ms** | ↓68% |
| Max | 385ms | **139ms** | ↓64% |

---

## 优化后：二阶限流 + 消费背压

### Semaphore 背压

Kafka Consumer 端 `Semaphore(30)` 上限对齐 HikariCP 连接池大小：

```
SEM.acquire() → poll 阻塞 → Broker 感知消费慢 → 降速
```

从根源杜绝 VT 堆积引发的连接耗尽与 heap OOM。

### 幂等兜底

| 防线 | 机制 | 说明 |
|------|------|------|
| DB 唯一索引 | `uk_activity_user` | `DuplicateKeyException` → ack |
| 内存重试 | TransactionException 重试 3 次 | 超限后 ack 放弃 |
| Fail Fast | Kafka 不可用 → 回补库存 + 503 | 不丢消息、不超卖 |

### 数据

| 配置 | 并发 | TPS | P99 | Max |
|------|:---:|:---:|:---:|:---:|
| ZGC phase5 | 300 | 1,312 | 183ms | 286ms |
| 异步 300-2 | 300 | 1,513 | 92ms | 139ms |
| 异步 400-2 | 400 | 1,661 | 122ms | 165ms |
| 异步 600 | 600 | 1,594 | 332ms | 601ms |

异步削峰后 TPS 提升 +15.3%，且在高并发下表现稳定。
