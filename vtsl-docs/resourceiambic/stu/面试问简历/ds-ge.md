这份简历技术栈新（JDK25/虚拟线程/分代ZGC）、项目经验扎实（秒杀+短链），面试官大概率会**深挖底层原理**、**挑战性能数据真实性**、**追问架构选型权衡**。下面我把面试**所有可能问的问题**按维度全列出来，分“通用必问”“技能深挖”“项目致命追问”和“架构设计”四大部分。

---

### 第一部分：通用基础与自我介绍破冰（必问）

1. 你才21岁，大四，如何证明你比工作2-3年的工程师更懂JDK25新特性？（学习路径是什么？）
2. 简历提到“善于使用AI工具辅助编码”，在秒杀项目中，AI具体帮你解决了哪个你手动写不出来的难点？
3. 职业规划是什么？秋招意向部门偏向电商中台还是基础架构？
4. 你觉得你简历里最大的技术亮点是什么？用一句话说服我。

---

### 第二部分：专业技能深度轰炸（按简历顺序）

#### Java 基础与集合框架
5. **ArrayList**：`ArrayList` 扩容机制（详细到 `grow()` 里的位运算）；`subList()` 返回的列表有什么坑？
6. **HashMap**：JDK 1.8 头插改尾插解决了什么问题？红黑树为什么阈值是8，退化为链表为什么是6？
7. **反射**：反射获取 `private` 方法时，`setAccessible(true)` 底层关了谁的检查（`ReflectPermission`）？在模块化（JPMS）下还管用吗？
8. **动态代理**：JDK 动态代理为什么必须基于接口？CGLIB 如果代理的类是 `final` 怎么办？Spring AOP 默认选哪个，为什么？
9. **注解**：`@Retention(SOURCE)` 的注解在编译期通过什么处理器处理？（APT）运行时怎么拿到泛型上的注解？（`AnnotatedType`）

#### 并发编程（重中之重）
10. **AQS**：手撕（口头描述）AQS 的 `acquire()` 流程，`tryAcquire` 失败后，节点是如何加入同步队列并阻塞的？
11. **synchronized**：锁升级过程中，**偏向锁**撤销为什么一定要等到 SafePoint？轻量级锁的 `Lock Record` 存在哪里（栈帧）？
12. **ReentrantLock**：`new ReentrantLock(true)` 公平锁的 `hasQueuedPredecessors()` 判断逻辑有什么缺陷（惊群？实际是避免）？
13. **Semaphore**：如何用 `Semaphore` 实现限流（漏桶）？`tryAcquire` 的超时时间怎么设才合理？
14. **ThreadLocal**：内存泄漏的**完整链条**（Thread -> ThreadLocalMap -> Entry(key为弱引用) -> value强引用）。为什么设计者不把 `value` 也设计成弱引用？
15. **ScopedValue（JDK25）**：和 `ThreadLocal` 的核心区别是什么？它是如何解决“父子线程传递”和“结构化并发”下的数据隔离的？
16. **虚拟线程（核心考点）**：
    - 虚拟线程的 **挂载（Mount）** 和 **卸载（Unmount）** 具体发生在哪个时机（阻塞 I/O 时）？
    - **Pinning** 是什么？为什么 `synchronized` 会导致 Pinning，而 `ReentrantLock` 不会？（JDK 后续是否优化了？）
    - 你的秒杀项目里，虚拟线程替换平台线程，是把 `@Async` 线程池换成了 `Executors.newVirtualThreadPerTaskExecutor()` 吗？Tomcat 线程模型怎么配合？

#### JVM 深度（挑战数据）
17. **G1**：`RSet` 和 `CSet` 分别存什么？`CardTable` 怎么配合写屏障维护跨代引用？
18. **ZGC（分代）**：
    - ZGC 的**染色指针（Colored Pointer）** 用了 64 位中的哪几位？怎么做到 `0` 拷贝重定位（转发表）？
    - **分代 ZGC** 相比原本的 ZGC，为什么能大幅降低 STW？（引入了年轻代屏障，但依然没有分代 GC 的某些弊端？）
    - 你的测试中 G1 STW 856ms，ZGC 降到 3ms。请告诉我你用的 **GC 日志参数**和 **SafePoint 时间统计命令**（`-Xlog:gc*` 具体怎么配的）？
19. **类加载**：打破双亲委派的典型场景（JDBC 驱动、Tomcat 热部署）。如果自己写一个 `java.lang.String` 类，类加载器会加载吗？（安全机制阻止）

#### 数据库 MySQL & PostgreSQL（对比拷问）
20. **MySQL InnoDB**：
    - `Buffer Pool` 的 **LRU 链表**为什么要分 `Young` 和 `Old`（防止预读失效）？
    - `Redo Log` 的两阶段提交（`prepare` 与 `commit`）怎么保证 Crash Safe？如果 `Binlog` 写成功了，`Redo Log` 没写成功，主从会不一致吗？
21. **MVCC**：`Read View` 里的 `m_low_limit_id`（活跃事务最小ID）和 `m_up_limit_id`（最大ID）在 RC 和 RR 级别下，生成时机有什么区别？
22. **PostgreSQL（高难度）**：
    - PG 的堆表（Heap Table）中，**TID（Tuple ID）** 会变吗（Vacuum 后）？回表（Index-only scan 失效）是因为什么？
    - PG 的 MVCC 依赖 `xmin/xmax`，它的**垃圾回收（VACUUM）**和 MySQL 的 `Purge` 线程有什么本质区别（数据可见性判断逻辑完全不同）？

#### NoSQL（Redis & MongoDB）
23. **Redis 数据结构底层**：`quicklist` 和 `listpack` 为什么取代了 `ziplist`？（解决连锁更新）。
24. **Redis 持久化**：RDB 做 `bgsave` 时，如果父进程修改了页，OS 的 **COW（写时复制）** 具体是怎么保护数据一致性（子进程读到的快照）的？
25. **缓存三大坑**：
    - **击穿**：不用互斥锁，还有没有别的方案（逻辑过期 + 异步重建）？具体代码怎么防止死循环？
    - **雪崩**：加随机过期时间，如果 Redis 集群本身挂了怎么办（熔断降级）？
26. **MongoDB**：你说了解原子更新，`findAndModify` 在分片集群（Sharded Cluster）下，能保证全局原子性吗？（不能，需要事务）。

#### Spring 生态 & 微服务
27. **Bean 生命周期**：`@PostConstruct`、`InitializingBean`、`init-method` 的执行顺序是什么？
28. **自动配置原理**：`@SpringBootApplication` 里的 `@EnableAutoConfiguration` 是怎么通过 `spring.factories`（或 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`）加载的？条件注解 `@ConditionalOnMissingBean` 如果遇到代理类怎么判断？
29. **Spring Cloud**：网关（Gateway）的路由断言（Predicate）和过滤器（Filter）的执行链顺序（Global 与 Route 级别）。限流熔断用的是 Resilience4j 还是 Sentinel？为什么选这个？

#### 中间件（Kafka & 工具）
30. **Kafka ACK 机制**：`acks=all` 配合 `min.insync.replicas=2`，如果 ISR 中只有一个副本了，生产请求会怎样（抛异常）。
31. **消费者组**：再均衡（Rebalance）时，`STOP_THE_WORLD` 的机制对业务有什么影响？怎么避免（使用 Cooperative 协议）？
32. **SkyWalking**：你用过它的告警功能吗？在秒杀项目中，你具体用哪个端点（Endpoint）来定位 Kafka 阻塞的？（比如追踪 Span 中的 `send.get()` 耗时）。

---

### 第三部分：秒杀项目（顶级致命追问，必须能写能画）

#### 架构与策略引擎
33. **为什么设计三种策略**？为什么不直接全部 `REDIS_ASYNC` 追求吞吐？最终一致性在金融/电商场景下，用户退款/对账怎么容忍？
34. **策略工厂路由**：`SeckillMode` 是前端传参还是后端动态计算的？如果攻击者恶意传参强制走 `DB_QUEUE` 打垮 DB，怎么防护？
35. **REDIS_SYNC 的滑动窗口自适应**：
    - 环形数组怎么统计“近 100 次”的耗时？数组下标旋转时，怎么保证读写线程安全（`AtomicLong` + `volatile` 还是锁）？
    - 自动切换是**永久切换**还是**临时降级**？如果 5 分钟后 Kafka 恢复，会**自动升回** REDIS_SYNC 吗？机制是什么？
    - 压测 1000 QPS 自动触发切换，你的**压测脚本**（JMeter）是怎么模拟阶梯式递增的？

#### 数据一致性与回补（最难缠）
36. **Redis Lua 扣减**：扣减成功但 Kafka 发送失败（网络闪断），此时 Redis 库存少了，但订单没落库，回补机制怎么捕获这个异常？是 `@Transactional` 回滚 Redis？（Redis 不支持事务回滚）—— **必须答：发送失败时立即调用回补 Lua 脚本，结合唯一键防重。**
37. **幂等性**：你说“唯一索引幂等”，索引建在哪个字段（`order_no`）？如果同一个 `order_no` 重试，`insert` 报 `DuplicateKey` 后，怎么让业务线程知道这笔订单其实已经成功了（`select` 查状态返回）？
38. **手动 ACK + Semaphore 背压**：
    - 为什么 `Semaphore` 的大小要对齐 `HikariCP` 池大小？（防止消息堆积导致连接池耗尽）。
    - 如果 `Semaphore` 获取不到许可，Kafka 消费者是阻塞等待还是提交 Offset 跳过？（必须答：阻塞等待，否则消息丢失）。
39. **取消/超时回补库存**：Redis 回补库存的 Lua 脚本幂等怎么保证？（`hsetnx` 记录回补流水号，如果已存在则返回0）。

#### 性能优化与数据真实性（面试官必怼）
40. **ZGC 性能数据**：
    - “STW 从 856ms 降至 3ms”，请问这 **856ms** 是 **Mixed GC** 的停顿还是 **Young GC** 的停顿？G1 的 `-XX:MaxGCPauseMillis` 你设了多少？
    - 你的测试环境是 JDK25 + **OpenJDK** 还是 **GraalVM**？分代 ZGC 在 JDK25 里默认开启了吗（需要 `-XX:+ZGenerational`）？
41. **虚拟线程数据**：
    - “600 并发 TPS 提升 7.6%”，你的压测机器 CPU 核数是多少？虚拟线程主要解决的是**高吞吐**还是**低延迟**？为什么 TPS 只提升了 7.6% 而不是更高（说明你的应用可能偏 CPU 密集，或者锁竞争没消除）。
42. **瓶颈定位**：
    - 你说 `Kafka send.get()` 是根因。改成 `fire-and-forget + pending 标记` 后，如果 Broker 返回失败（`RecordException`），你的**定时补投**怎么区分是“没发”还是“发了但 Broker 超时未响应”？（需要业务版本号 + 状态机）。

---

### 第四部分：短链项目（设计权衡与边界条件）

43. **发号器 KGS**：
    - `SecureRandom` 生成 6 位 Base62，碰撞概率怎么评估（568亿容量，但随机可能重复）。**如果碰撞了**，你的处理逻辑是重试还是报错？重试次数上限是多少？
    - “MongoDB 账本落账后批量写入 Redis List”，如果 Redis 在 `RPUSH` 半路挂了，MongoDB 已落账但 Redis 没存进去，导致短码遗失浪费怎么办（启动时校验差额）？
44. **CAS 双保险**：`MongoDB` 的 CAS 条件更新（`findAndModify` 查 `status=0` 改 `status=1`）。如果高并发下大量请求同时 `RPOP` 到同一个短码，MongoDB 的文档锁冲突严重吗？（比较耗性能，如何优化？批量预取 + 本地缓存？）
45. **二级缓存一致性**：
    - Caffeine L1 过期 3s，Redis L2 过期 24h。用户修改了短链目标 URL，**3 秒内**用户访问会拿到旧链接，这种“容忍 3 秒不一致”在你们的业务场景里允许吗？
    - 主动失效策略：修改时删 Redis，但 Caffeine 本地缓存怎么删（引入消息广播，如 Redis Pub/Sub 通知所有服务节点删除本地缓存）？
46. **网关校验**：正则校验只拦截非 Base62 字符，如果短码在 Redis/DB 根本不存在，网关层怎么快速返回 404 不穿透业务？（可以加布隆过滤器，为什么没加？）
47. **存储分离**：MongoDB 存唯一性，MySQL 存业务属性。如果一个短码在 MongoDB 状态是 `USED`，但 MySQL 插入业务记录失败了，怎么保证两边的最终一致性？（本地消息表 + 补偿任务）。

---

### 第五部分：系统设计场景题（开放式）

48. **假如秒杀流量再翻 10 倍（1w QPS）**，现在的 Redis 单节点扛不住，你怎么改造？（Redis Cluster + 分片库存，但要注意超卖：分片间的库存同步问题，用 Lua 还是用 TCC？）
49. **ZGC 在超大堆（100GB）下表现如何**？如果你的秒杀堆内存扩到 64GB，ZGC 的并发标记时间会线性增加吗？（不会，但负载增加）。
50. **短链的防攻击**：如果有人写脚本枚举你的 6 位短码（568亿次请求），怎么防御？（网关 IP 限流 + 验证码 + 短码加盐混淆；或者引入业务 ID 映射，让短码不可枚举，但你的设计是随机码，有利有弊，面试官会问为什么选随机不选哈希）。

---

### 第六部分：常规软技能 & 最后收尾

51. 如果让你把秒杀项目**推到生产环境**，你觉得最大的风险点是什么（不是技术，而是人/流程）？
52. 遇到线上 GC 频繁，但没有日志，你第一时间的排查命令是什么（`jstat` / `jmap`）？
53. 和同事发生技术方案争执（比如同事非要只用 Redis_ASYNC），你怎么说服对方？
54. 接下来想深入学习什么？（建议回答：分布式事务（TCC/Saga）或者 云原生 K8s 编排，因为简历现在偏中间件，缺容器化经验）。

---

**特别提醒**：面试官对于“21岁”+“JDK25/分代ZGC”这个组合会极度怀疑真实性。**ZGC 和虚拟线程的底层细节（如 `jdk.internal.vm.Continuation`）** 以及 **G1/ZGC 的 GC 日志参数（`-Xlog:gc+heap=trace`）** 最好画图并背熟源码级流程，否则容易被认定为“背面试题”。祝你面试顺利！如有需要，我可以针对其中**任意一道题**给你写出标准满分答案。😊