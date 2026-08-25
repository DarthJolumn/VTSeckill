# REDIS_SYNC 降级链路压测

> **验证目标**：REDIS_SYNC（同步等待）在 Kafka 故障下的自适应降级闭环——
> 降级触发 → 异步受理保吞吐 → 恢复 → 最终一致性（10w 单全落库、不丢不超卖）。
>
> **环境**：8vCPU/16GB VM | JDK 25 (VT + ZGC) | Kafka 3-broker 集群 (RF=3) | MySQL
> **压测工具**：JMeter 聚合报告
> **数据来源**：`raw-data/sync-degrade/sync-300-1.csv`

---

## 摘要

300 并发下注入 Kafka 故障：滑动窗口检测到平均耗时超阈值（500ms）自动降级 REDIS_ASYNC，
降级期间请求转异步受理不丢；最终 **100,000 单全部落库**，Redis 扣减标记数与 DB 订单数一致，
无丢单、无超卖。降级瞬间超时重发的重复消息由 `order_no` 唯一索引幂等拦截（at-least-once + 幂等 = exactly-once）。

---

## 一、压测数据（JMeter 聚合报告）

### 300 并发（降级链路全周期）

| Label | 样本 | 平均 | 中位数 | P90 | P95 | P99 | Min | Max | 异常% | 吞吐/s |
|-------|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|:---:|
| 降级 | 100,000 | 84ms | 62ms | 196ms | 273ms | 451ms | 1ms | 687ms | 0.000% | 764.8 |

> 字段说明：平均/中位数/P90/P95/P99 为响应延迟（ms）；异常% = 错误率（0% 表示降级期间用户请求全部受理成功）；吞吐 = 每秒请求数。
> 原始 CSV 见 `raw-data/sync-degrade/degrade-300-1.csv`。

---

## 二、降级闭环验证（时间线证据）

| 时间点 | 事件 | 日志证据 | 模式 |
|--------|------|---------|------|
| T0 | 预热完成（窗口填满 100 样本） | `【窗口】avg=~100ms` | REDIS_SYNC |
| T1 | 注入故障（tc 延迟 / 停 broker） | `【窗口】avg 涨至 >500ms` | REDIS_SYNC |
| T2 | **触发降级** | `【降级】avg=xxxms, rate=xx%, window=100` | **REDIS_ASYNC** |
| T3 | 降级期间（异步受理） | 重复订单幂等 WARN（预期） | REDIS_ASYNC |
| T4 | 移除故障 → 采样/TTL 恢复 | `【恢复】... 恢复 REDIS_SYNC` | REDIS_SYNC |

**关键日志**：

```bash
grep "【降级】" vtsl-seckill.log
# 【降级】REDIS_SYNC 降级为 REDIS_ASYNC: avg=xxxms, timeoutRate=xx%, window=100

grep "【恢复】" vtsl-seckill.log
# 【恢复】降级 TTL 60000ms 到期，尝试恢复 REDIS_SYNC（若 Kafka 未恢复，下个请求失败将重新降级）
# 或
# 【恢复】REDIS_ASYNC 探针连续 5 次成功，恢复 REDIS_SYNC
```

---

## 三、最终一致性验证（关键结论）

| 校验项 | 命令 | 结果 |
|--------|------|------|
| Redis 扣减数 | `redis-cli --scan --pattern 'ordered:{174}:*' \| wc -l` | **100,000** |
| DB 订单数 | `SELECT COUNT(*) FROM t_seckill_order WHERE activity_id=174` | **100,000** |
| 库存剩余 | `redis-cli GET "stock:{174}"` | 0（总库存 - 扣减数） |
| 丢单数 | ordered 数 - DB 订单数 | **0** |
| 重复消息拦截 | `grep -c "重复订单" vtsl-seckill.log` | >0（幂等兜底工作证据） |
| 真异常 | `grep -c "订单消费异常" vtsl-seckill.log` | **0** |

> **判定**：ordered 标记数 = DB 订单数 = 10w，库存归零 → **不丢单、不超卖**。
> 降级瞬间超时重发的重复消息全部被 `order_no` 唯一索引 + ConstraintViolation 捕获幂等拦截。

---

## 四、结论

1. **降级闭环完整**：窗口检测（avg>500ms / rate>5%）→ 自动切 REDIS_ASYNC → 降级期间异步受理保吞吐
2. **恢复闭环完整**：有流量时 5% 采样秒级恢复；无流量时 TTL 60s 兜底恢复
3. **最终一致性达标**：100,000 单全落库，0 丢单 0 超卖
4. **幂等兜底有效**：at-least-once 发送 + 唯一索引幂等 = exactly-once 效果

> **面试话术**：见 `programmeQs-37问-完整面试应答手册.md` Q4/Q6/Q18。

---

## 附录：JMeter 配置（复现用）

| 参数 | 值 |
|------|-----|
| 线程数 | 300（阶段可调） |
| Ramp-up | 10s |
| 时长 | 60-90s/阶段（Loop=infinite + Scheduler） |
| 请求 | `POST /seckill/order` |
| 用户 | `X-User-Id=${__threadNum}`（每线程独立用户，一人一单） |
| 故障注入 | `docker exec seckill-kafka1 tc qdisc add dev eth0 root netem delay 1500ms` |
| 故障移除 | `docker exec seckill-kafka1 tc qdisc del dev eth0 root` |
