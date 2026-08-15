# programmeQs 37 问 × 项目现状 × 改造方案

> **用途**：对照 `programmeQs.md` 的 37 个面试追问，逐项核查 `VTSeckillShortLink` 项目代码现状，给出改造方案与优先级，作为"一个一个改项目"的执行清单。
>
> **现状分级**：✅ 已实现可支撑应答 ｜ ⚠️ 部分实现有缺口 ｜ ❌ 未实现/代码与文档不符
> **优先级**：P0 面试必问且当前答不出（先改）→ P1 高价值易被追问 → P2 加分项/深水区

---

## 一、秒杀系统（Q1-Q18）

### Q1 三种扣减策略对用户下单体验的影响（异步模式用户感知）
- **现状**：✅ 三策略齐全（`SeckillStrategy` + `RedisAsyncStrategy`/`RedisSyncStrategy`/`DBQueueStrategy`），异步模式"扣减成功即返回成功、订单异步落库"
- **代码**：`vtsl-seckill/.../biz/service/strategy/*.java`
- **差距**：用户"抢购成功但订单未落库"的**状态展示与查询路径**未专门设计（前端靠 WS 推送结果，无轮询兜底）
- **改造**：① 下单响应携带 `orderStatus=PENDING` 明确语义；② 查询接口对"Redis 已扣、DB 未落"返回"处理中"而非"无记录"；③ 前端 SELLING→QUEUING→SUCCESS 六态已具备，补充"查询不到订单时的兜底文案"
- **优先级**：P1

### Q2 Sentinel 限流/降级触发时前端展示什么
- **现状**：❌ Sentinel 规则完全未落地（依赖已引入，Nacos datasource 整段注释，无 `FlowRuleManager`/`@SentinelResource`）
- **代码**：`vtsl-seckill/src/main/resources/application.yml:81-91`（注释掉的 datasource）
- **差距**：被问"限流触发用户看到什么"**无实际可演示路径**
- **改造**：① 启用 Sentinel Nacos 数据源（`dataId: vtsl-seckill-rules.json`）；② 配置限流规则（秒杀接口 QPS）；③ 加 `@SentinelResource(blockHandler)` 统一返回"系统繁忙，请稍后再试"；④ 前端对 429/降级码展示排队提示
- **优先级**：**P0**

### Q3 Kafka 消费者宕机/积压对账补偿
- **现状**：⚠️ `ReconciliationScheduler` 只覆盖"取消订单回补"，**无"有扣减无订单"丢单扫描**，无 Lag 监控
- **代码**：`vtsl-seckill/.../scheduler/ReconciliationScheduler.java:62-115`
- **改造**：① 新增 `LostOrderReconciler`：扫描 Redis `ordered:{activityId}:{userId}` 标记但 DB 无对应订单的记录（超时窗口如 10min），补单或回补；② 暴露 Kafka consumer `lag` 指标（actuator + Prometheus）
- **优先级**：**P0**

### Q4 Lua 扣减成功后 Kafka 发送失败（本地消息表/事务消息？）
- **现状**：⚠️ 无本地消息表/事务消息。REDIS_SYNC 有 catch→refund→503；**REDIS_ASYNC 纯异步发送，失败不回补**
- **代码**：`RedisAsyncStrategy.java:32`（无补偿的 send）；`RedisSyncStrategy.java:44-53`；`SeckillService.java:104-115`
- **改造**：① REDIS_ASYNC 发送前先插本地消息表（`t_mq_send_message`），`afterCommit` 后发送，失败由 `@Scheduled` 补投（5min 缓冲）；② 或最少：`send()` 注册 `ListenableFuture` 回调，失败回补 + 记日志（低成本止血）
- **优先级**：**P0**（面试必问"消息丢了怎么办"）

### Q5 取消订单回补库存原子性/幂等
- **现状**：✅ 完整实现：DB 先（`@Version` CAS）→ Redis 后（`refund_stock.lua` EXISTS 幂等）+ `ReconciliationScheduler` 兜底
- **代码**：`refund_stock.lua:6-11`；`SeckillService.java:147-165`；`TimeoutCancelScheduler.java:53-71`
- **差距**：无（已支撑应答）
- **优先级**：无需改

### Q6 Semaphore 背压与 HikariCP 动态调整
- **现状**：✅ 背压已实现（30 对齐 HikariCP 30）；❌ **无监控告警**（未暴露信号量余量指标）
- **代码**：`SeckillOrderConsumer.java:29,40,45,51,113`；`application.yml:22-24,133`
- **改造**：① 自定义 `PrometheusGauge` 暴露 `semaphore_available_permits` 与当前阻塞线程数；② 配置告警规则（permits=0 持续 >5s → warn）
- **优先级**：P1

### Q7 SkyWalking 定位后若问题依旧
- **现状**：❌ 代码只有 Brave/Zipkin 桥接（`micrometer-tracing-bridge-brave`），**无 SkyWalking agent/otel 依赖**；压测文档声称用 SW（`03-async-shave.md`），代码拿不出配置——文档与代码不符
- **代码**：`vtsl-seckill/pom.xml:90-98`；`application.yml:144-152`
- **改造**：二选一：① 换 `micrometer-tracing-bridge-otel` + `opentelemetry-exporter-otlp`（配 SW OTLP receiver），配置对齐文档；② 或改文档为 Brave/Zipkin 表述。**推荐①**（面试可演示链路追踪）
- **优先级**：P1

### Q8 JDK25 EA 崩溃的细粒度容错（灰度回滚、特性开关）
- **现状**：⚠️ 仅文档级预案（`spring.threads.virtual.enabled` 可配但需重启），无灰度/滚动脚本
- **代码**：`application.yml:12-14`
- **改造**：① 补 Dockerfile + 滚动部署脚本（网关摘流量→分批重启）；② 加 `feature flag`（如 `seckill.vt.enabled`）走 Nacos 下发（与 Q14 合并做）
- **优先级**：P2

### Q9 Kafka 重复消费业务层幂等
- **现状**：✅ 唯一索引 `uk_activity_user` + `DuplicateKeyException` 捕获幂等 ack + 内存重试 3 次
- **代码**：`SeckillOrderConsumer.java:62-111`；`docker/sql/init-tables.sql:21-35`
- **差距**：无（可支撑应答；可补"消费前 status 预检"话术）
- **优先级**：无需改

### Q10 Lua 客户端重试全局幂等（请求 ID 去重）
- **现状**：⚠️ `deduct_stock.lua` 用 `ordered:{activityId}:{userId}` 判重（用户维度），**无请求 ID 维度**——同一用户两次合法请求 vs 客户端重试无法区分（当前设计认为"一人一单"，同用户重复扣减会被 ordered 标记拦截，实际已兜住）
- **代码**：`lua/deduct_stock.lua`
- **改造**：① 确认 `ordered` 标记在 Lua 内 SETNX 原子完成（已满足）；② 可在 Lua 增加 `requestId` 参数 + `dedup:{requestId}` SETNX，作为显式幂等（可选增强）
- **优先级**：P2

### Q11 ZGC Proactive 配置/内存碎片
- **现状**：❌ **JVM 参数无落盘文件**（无 Dockerfile、无 `JAVA_OPTS`），压测数据充分但无可执行配置
- **代码**：无；压测文档 `vtsl-docs/performance/02-g1-vs-zgc.md`
- **改造**：① 新增 `docker/Dockerfile` + `docker-compose` 服务定义，JAVA_OPTS 含 `-XX:+UseZGC -XX:+ZGenerational -XX:ZCollectionInterval=...`（Proactive 相关）；② 或至少提供 `run.sh` 脚本固化参数；③ 补 `-Xlog:gc` 落盘配置
- **优先级**：**P0**（简历核心卖点 ZGC 却无配置证据）

### Q12 虚拟线程 ForkJoinPool 调优/自定义 Executor
- **现状**：⚠️ VT 已启用（`spring.threads.virtual.enabled: true`），**无自定义业务 Executor**；秒杀链路手动 `startVirtualThread`
- **代码**：`application.yml:12-14`；`SeckillOrderConsumer.java:57`
- **改造**：① 定义 `ThreadPoolTaskExecutor`（平台线程，core/max/queue 可配）供非 IO 密集/有状态任务使用；② 文档补充"VT 调度瓶颈→自定义 Executor 隔离资源"话术
- **优先级**：P2

### Q13 P99 数据 QPS 口径/容量规划
- **现状**：✅ 压测文档完整（`vtsl-docs/performance/*`，300 并发甜点 TPS 1312/P99 183ms）
- **差距**：无独立"容量规划/扩容建议"文档
- **改造**：补 `vtsl-docs/performance/04-capacity-planning.md`（N 万 QPS 需几台、分库分表触发阈值）
- **优先级**：P2

### Q14 新增策略的开闭原则/Nacos 动态下发
- **现状**：⚠️ 扩展改动点小（实现接口 + `resolveMode` 加 instanceof 分支）✅；**Nacos 配置监听完全无** ❌
- **代码**：`SeckillStrategyFactory.java:16-28`
- **改造**：① 引入 `@NacosValue("${seckill.mode}")` + 配置监听，运行时切换默认策略；② `SeckillStrategyFactory` 改为按配置动态注册（`Map<String,SeckillStrategy>` bean 注入更符合开闭原则）；③ 切换时存量请求兼容（切前打点/双写窗口）
- **优先级**：**P0**

### Q15 单体/微服务/多品类扩展/数据隔离
- **现状**：✅ 微服务独立部署（端口 8090/Dubbo 20882，Nacos 注册），单库单表，Redis hash tag 隔离
- **差距**：无分库分表（可答"当前量级单表足够，触发条件 X 后再分"）
- **改造**：补容量触发阈值话术文档（Q13 一并做）
- **优先级**：P2

### Q16 关闭 VT 切 G1 实时生效还是重启
- **现状**：⚠️ 配置项切换需重启；**无灰度/滚动脚本落地**；切换中正在执行的 VT 任务处理无方案
- **改造**：① 补滚动重启脚本（与 Q8 合并）；② 文档补"切换中存量请求靠网关摘流量 + 优雅停机等待在途 VT 完成"
- **优先级**：P2

### Q17 业务层降级（缓存失效穿透 DB？Kafka 不可用切同步？）
- **现状**：⚠️ 散落 try-catch 降级：Redis 挂→幂等降级放行/扣减 503；Kafka 挂→REDIS_SYNC 回补 503，**REDIS_ASYNC 无降级路径**；缓存穿透靠 Caffeine 5s 旧值+异步刷新
- **代码**：`IdempotencyService.java:31-35`；`StockService.java:56-57`；`ActivityCacheService.java:27-31`
- **改造**：① REDIS_ASYNC 检测 Kafka 不可用（Producer 发送异常计数）→ 自动降级为 REDIS_SYNC 或直接拒绝；② 统一降级框架（`@SentinelResource(fallback)`）替代散落 try-catch（与 Q2 合并）
- **优先级**：P1

### Q18 降级触发条件/自动/人工/恢复
- **现状**：❌ 无 Prometheus 告警规则、无降级开关接口、无配置中心监听——降级触发只能口述
- **改造**：① Prometheus 告警规则文件（`alerts.yml`：P99 超阈值/错误率/GC 异常）；② 降级开关走 Nacos（与 Q2/Q14 合并）；③ 恢复机制（半开/手动确认）
- **优先级**：P1

---

## 二、短链平台（Q19-Q37）

### Q19 缓存 miss 直查 MySQL 的 RT/兜底
- **现状**：✅ DB 兜底已实现（Caffeine→Redis→MySQL 三级读 + `uk_short_key` 索引）；❌ **慢查询保护无**（无超时告警/降级）
- **代码**：`vtsl-shortlink-api/.../service/UrlService.java:130-150`
- **改造**：① 读链路加 `@SentinelResource` 或超时熔断；② 慢查询日志 + 告警（与秒杀 Q2 监控体系合并）
- **优先级**：P2

### Q20 软删除后行为/归属校验/防探测
- **现状**：✅ 软删后 404、归属校验失败统一 404（防探测）、网关正则拦非法格式
- **代码**：`UrlService.java:122-128,152-159`；网关 `ShortCodeValidationFilter.java`
- **差距**：⚠️ simple 旧版软删不 evict 缓存（TTL 内仍可跳转）——但 simple 是遗留对照实现，可不改
- **改造**：无（新链已达标）；面试话术补"404 隐藏存在性"
- **优先级**：无需改

### Q21 Mongo 取号与 Redis 写入原子性/号码丢失
- **现状**：⚠️ 无跨库事务：Mongo insert 成功后才 push Redis，**push 失败整批 available 号码静默丢失**；RPOP 与 CAS 非原子（崩溃窗口）
- **代码**：`KeyGenerator.java:49-53`；`KeyService.java:43-63`
- **改造**：① 入队失败补偿：push 失败的 key 批量回收（回置 available）或记 `dead` 表由定时任务重投；② 取号改为"Mongo CAS 先标记"或引入对账任务扫描"available 但超时未入队"的号码（与 Q23/Q10 共用回收机制）
- **优先级**：**P0**

### Q22 Caffeine L1 与 Redis L2 不一致
- **现状**：⚠️ TTL 兜底（L1 3s / L2 24h）+ 双删（更新/删除时 invalidate + delete）已实现，最终收敛
- **差距**：3s 窗口内可读到旧值（设计内接受）；无版本号比对
- **改造**：可选：缓存值携带版本号/时间戳，读取时校验（低成本增强）；面试话术"接受短暂不一致 + TTL 收敛"
- **优先级**：P2

### Q23 短码生成后 MySQL 写入失败回滚/两库协调
- **现状**：⚠️ api `create()` `@Transactional` 事务回滚 ✅，但 KGS 侧 key 已 CAS=used **不可逆、不回收**；无补偿机制
- **代码**：`UrlService.java:54-85`；`KeyService.java:49-63`
- **改造**：**号码回收机制**（核心缺口）：① api 落库失败时调 KGS `releaseKey(key)`（CAS used→available 或入回收队列）；② 定时对账：扫描 Mongo used 但 MySQL 无对应 t_url 且超时的 key → 回收；③ 与 Q21/Q10 统一为一个回收服务
- **优先级**：**P0**

### Q24 队列耗尽 + 补货卡住的备用发号
- **现状**：❌ 无备用号源。补货是同步的：`getKey()` 内发现 <200 就现场 `generateBatch(1000)`，Mongo 失败直接抛异常 → 500
- **代码**：`KeyService.java:30-65`；`KeyGenerator.java:38-59`
- **改造**：① 独立后台补货线程（`@Scheduled` 检查 queueLen < 阈值即补），加 Redisson 互斥锁防并发补货；② 备用号源：Redis INCR 顺序号降级（与 simple 复用）或 Snowflake Base58，**格式需兼容**（6 位 Base62 vs 雪花长码——需设计版本位）
- **优先级**：**P0**

### Q25 gRPC 超时/重试/熔断/proto 兼容
- **现状**：⚠️ 超时已实现（`withDeadlineAfter(2s)`）；❌ 无重试、无熔断；❌ proto 无版本化（`package key` 无 v1）
- **代码**：`KeyServiceClient.java:26`；`GrpcClientConfig.java:27-30`；`key.proto`
- **改造**：① gRPC 客户端加重试（幂等取号场景可重试，注意非幂等场景）；② 加 resilience4j CircuitBreaker 或手动熔断（连续失败打开 → 走 Q24 备用号源）；③ proto 升级 `package key.v1`，留兼容字段
- **优先级**：**P0**（gRPC 是短链亮点，重试熔断是必问）

### Q26 Mongo 主节点宕机/分段配置
- **现状**：❌ 单节点 URI（无 replicaSet）；❌ 分段硬编码 `BATCH_SIZE=1000/QUEUE_THRESHOLD=200`（`KgsConstants.java`）
- **代码**：`application.yml:11-13`；`KgsConstants.java:8-9`
- **改造**：① yml 化分段参数（`kgs.batch-size`/`kgs.queue-threshold`）；② Mongo URI 改 replicaSet（或文档说明单节点为演示环境 + 生产要求）；③ 段大小权衡话术（大段省 DB 压力但号码浪费多）
- **优先级**：P1

### Q27 同一长 URL 多次生成/幂等键
- **现状**：⚠️ api：同一 userId + 同一 URL 未删 → 409 拒绝（不返回旧码）；**无全局 URL 去重、无幂等键字段**；不同用户同 URL 各生成新码（可接受）
- **代码**：`UrlService.java:56-58`；`CreateUrlRequest.java`
- **改造**：① 请求加 `idempotencyKey` 字段（可选）：Redis SETNX 幂等，重复请求返回首次结果；② 面试话术"409 防重复是业务决策，幂等键防客户端重试"
- **优先级**：P2

### Q28 RPOP 取号失败号码浪费/回收/可预测性
- **现状**：⚠️ CAS 失败**直接丢弃**（不回队不回收）；KGS SecureRandom 随机 → 不可预测 ✅；simple Redis INCR 顺序 → 可预测（遗留对照）
- **代码**：`KeyService.java:49-63`；`Base62Util.java`；simple `IdGenerator.java`
- **改造**：与 Q23 同一**回收机制**；面试话术"随机码防预测 + 唯一索引防重，双保险不依赖概率"
- **优先级**：**P0**

### Q29 Base62 均匀性/热点打散
- **现状**：✅ 均匀（`BigInteger(bitLength,RANDOM).mod(62^len)` 逐位除基）；❌ 热点打散无
- **代码**：`Base62Util.java:15-29`
- **改造**：可选：热点短码（活动）差异化 TTL/预加载；面试话术"随机码天然均匀，无顺序热点"
- **优先级**：P2

### Q30 Caffeine 命中率监控/淘汰策略
- **现状**：⚠️ 淘汰策略有（`maximumSize(100_000)` + `expireAfterWrite(3s)`）；❌ **未开 `recordStats()`**，无命中率数据
- **代码**：`UrlService.java:39-42`
- **改造**：① `recordStats()` + actuator 暴露 `cache.gets/hits`；② 面试可报命中率数字
- **优先级**：P1

### Q31 网关 Base62 校验性能/DFA
- **现状**：✅ 正则已实现（`Pattern` 静态常量，微秒级）；⚠️ **api 新链 `/api/v1/url/redirect/**` 不经过该校验**
- **代码**：`ShortCodeValidationFilter.java:29-65`
- **改造**：① 校验范围扩展到 api 跳转路径；② 可选：手写 DFA 或长度短路（收益低，先不加）
- **优先级**：P1

### Q32 短码长度升级（6→7 位）
- **现状**：❌ `KEY_LENGTH=6` 硬编码，无升级灰度；⚠️ 表结构/网关天然容忍变长
- **代码**：`KgsConstants.java:10`；`schema.sql:4`；`ShortCodeValidationFilter.java:37-38`
- **改造**：① 常量参数化（`kgs.key-length`）；② 升级策略文档：版本位/长度位标识、老码共存（查询按长度路由）、预留扩容计划
- **优先级**：P2

### Q33 多租户隔离
- **现状**：❌ 无 tenant 字段/分库（api 只有 user_id）
- **代码**：`schema.sql:6`
- **改造**：① 表加 `tenant_id` + 查询条件注入（MyBatis/JPA 拦截器）；② 或文档说明"当前按 user_id 隔离，SaaS 化再加 tenant"（面试话术优先）
- **优先级**：P2

### Q34 非 Java 客户端协议兼容
- **现状**：⚠️ 标准 proto3 契约存在；❌ 无 reflection/health、无版本号、无跨语言 SDK
- **改造**：① 启用 gRPC reflection + health service（`grpc-services` 依赖一行）；② proto 版本化（与 Q25 合并）
- **优先级**：P2

### Q35 Redis 不可用时降级直查 MySQL
- **现状**：❌ api `getFromCache` 无 try-catch → Redis 挂直接 500；限流器也依赖 Redis，挂时限流报错；simple 读 L2 有容错（返回 null）但 DCL 无保护
- **代码**：api `UrlService.java:161-171`；`RedisFixedWindowLimiter.java:30-34`；simple `ShortLinkCache.java:52-65`
- **改造**：① api 读链路捕获 `RedisConnectionFailureException` → 直查 DB（降级开关可配）；② 限流器 Redis 挂 → 降级为本地限流（Guava/Caffeine 计数器）或放行+告警；③ 统计异步落库天然不阻塞主链路
- **优先级**：**P0**

### Q36 Mongo 发号器故障切 UUID/雪花降级
- **现状**：❌ 无降级发号；`SnowflakeIdGenerator` 存在但短链模块未引用；雪花 Base58 与 6 位 Base62 **格式不兼容**
- **改造**：① 降级路径：KGS 不可用 → 临时雪花 Base58 生成（加前缀标识版本）或 Redis INCR 短码；② 格式兼容设计（版本位/长度位）与 Q24/Q32 统一设计
- **优先级**：P1

### Q37 二级缓存全部失效时行为/降级/重建
- **现状**：⚠️ api miss → 直查 DB → 回填（隐式重建）✅；❌ **api 无 DCL 防击穿**（缓存重建瞬间并发回源放大 DB 压力）；simple 有完整 DCL 但依赖 Redis 锁
- **代码**：api `UrlService.java:130-150`；simple `ShortLinkService.java:128-181`
- **改造**：① api 加 DCL/单飞（`ConcurrentHashMap` + `CompletableFuture` 或 Caffeine `get(key, loader)` 内置单飞）；② 降级开关：缓存全失效时允许直查 DB（加限流保护）
- **优先级**：**P0**（防击穿是缓存必考）

---

## 三、改造路线图（按优先级分批执行）

### 🔴 第一批 P0（8 项，面试必问且当前答不出）

| # | 改造项 | 涉及模块 | 关联问题 |
|---|---|---|---|
| 1 | Sentinel 限流/降级落地（规则+数据源+blockHandler+前端提示） | vtsl-seckill/gateway | Q2, Q18 |
| 2 | REDIS_ASYNC 消息丢兜底（本地消息表或发送回调补偿） | vtsl-seckill | Q4 |
| 3 | 对账补"有扣减无订单"丢单扫描 | vtsl-seckill | Q3 |
| 4 | JVM 参数落盘（Dockerfile/JAVA_OPTS/ZGC 配置） | 部署 | Q11 |
| 5 | Nacos 动态下发策略模式 + 工厂开闭原则改造 | vtsl-seckill | Q14, Q8, Q16, Q18 |
| 6 | 短链号码回收/对账机制（Q21/Q23/Q28 统一） | vtsl-shortlink | Q21, Q23, Q28 |
| 7 | 短链备用发号 + 补货线程化 + 互斥锁（Q24/Q36 统一） | vtsl-shortlink | Q24, Q36 |
| 8 | api 侧 Redis 故障降级 + 限流降级 + DCL 防击穿 | vtsl-shortlink | Q35, Q37 |

### 🟡 第二批 P1（6 项，高价值易被追问）

| # | 改造项 | 涉及模块 | 关联问题 |
|---|---|---|---|
| 9 | gRPC 重试 + 熔断 + proto 版本化 | vtsl-shortlink | Q25, Q34 |
| 10 | Semaphore 余量指标 + Prometheus 告警规则 | vtsl-seckill | Q6, Q18 |
| 11 | SkyWalking 集成（otel→SW）或文档对齐 | vtsl-seckill | Q7 |
| 12 | 缓存命中率指标（recordStats + actuator） | vtsl-shortlink | Q30 |
| 13 | 网关校验扩展到 api 跳转路径 | vtsl-gateway | Q31 |
| 14 | Mongo 高可用 + 分段参数 yml 化 | vtsl-shortlink | Q26 |

### 🟢 第三批 P2（加分项/深水区，9 项）

| # | 改造项 | 关联问题 |
|---|---|---|
| 15 | 下单响应携带 PENDING + 查询兜底文案 | Q1 |
| 16 | 容量规划文档（Q13/Q15 合并） | Q13, Q15 |
| 17 | 自定义业务 Executor + VT 调度话术 | Q12 |
| 18 | 滚动重启脚本 + 特性开关 | Q8, Q16 |
| 19 | Lua 请求 ID 幂等（可选增强） | Q10 |
| 20 | 缓存版本号比对（可选） | Q22 |
| 21 | 幂等键字段（可选） | Q27 |
| 22 | 短码长度升级参数化 + 文档 | Q32 |
| 23 | 多租户话术/字段（先话术后代码） | Q33 |
| 24 | gRPC reflection/health | Q34 |
| 25 | 热点短码打散（可选） | Q29 |

---

## 四、执行建议

1. **从第一批 P0 开始**：每项改动独立 commit，改完跑 `mvn -pl <module> -DskipTests compile` 验证可编译；
2. **每项改造同时更新面试话术**：在 `vtsl-docs/resourceiambic/简历设计点-面试追问问题清单.md` 对应问题下标注"已实现"；
3. **Q11 的 Dockerfile 与 Q5/Q9 的既有实现优先演示**：ZGC 配置、回补幂等、唯一索引幂等是现有最强证据，先固化；
4. **文档与代码一致性**：Q7（SkyWalking）必须先对齐，否则面试官看仓库会发现文档与代码不符，杀伤力最大。
