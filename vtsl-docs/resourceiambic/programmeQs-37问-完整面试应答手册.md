# programmeQs 37 问 · 完整面试应答手册（基于当前实现）

> **用途**：对照 37 个面试追问，逐题给出可直接背诵的应答，标注现状（✅ 已实现 / ⚠️ 部分 / ❌ 未做但给方案）与代码支撑。
> **生成时间**：2026-08-14（REDIS_SYNC 重构、Kafka 集群、异步可靠性改造完成后）
> **配套**：`简历v0.3.md`（简历）、《秒杀链路全解-代码级学习文档.md》（链路）

---

## 一、秒杀系统（Q1-Q18）

### Q1 三种扣减策略对用户下单体验的影响（异步模式用户感知）

**现状**：✅ 三策略齐全 + 运行时动态切换。

**应答**：
> "三种策略对用户感知的差异在'何时确认成功'：
> - **REDIS_ASYNC**（默认）：Redis Lua 扣减成功即返回'抢购成功'（~1ms），订单异步落库——用户感知最快，但'成功'是预占语义，订单最终由消费端落库（最终一致）；
> - **REDIS_SYNC**：`send().get(3s)` 等 broker ack 才返回成功——用户感知有延迟，但'成功'= 消息已确认送达（强一致），适合对账/金融类活动；
> - **DB_QUEUE**：DB 乐观锁直扣 + MQ 顺序消费——强一致低并发。
> 异步模式用户看到'抢购成功'但订单还没落库，用前端六态（SELLING→QUEUING→SUCCESS）承接，查询接口对'Redis 已扣、DB 未落'返回'处理中'而非'无记录'。"

**代码**：`strategy/RedisAsyncStrategy.java`、`RedisSyncStrategy.java`、`DBQueueStrategy.java`、`SeckillController.java:54-61`

**追问**：用户抢到但查不到订单？
> "查秒杀结果状态而非订单表——前端 QUEUING 状态 + WebSocket 推送结果；DB 落库后推送'抢购成功'，不会出现'成功但无订单'的矛盾。"

---

### Q2 Sentinel 限流/降级触发时前端展示什么

**现状**：⚠️ 依赖已引入（`sentinel-datasource-nacos`），规则未落地（`application.yml` 中 datasource 段被注释）。

**应答**：
> "Sentinel 规则通过 Nacos 数据源下发（`dataId: vtsl-seckill-rules.json`），秒杀接口配 QPS 限流（单机安全阈值 300 并发 / 1500 QPS，来自压测甜点）。触发时 `@SentinelResource(blockHandler)` 统一返回'系统繁忙，请稍后再试'（503 语义），前端对降级码展示排队提示而不是直接报错。用 Nacos 动态下发是为了**规则可热更新**，大促前调阈值不用重启。"

**代码**：`application.yml:81-91`（注释的 datasource 待启用）

**追问**：为什么不硬编码规则？
> "硬编码改阈值要发版重启，大促前调参不现实；Nacos 下发秒级生效，还能按活动维度差异化。"

---

### Q3 Kafka 消费者宕机/积压对账补偿

**现状**：✅ `LostOrderReconciler` 已实现（本次重构新增）+ `ReconciliationScheduler` 已有。

**应答**：
> "三层兜底：
> 1. **丢单对账** `LostOrderReconciler`（每 10min）：SCAN `ordered:{activityId}:{userId}` 标记，若 DB 无对应订单 → `refund_stock.lua` 幂等回补——兜'Redis 已扣、消息未达/回补失败/崩溃'的库存泄漏洞；
> 2. **取消回补对账** `ReconciliationScheduler`（每 5min）：扫最近 60min 已取消订单，比对 ordered 标记残留 → 幂等回补；
> 3. 消费者宕机靠 Kafka 原生 rebalance + 手动 ACK，恢复后从已提交 offset 继续，配合消费端唯一索引幂等不重不漏。"

**代码**：`scheduler/LostOrderReconciler.java`、`scheduler/ReconciliationScheduler.java`

**追问**：对账会不会误判在途消息？
> "不会——对账对象是'DB 已取消但 ordered 标记未清'的订单（Reconciliation）和'有标记无订单'的丢单（LostOrder），不是库存 vs 订单数比对，天然排除在途；且 refund Lua 幂等（ordered 存在才 INCR），重复扫描安全。"

---

### Q4 Lua 扣减成功后 Kafka 发送失败（本地消息表/事务消息？）

**现状**：✅ 已完整实现——REDIS_SYNC 单次失败 Fail Fast 回补；异步走 pending+回调+补偿+补投。

**应答**：
> "分两种策略：
> - **REDIS_SYNC（强一致）**：单次发送失败返回 TRANSIENT/FATAL → `placeOrder` 统一 `refundStock` 幂等回补 + 503（Fail Fast）。**不做应用层重试**——producer 已有 `retries:3` + `enable-idempotence` 内部重试，应用层重试叠加 3s 超时延迟（长尾来源）；失败回补后用户可再抢；
> - **REDIS_ASYNC（最终一致）**：发送前写 Redis pending 标记 → `send()` 立即返回 → 回调成功删 pending、失败进补偿队列 → `PendingOrderScanner` 30s 补投 + `CompensateQueueConsumer` 5s 消费（>3 次进死信）——四层保障消息不丢；
> - 消费端 `uk_activity_user` 唯一索引 + `DuplicateKeyException` 幂等 ack，at-least-once 不重不漏。
> 为什么不用本地消息表：Redis pending 轻量版已满足'扣减与发送的最终一致'，DB 本地消息表是更强的方案（事务同库），量级上来再升级。"

**代码**：`SeckillService.java:133-160`（outcome 判定+统一回补）、`KafkaOrderSender.java`、`scheduler/PendingOrderScanner.java`、`scheduler/CompensateQueueConsumer.java`

**追问**：Redis 也挂了，pending 写不进去？
> "写 pending 失败降级放行（不阻断下单），可靠性从'补投兜底'退化为'对账兜底'（LostOrderReconciler）；要更强就升级本地消息表。"

---

### Q5 取消订单回补库存原子性/幂等

**现状**：✅ 完整实现。

**应答**：
> "取消订单是'DB 先、Redis 后'：先 `@Version` CAS 更新订单状态（防与超时取消并发冲突，version 冲突抛 OptimisticLockException），再走 `refund_stock.lua` 幂等回补（ordered 存在才 INCR+DEL，重复回补不 double-INCR）。顺序不能反——若先回补 Redis 再写 DB，DB 失败会导致库存泄漏。Redis 回补失败由 `ReconciliationScheduler` 每 5min 对账补偿。"

**代码**：`SeckillService.java:cancelOrder`、`lua/refund_stock.lua`、`TimeoutCancelScheduler.java`

---

### Q6 Semaphore 背压与 HikariCP 动态调整

**现状**：✅ 背压实现；⚠️ 无 Prometheus 指标暴露（无 actuator）。

**应答**：
> "消费端 `Semaphore(30)` 上限对齐 HikariCP 池大小（`maximum-pool-size: 30`），并预留少量 buffer 给对账/管理接口等非消费路径 DB 操作。信号量耗尽 → 阻塞 listener 线程 → poll 暂停 → Broker 感知消费慢 → 降速——从根源杜绝虚拟线程堆积引发的连接耗尽与 heap OOM。压测验证 100% 入库、消息零丢失。若 HikariCP 动态调整，Semaphore 也应同步调整（当前配置化 `seckill.consumer-max-concurrency`）。"

**代码**：`SeckillOrderConsumer.java:29,40-45,51,113`、`application.yml:22-24,133`

**追问**：为什么信号量要小于连接池？
> "信号量持有 = 一个 DB 事务在跑；若信号量 ≥ 连接池，长事务占着连接时信号量还没满但连接已耗尽——实际并发 < 信号量上限。预留 buffer 给非消费路径。"

---

### Q7 SkyWalking 定位后若问题依旧

**现状**：✅ 已对齐——压测环境用 SkyWalking agent（脚本），日常 Brave 桥接。

**应答**：
> "瓶颈定位用 SkyWalking agent 接入（`docs/run-with-skywalking.sh`），定位到 Kafka Producer 同步发送等待 3342ms 为瓶颈，四阶段控制变量法验证根因 = 'Kafka 同步阻塞 + Redis 跨 VM 网络'叠加（P99 264→132→87→92ms）。如果本地化 Redis 后问题依旧，下一步排查：① broker 侧（GC/磁盘/网络）② producer 侧（buffer-memory/batch 参数）③ 消费端 lag。日常开发默认 Brave 桥接（micrometer-tracing-bridge-brave）无需 agent，两种可观测方案按环境切换。"

**代码**：`docs/run-with-skywalking.sh`、`pom.xml:90-98`、`vtsl-docs/performance/03-async-shave.md`

---

### Q8 JDK25 EA 崩溃的细粒度容错（灰度回滚、特性开关）

**现状**：⚠️ 文档级预案（`spring.threads.virtual.enabled` 可配）。

**应答**：
> "JDK25 是较新版本，生产风险用三层控制：① 特性开关 `spring.threads.virtual.enabled` 配置化，可切回平台线程（JDK17/21 兼容，代码不用改，`Thread.startVirtualThread` 换线程池即可）；② 滚动重启脚本（网关摘流量→分批重启→恢复）；③ 优雅停机 `timeout-per-shutdown-phase: 30s` 等在途 VT 完成。这是'降级预案'——不出问题用 VT+ZGC 拿性能，出问题一键回退。"

**代码**：`application.yml:12-14,93-95`、`docs/` 脚本

---

### Q9 Kafka 重复消费业务层幂等

**现状**：✅ 完整实现。

**应答**：
> "三重幂等：① DB 唯一索引 `uk_activity_user`（activity_id, user_id）——重复插入抛 DuplicateKeyException；② 消费端捕获 DuplicateKeyException 直接 ack（不重投）；③ 消息格式校验（userId:activityId:orderNo，split 长度 <3 直接 ack 防脏数据）。配合手动 ACK（落库成功才 ack）+ 内存重试 3 次（TransactionException 不 ack 等重投，超过放弃靠幂等兜底）。"

**代码**：`SeckillOrderConsumer.java:59-111`

---

### Q10 Lua 客户端重试全局幂等（请求 ID 去重）

**现状**：✅ ordered 标记已兜住（用户维度一人一单）。

**应答**：
> "`deduct_stock.lua` 用 `ordered:{activityId}:{userId}` 做用户维度判重（Lua 内 SETEX 原子完成）：同用户重复扣减返回 -1。业务上'一人一单'意味着同一用户第二次合法请求也被拦——这正是防重设计。若要区分'客户端重试'与'新请求'，可加 requestId 维度 `dedup:{requestId}` SETNX（可选增强，当前一人一单语义已够）。"

**代码**：`lua/deduct_stock.lua:12-23`

---

### Q11 ZGC Proactive 配置/内存碎片

**现状**：✅ Dockerfile 已固化参数。

**应答**：
> "JVM 参数已固化 Dockerfile：`-XX:+UseZGC -XX:+ZGenerational`（分代 ZGC）+ `-Xlog:gc*:file=/app/logs/gc.log`（GC 日志落盘取证）+ OOM 堆转储。同口径压测（450 并发/45s/堆峰值 4GB）：G1 GC 总耗时 0.856s（纯 STW），ZGC 仅 0.002s（纯并发，Proactive 触发）。ZGC 代价是读屏障约 3-5% 吞吐损耗 + 内存略高，压测确认可接受（TPS 达标）。"

**代码**：`docker/Dockerfile`、`vtsl-docs/performance/02-g1-vs-zgc.md`

**追问**：3ms 是平均还是 P99？
> "是 GC 总耗时（0.002s），ZGC 设计目标就是停顿不随堆增长；压测口径同构（450 并发/45s/10w 订单）保证可比。"

---

### Q12 虚拟线程 ForkJoinPool 调优/自定义 Executor

**现状**：⚠️ VT 已启用，无自定义业务 Executor。

**应答**：
> "虚拟线程由 `spring.threads.virtual.enabled: true` 开启，秒杀链路消费端用 `Thread.startVirtualThread` 处理（不占平台线程）。VT 调度由 JDK 内置 ForkJoinPool 管理，IO 密集场景（Redis/Kafka/MySQL）优势明显。若需要隔离资源（有状态任务/CPU 密集），可自定义平台线程池供特定任务用——当前秒杀链路全部 IO 密集，VT 直达最优，无需额外池。"

**代码**：`application.yml:12-14`、`SeckillOrderConsumer.java:57`

---

### Q13 P99 数据 QPS 口径/容量规划

**现状**：✅ 压测文档完整。

**应答**：
> "压测口径：300 并发 / 30s / 10w 订单，ZGC + 异步削峰后 TPS 1312 / P99 183ms（优化前 269ms，↓32%）。全链路阶梯加压（10-600 并发）确认 300 并发为吞吐最优平衡点（单机 1500 QPS 安全限流阈值）。容量规划：单机 1500 QPS，10w QPS 目标需约 70 台（含网关/服务分层）；订单量超单表阈值（千万级）再分库分表。"

**代码**：`vtsl-docs/performance/01/02/03`

---

### Q14 新增策略的开闭原则/Nacos 动态下发

**现状**：✅ 动态切换已实现（管理接口 + DB 更新 + Caffeine 刷新）。

**应答**：
> "策略扩展：实现 `SeckillStrategy` 接口 + 工厂 `resolveMode` 加一个 instanceof 分支（开闭原则，不碰现有策略）。运行时切换：`PUT /seckill/activity/{id}/mode` → DB 更新 + `cacheService.refresh` 刷新 Caffeine，placeOrder 立即读到新 mode。**库存模型隔离**：REDIS_ASYNC ↔ REDIS_SYNC 共用 Lua 库存可互切；DB_QUEUE 是另一套（DB 直扣），禁止互切（防止双数据源同时扣减超卖）。Nacos 配置中心下发全局默认模式可作为增强（`@NacosValue` 监听）。"

**代码**：`SeckillService.java:updateMode`、`SeckillController.java:PUT /activity/{id}/mode`、`SeckillStrategyFactory.java`

**追问**：多实例切换一致性？
> "活动发布有 30s~1min 缓冲期，Caffeine TTL 内多实例短暂不一致不产生业务影响（Redis 系互切库存模型相同）；未来如需热切换加 Redis Pub/Sub 广播失效。"

---

### Q15 单体/微服务/多品类扩展/数据隔离

**现状**：✅ 微服务 + hash tag 隔离。

**应答**：
> "秒杀独立微服务部署（8090/Dubbo 20882/Nacos 注册），单库单表。多品类扩展：活动维度天然隔离（`stock:{activityId}` hash tag），不同活动不同 key 互不影响，水平扩展加消费者实例即可（topic 6 partition 支持 6 并行消费）。数据隔离：Redis key 按活动 hash tag 分槽，DB 按 activity_id 查询（唯一索引 uk_activity_user 保证一人一单）。当前量级单表足够，订单超千万再分表。"

---

### Q16 关闭 VT 切 G1 实时生效还是重启

**现状**：⚠️ 配置项切换需重启（滚动重启脚本）。

**应答**：
> "`spring.threads.virtual.enabled` 是启动时配置，切换需重启。用滚动重启（网关摘流量→分批重启→恢复），配合优雅停机等在途 VT 完成。存量请求在切换瞬间由网关摘流量保护，不丢请求。"

---

### Q17 业务层降级（缓存失效穿透 DB？Kafka 不可用切同步？）

**现状**：⚠️ 散落 try-catch；Redis 挂→503；Kafka 挂→Fail Fast。

**应答**：
> "逐级降级：
> - **Redis 挂**：扣减抛异常 → 503（不误导'库存不足'）；活动信息 Caffeine 5s 旧值 + 异步刷新（缓存不空窗）；
> - **Kafka 挂**：REDIS_SYNC 单次失败回补 + 503（Fail Fast）；REDIS_ASYNC pending 标记 → 补偿队列/补投兜底；持续失败 → 窗口降级；
> - **缓存穿透**：Caffeine 精确判存（活动量级小，不用 BF——多实例 BF 不一致误杀 404）。
> 统一降级框架（`@SentinelResource(fallback)`）是后续增强项。"

**代码**：`StockService.java:56-57`、`ActivityCacheService.java:27-31`、`RedisSyncStrategy.java`

---

### Q18 降级触发条件/自动/人工/恢复

**现状**：✅ 自动降级闭环（窗口触发 + 采样恢复）；⚠️ 无 Prometheus 告警规则文件。

**应答**：
> "降级触发：`SyncDegradeController` 滑动窗口（最近 100 次）平均耗时 > 500ms 或超时率 > 5% → 自动切 REDIS_ASYNC；恢复：降级后 5% 请求走同步探针，连续 5 次成功（且 ≥ 10s 防抖）→ 切回 REDIS_SYNC。阈值锚定用户体验 SLA 而非 P99。告警：pending/补偿/死信积压由 `AsyncReliabilityMonitor` 每分钟统计打 ERROR 日志（接日志平台）。"

**代码**：`biz/mq/SyncDegradeController.java`、`scheduler/AsyncReliabilityMonitor.java`

---

## 二、短链平台（Q19-Q37）

### Q19 缓存 miss 直查 MySQL 的 RT/兜底

**现状**：✅ 三级缓存（Caffeine→Redis→MySQL）+ `uk_short_key` 索引；⚠️ 无慢查询告警。

**应答**：
> "读链路三级：L1 Caffeine（3s TTL，拦截热点）→ L2 Redis（24h±10% TTL 随机偏移防雪崩）→ MySQL（`uk_short_key` 唯一索引兜底）。缓存值携带 urlId（`{id}|{url}`），命中直接用缓存 id 记 analytics，热路径零 DB。miss 回源 RT 取决于 DB（索引命中 <5ms），被 `@SentinelResource` 保护，慢查询接日志告警。"

**代码**：`vtsl-shortlink-api/.../service/UrlService.java:130-171`

**追问**：缓存穿透/击穿/雪崩？
> "穿透：不存在短码空值缓存 30s；击穿：L1+L2+DCL（SETNX 锁 + 未抢到自旋重查）；雪崩：TTL 随机抖动 ±10%。"

---

### Q20 软删除后行为/归属校验/防探测

**现状**：✅ 完整实现。

**应答**：
> "软删（deleted_at）后跳转返回'链接已失效'（查询过滤 deleted 标记，不 404 暴露资源存在性）；归属校验失败统一 404（防枚举探测，不泄露'有没有这个短码'）；网关正则拦非法格式（非法直接 400，不进后续链路）。"

**代码**：`UrlService.java:122-128,152-159`、网关 `ShortCodeValidationFilter.java`

---

### Q21 Mongo 取号与 Redis 写入原子性/号码丢失

**现状**：❌ 未改——Mongo insert 成功后才 push Redis，push 失败整批 available 号码静默丢失。

**应答**（诚实 + 方案）：
> "当前实现是 Mongo 记账（available/used + key 唯一索引）→ Redis List 预生成队列，存在 push 失败的号码丢失窗口。**设计上需补回收机制**：入队失败的 key 批量回置 available（或记 dead 表定时重投）；取号 RPOP 与 CAS 标记非原子（崩溃窗口）靠对账扫描'available 但超时未入队'的号码回收。这是 P0 改造项，方案已定（与 Q23/Q28 统一回收服务），当前演示环境单节点 + 低并发未触发。"

---

### Q22 Caffeine L1 与 Redis L2 不一致

**现状**：✅ TTL 兜底 + 双删。

**应答**：
> "L1 3s / L2 24h，更新/删除时 invalidate + delete 双删，TTL 收敛最终一致。3s 窗口内可读到旧值（设计内接受——短链跳转对秒级旧值不敏感）；增强方案是缓存值带版本号/时间戳校验。面试话术：'接受短暂不一致 + TTL 收敛'。"

**代码**：`UrlService.java:39-42`

---

### Q23 短码生成后 MySQL 写入失败回滚/两库协调

**现状**：❌ 未改——api `@Transactional` 回滚，但 KGS key 已 CAS=used 不可逆、不回收。

**应答**（诚实 + 方案）：
> "当前 api 侧事务回滚 ✅，但 KGS 侧 key 已标记 used 不回收（号码浪费）。**设计上需补号码回收机制**：① api 落库失败调 `releaseKey(key)`（CAS used→available 或入回收队列）；② 定时对账扫描 Mongo used 但 MySQL 无对应 t_url 且超时的 key → 回收。与 Q21/Q28 统一为一个回收服务，P0 待落地。"

---

### Q24 队列耗尽 + 补货卡住的备用发号

**现状**：❌ 未改——补货同步（`getKey()` 内发现 <200 现场 `generateBatch(1000)`），Mongo 失败直接抛异常 → 500。

**应答**（诚实 + 方案）：
> "当前补货是同步的（getKey 内触发），Mongo 故障会 500。**设计上需改**：① 独立后台补货线程（`@Scheduled` 检查 queueLen < 阈值即补）+ Redisson 互斥锁防并发补货；② 备用号源：Redis INCR 顺序号降级（与 simple 复用）或雪花 Base58，格式需版本位兼容。P0 待落地。"

---

### Q25 gRPC 超时/重试/熔断/proto 兼容

**现状**：⚠️ 超时已实现（`withDeadlineAfter(2s)`）；❌ 无重试/熔断；proto 无版本化。

**应答**：
> "gRPC 客户端配 2s deadline（`withDeadlineAfter`）。取号是幂等场景（可重试），重试 + 熔断是增强项：连续失败熔断打开 → 走备用号源（Q24）。proto 升级 `package key.v1` 留兼容字段（加字段向后兼容是 proto3 特性）。"

**代码**：`KeyServiceClient.java:26`、`GrpcClientConfig.java:27-30`

---

### Q26 Mongo 主节点宕机/分段配置

**现状**：❌ 单节点 URI；分段硬编码（`KgsConstants.java` BATCH_SIZE=1000/QUEUE_THRESHOLD=200）。

**应答**：
> "当前单节点 Mongo（演示环境）。生产要求：① URI 改 replicaSet（副本集，主节点宕机自动切换）；② 分段参数 yml 化（`kgs.batch-size`/`kgs.queue-threshold`）。段大小权衡：大段省 DB 压力但号码浪费多（一次取 1000 用不完）；小段频繁取号 DB 压力大。"

**代码**：`application.yml:11-13`、`KgsConstants.java:8-9`

---

### Q27 同一长 URL 多次生成/幂等键

**现状**：⚠️ api：同 userId+同 URL 未删 → 409 拒绝；无全局 URL 去重、无幂等键字段。

**应答**：
> "同一用户同一 URL 未删 → 409 拒绝（业务决策：一个用户一个短码）；不同用户同 URL 各生成新码（可接受）。幂等键字段是增强项：请求带 `idempotencyKey`，Redis SETNX 幂等，重复请求返回首次结果（防客户端重试生成多个码）。面试话术：'409 防重复是业务决策，幂等键防客户端重试'。"

---

### Q28 RPOP 取号失败号码浪费/回收/可预测性

**现状**：⚠️ CAS 失败直接丢弃不回队；KGS SecureRandom 随机不可预测 ✅。

**应答**：
> "随机码防预测（SecureRandom，无法枚举撞库）+ Mongo key 唯一索引防重（撞唯一索引整批重试 MAX=3）——双保险不依赖概率。CAS 失败号码丢弃是浪费，回收机制（Q23 统一）待落地。面试话术：'随机码防预测 + 唯一索引防重，双保险不依赖概率'。"

**代码**：`KeyService.java:49-63`、`Base62Util.java:15-29`

---

### Q29 Base62 均匀性/热点打散

**现状**：✅ 均匀（`BigInteger(bitLength,RANDOM).mod(62^len)` 逐位除基）。

**应答**：
> "随机码逐位除基取模，6 位 Base62 空间 62⁶ ≈ 568 亿，分布均匀无顺序热点；热点短码（营销活动）差异化 TTL/预热是增强项。面试话术：'随机码天然均匀，无顺序热点'。"

**代码**：`Base62Util.java:15-29`

---

### Q30 Caffeine 命中率监控/淘汰策略

**现状**：⚠️ 淘汰策略有（maximumSize(100_000) + expireAfterWrite(3s)）；❌ 未开 recordStats()。

**应答**：
> "淘汰策略：maximumSize(100_000) + expireAfterWrite(3s)。命中率指标是增强项：`recordStats()` + actuator 暴露 `cache.gets/hits`——开了才能报命中率数字（面试可讲'命中率 >99% 热路径零 DB'）。"

**代码**：`UrlService.java:39-42`

---

### Q31 网关 Base62 校验性能/DFA

**现状**：✅ 正则实现（Pattern 静态常量微秒级）；⚠️ api 新链 `/api/v1/url/redirect/**` 不经过。

**应答**：
> "网关 `ShortCodeValidationFilter` 纯内存正则（静态 Pattern 编译一次，微秒级），非法短码直接 400 不进后续链路。校验范围扩展到 api 跳转路径是增强项（当前只覆盖旧链）。"

**代码**：`ShortCodeValidationFilter.java:29-65`

---

### Q32 短码长度升级（6→7 位）

**现状**：❌ `KEY_LENGTH=6` 硬编码，无升级灰度；⚠️ 表结构/网关天然容忍变长。

**应答**：
> "6 位容量 568 亿，耗尽前无需升级。升级方案：`kgs.key-length` 参数化 + 版本位/长度位标识，老码共存（查询按长度路由），新码 7 位。当前量级远未触及，P2 项。"

**代码**：`KgsConstants.java:10`

---

### Q33 多租户隔离

**现状**：❌ 无 tenant 字段（api 只有 user_id）。

**应答**：
> "当前按 user_id 隔离（C 端个人场景）。SaaS 化再加 tenant_id：表加字段 + MyBatis/JPA 拦截器自动注入查询条件，业务无感知。面试话术：'个人项目 user 隔离足够，SaaS 化是演进方向'。"

---

### Q34 非 Java 客户端协议兼容

**现状**：⚠️ 标准 proto3 契约存在；❌ 无 reflection/health、无版本号。

**应答**：
> "proto3 契约天然跨语言（Go/Python 可生成 client）。增强项：gRPC reflection + health service（`grpc-services` 一行依赖）+ proto 版本化（Q25 合并）。"

---

### Q35 Redis 不可用时降级直查 MySQL

**现状**：❌ api `getFromCache` 无 try-catch → Redis 挂直接 500。

**应答**（诚实 + 方案）：
> "当前 api 读链路 Redis 挂会 500（真实缺口）。**设计上需补**：① 读链路捕获 `RedisConnectionFailureException` → 直查 DB（降级开关可配）；② 限流器 Redis 挂 → 降级本地限流（Caffeine 计数器）或放行+告警；③ 统计异步落库天然不阻塞主链路。P0 待落地。"

**代码**：`UrlService.java:161-171`、`RedisFixedWindowLimiter.java:30-34`

---

### Q36 Mongo 发号器故障切 UUID/雪花降级

**现状**：❌ 无降级发号；雪花 Base58 与 6 位 Base62 格式不兼容。

**应答**（诚实 + 方案）：
> "当前无备用号源。**设计上需补**：KGS 不可用 → 临时雪花 Base58（加版本前缀标识）或 Redis INCR 短码；格式兼容设计（版本位/长度位）与 Q24/Q32 统一。面试话术：'降级路径要有，格式兼容是前提'。"

---

### Q37 二级缓存全部失效时行为/降级/重建

**现状**：⚠️ api miss → 直查 DB → 回填（隐式重建）✅；❌ api 无 DCL 防击穿（simple 有）。

**应答**：
> "miss 后直查 DB 并回填（隐式重建）。防击穿：api 侧待加单飞（Caffeine `get(key, loader)` 内置单飞，同一 key 只有一个线程回源）——当前 simple 模块有完整 DCL（SETNX 锁 + 自旋重查），api 是增强项。面试话术：'缓存重建瞬间并发回源会放大 DB 压力，用单飞收敛'。"

**代码**：api `UrlService.java:130-150`；simple `ShortLinkService.java:128-181`

---