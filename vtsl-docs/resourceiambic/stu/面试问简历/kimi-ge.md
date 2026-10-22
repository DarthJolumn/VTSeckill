基于这份简历，我为你梳理了一份**从基础到深挖、从八股到场景、从技术到工程**的完整面试问题清单。问题按模块分类，难度递进，覆盖简历中每一个技术点和每一个数字。

---

## 一、Java 基础与集合框架

### ArrayList
1. ArrayList 的底层数据结构是什么？扩容机制具体是怎样的？扩容因子是多少？
2. 你简历提到"理解底层原理"，那 ArrayList 的 `add(E e)` 和 `add(int index, E element)` 时间复杂度分别是多少？为什么？
3. ArrayList 是线程安全的吗？如果在多线程环境下使用 ArrayList 会有什么问题？具体会出现哪些异常？
4. 如何线程安全地使用 ArrayList？`Collections.synchronizedList` 和 `CopyOnWriteArrayList` 的实现原理和适用场景有什么区别？
5. ArrayList 的 `subList` 方法返回的是什么？对 subList 做结构性修改会影响原 List 吗？
6. `ArrayList` 和 `LinkedList` 在遍历性能上有什么差异？用 `fori` 遍历 LinkedList 为什么会慢？

### HashMap
7. HashMap 的底层数据结构是什么？JDK 8 相比 JDK 7 做了哪些优化？
8. HashMap 的 `put` 方法完整流程是怎样的？扰动函数的作用是什么？
9. 为什么 HashMap 的容量必须是 2 的幂次方？如果传入的初始容量不是 2 的幂次方，内部会怎么处理？
10. HashMap 的扩容机制（resize）具体流程是什么？JDK 8 中扩容时 rehash 的逻辑和 JDK 7 有什么区别？
11. 什么情况下 HashMap 会树化？什么情况下会退树化？为什么链表长度是 8 树化、6 退树化，而不是同一个阈值？
12. HashMap 是线程安全的吗？多线程环境下会出现什么问题（死循环、数据丢失、size 不准）？具体死循环是怎么产生的？
13. `ConcurrentHashMap` 在 JDK 7 和 JDK 8 的实现原理有什么区别？JDK 8 中 `size()` 方法是怎么保证准确性的？
14. `HashMap` 和 `Hashtable` 的区别？`ConcurrentHashMap` 和 `Collections.synchronizedMap` 的性能差异原因是什么？

### 反射、动态代理与注解
15. Java 反射机制是什么？反射的性能开销主要来自哪里？如何优化反射性能？
16. 动态代理的两种实现方式（JDK 动态代理和 CGLIB）有什么区别？Spring AOP 在什么情况下用 JDK、什么情况下用 CGLIB？
17. JDK 动态代理的原理是什么？生成的代理类长什么样？为什么只能代理接口？
18. CGLIB 动态代理的原理是什么？`MethodInterceptor` 的 `intercept` 方法参数都代表什么？
19. 自定义注解时，`@Retention` 的三种策略分别是什么？`@Target` 可以标注在哪些元素上？
20. 注解的底层实现原理是什么？注解信息存储在 class 文件的哪个部分？运行时如何获取注解？

---

## 二、并发编程

### AQS & synchronized
21. AQS（AbstractQueuedSynchronizer）的核心思想是什么？内部维护了几个关键变量？
22. AQS 的同步队列（CLH 变体）是什么结构？节点有哪些状态（CANCELLED、SIGNAL、CONDITION、PROPAGATE）？
23. 以 `ReentrantLock` 为例，描述加锁和解锁的完整流程（从 `lock()` 到 `unlock()`）。
24. `ReentrantLock` 的公平锁和非公平锁在实现上有什么区别？为什么默认是非公平锁？
25. `synchronized` 的锁升级过程是怎样的？无锁 → 偏向锁 → 轻量级锁 → 重量级锁的完整流程？
26. 偏向锁在 JDK 15 后为什么被废弃了？JDK 25 中偏向锁的状态是什么？
27. `synchronized` 底层实现原理（Monitor、对象头 Mark Word、锁记录 Lock Record）？
28. 什么是锁消除和锁粗化？JIT 编译器在什么情况下会进行这些优化？
29. `volatile` 的内存语义是什么？如何保证可见性和禁止指令重排序？`volatile` 能保证原子性吗？
30. `happens-before` 规则有哪些？`volatile` 的 happens-before 规则具体是什么？

### JUC 组件
31. `ReentrantLock` 和 `synchronized` 的区别？什么情况下必须用 `ReentrantLock`？
32. `Semaphore` 的原理是什么？应用场景有哪些？如果 permits 为负数会怎样？
33. `CountDownLatch` 和 `CyclicBarrier` 的区别？`CyclicBarrier` 的回调线程是什么时候执行的？
34. `ThreadLocal` 的原理是什么？内存泄漏是怎么产生的？为什么建议用 `static` 修饰 `ThreadLocal` 变量？
35. `ThreadLocalMap` 的哈希冲突是怎么解决的？`ThreadLocal` 的扩容机制是什么？
36. `ScopedValue`（JDK 20+ 预览，JDK 25 正式特性）是什么？和 `ThreadLocal` 的核心区别是什么？为什么虚拟线程场景下更适合用 `ScopedValue`？
37. `CompletableFuture` 的核心用法有哪些？`thenApply`、`thenAccept`、`thenCompose` 的区别？
38. `ForkJoinPool` 的工作窃取算法是什么？`commonPool` 的线程数默认是多少？

### 虚拟线程（Virtual Threads）
39. 虚拟线程和平台线程（OS 线程）的本质区别是什么？JVM 中虚拟线程的调度机制是怎样的？
40. 什么是"挂载（mount）"和"卸载（unmount）"？什么情况下虚拟线程会从载体线程上卸载？
41. 什么是 PINNING？什么代码会导致 PINNING？PINNING 会带来什么问题？
42. `synchronized` 块或 `native` 方法中的 IO 操作会导致 PINNING 吗？JDK 25 中对 PINNING 有什么优化或监控手段？
43. 虚拟线程适合什么场景？不适合什么场景（CPU 密集型）？为什么？
44. 虚拟线程的线程池模型和传统线程池有什么区别？为什么建议"每个任务一个虚拟线程"而不是池化？
45. 你的秒杀系统里，虚拟线程重构的是哪些 IO 密集型链路？具体是怎么替换的？`ExecutorService` 怎么配置？

---

## 三、JVM

### 内存模型与 GC
46. JVM 内存区域划分（堆、栈、方法区、程序计数器、本地方法栈）？各区域的作用和可能产生的异常？
47. 堆的划分（新生代、老年代、Eden、Survivor0、Survivor1）？对象晋升老年代的规则有哪些？
48. 什么是 TLAB（Thread Local Allocation Buffer）？为什么需要 TLAB？TLAB 的分配流程是怎样的？
49. G1 垃圾收集器的 Region 模型是怎样的？Humongous Region 是什么？多大的对象会直接进入 Humongous Region？
50. G1 的 RSet（Remembered Set）和 CSet（Collection Set）分别是什么？Card Table 的作用是什么？写屏障（Write Barrier）在 G1 中如何维护 RSet？
51. G1 的 Mixed GC 和 Full GC 有什么区别？什么情况下 G1 会触发 Full GC？
52. ZGC 的染色指针（Colored Pointers）原理是什么？ZGC 如何利用染色指针实现并发转移？
53. ZGC 的读屏障（Load Barrier）是怎么工作的？对性能有什么影响？
54. 分代 ZGC（Generational ZGC，JDK 21+）相比不分代 ZGC 做了哪些改进？为什么分代后性能更好？
55. 分代 ZGC 和 G1 的核心差异有哪些？（并发程度、STW 时间、内存布局、适用场景）
56. 你的简历说"STW 从 856ms 降至 3ms"，这个 856ms 是 G1 的什么 GC 事件导致的？（Mixed GC？Full GC？）3ms 是 ZGC 的哪个阶段？
57. ZGC 的 STW 时间理论上可以控制在 10ms 以内，但实际生产中哪些因素可能导致 STW 变长？
58. 类加载机制的完整流程？加载 → 验证 → 准备 → 解析 → 初始化的详细过程？
59. 双亲委派模型是什么？为什么需要双亲委派？如何打破双亲委派？（Tomcat、SPI、OSGi）
60. 自定义类加载器的场景有哪些？如何实现一个自定义类加载器？

### JVM 调优与问题排查
61. 常用的 JVM 监控命令有哪些？`jstat`、`jmap`、`jstack`、`jcmd` 分别看什么？
62. 如何排查内存泄漏？（MAT 工具、 dominator tree、大对象分析）
63. OOM 的几种类型（Heap Space、Metaspace、GC Overhead Limit、Unable to Create Native Thread）及排查思路？
64. GC 日志怎么看？G1 的 GC 日志和 ZGC 的 GC 日志格式有什么区别？

---

## 四、数据库（MySQL & PostgreSQL）

### MySQL InnoDB
65. InnoDB 的 Buffer Pool 是什么？LRU 链表是怎么工作的？为什么分 young 区和 old 区？
66. 什么是 Change Buffer？什么情况下会使用 Change Buffer？
67. Redo Log 和 Undo Log 的区别？Redo Log 的刷盘策略（innodb_flush_log_at_trx_commit）各有什么影响？
68. Binlog 的三种格式（Statement、Row、Mixed）有什么区别？为什么默认是 Row？
69. 两阶段提交（2PC）的过程是怎样的？Redo Log 和 Binlog 是怎么保证一致性的？
70. MVCC 的实现原理？Read View 的可见性判断规则？`trx_id` 和 `roll_pointer` 的作用？
71. RC（Read Committed）和 RR（Repeatable Read）在 MVCC 实现上有什么区别？
72. InnoDB 的四种隔离级别分别解决了什么问题？幻读在 RR 级别下真的完全解决了吗？
73. 快照读和当前读的区别？`SELECT ... FOR UPDATE` 和 `SELECT ... LOCK IN SHARE MODE` 的加锁范围？
74. 间隙锁（Gap Lock）和临键锁（Next-Key Lock）是什么？什么情况下会退化为记录锁？
75. 索引的底层数据结构是什么？B+ 树和 B 树的区别？为什么 InnoDB 用 B+ 树？
76. 聚簇索引和非聚簇索引的区别？回表查询是什么？覆盖索引怎么避免回表？
77. 联合索引的最左前缀原则是什么？索引下推（ICP）是什么？
78. `EXPLAIN` 的结果怎么看？`type` 字段从优到劣的排序？`Extra` 中 `Using filesort`、`Using temporary` 代表什么？
79. 慢 SQL 优化的一般思路是什么？（索引优化、SQL 改写、分库分表、读写分离）
80. 大表分页查询 `LIMIT 1000000, 10` 有什么问题？怎么优化？

### PostgreSQL
81. PostgreSQL 的堆表存储结构和 InnoDB 的聚簇索引存储有什么区别？
82. PostgreSQL 的 TID（Tuple ID）回表机制是什么？和 InnoDB 的回表有什么区别？
83. PostgreSQL 的 MVCC 实现和 InnoDB 有什么不同？（事务 ID、可见性判断、VACUUM 机制）
84. PostgreSQL 的事务隔离级别和 MySQL 有什么差异？PostgreSQL 的默认隔离级别是什么？
85. PostgreSQL 的 VACUUM 和 AUTOVACUUM 是什么？为什么需要 VACUUM？不 VACUUM 会怎样？

---

## 五、Redis & MongoDB

### Redis
86. Redis 的基本数据结构有哪些？底层分别用了什么数据结构实现？
87. SDS（Simple Dynamic String）相比 C 字符串有什么优势？`sdshdr` 的结构是怎样的？
88. `quicklist` 和 `listpack` 分别是什么？Redis 7.0 为什么用 listpack 替代 ziplist？
89. `dict`（字典）的渐进式 rehash 是怎么实现的？rehash 期间读写操作怎么处理？
90. `skiplist`（跳表）的查询复杂度是多少？为什么用跳表而不是红黑树？
91. Redis 的持久化机制 RDB 和 AOF 的区别？混合持久化是什么？
92. AOF 的三种刷盘策略（always、everysec、no）的区别？生产环境一般怎么配？
93. Redis 的过期淘汰策略有哪些？（惰性删除、定期删除）内存淘汰策略有哪些？（LRU、LFU、TTL、Random）
94. 缓存穿透、缓存击穿、缓存雪崩的区别及解决方案？布隆过滤器的原理和缺点？
95. Redis 分布式锁的实现？`SETNX` + `EXPIRE` 有什么问题？Redisson 的看门狗机制是什么？
96. Redis 主从复制的原理？全量复制和增量复制的触发条件？
97. Redis Cluster 的哈希槽（Slot）机制？一个键是怎么映射到具体节点的？
98. Redis 的大 Key 和热 Key 问题怎么发现和解决？

### MongoDB
99. MongoDB 的文档存储模型和关系型数据库的表模型有什么区别？
100. MongoDB 的索引类型有哪些？单字段索引、复合索引、多键索引、文本索引、TTL 索引？
101. MongoDB 的原子更新操作有哪些？`findAndModify` 和 `updateOne` 的区别？
102. MongoDB 的 WiredTiger 存储引擎有什么特点？支持事务吗？

---

## 六、Spring 生态

### Spring Boot
103. Spring IoC 容器的初始化流程？`refresh()` 方法的 12 个步骤？
104. Bean 的生命周期完整流程？（实例化 → 属性赋值 → 初始化 → 销毁）各个扩展点（Aware、BeanPostProcessor、InitializingBean 等）？
105. Spring AOP 的实现原理？JDK 动态代理和 CGLIB 的选用规则？
106. Spring Boot 自动配置原理？`@SpringBootApplication` 的组成？`spring.factories` 和 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 的区别？
107. Spring Boot 的 Starter 机制是什么？如何自定义一个 Starter？
108. Spring 的循环依赖是怎么解决的？三级缓存分别是什么？为什么需要三级缓存而不是两级？
109. Spring Boot 的启动流程？`SpringApplication.run()` 内部做了哪些事？
110. `@Transactional` 的实现原理？事务传播行为有哪些？什么情况下事务会失效？
111. Spring Boot 的 Actuator 端点有哪些？如何安全地暴露这些端点？

### Spring Cloud
112. Spring Cloud Gateway 的核心概念？（Route、Predicate、Filter）工作流程？
113. 服务注册与发现的原理？Nacos/Eureka/Consul 的对比？
114. 限流熔断的实现？Sentinel 和 Hystrix 的区别？滑动窗口、令牌桶、漏桶算法的原理？
115. 负载均衡 Ribbon 的几种策略？OpenFeign 的工作原理？
116. 分布式事务的解决方案？（Seata AT/TCC/Saga、本地消息表、最大努力通知）

---

## 七、中间件与工具

### Kafka
117. Kafka 的 Topic、Partition、Broker、Consumer Group 的概念？
118. Partition 的副本机制？Leader 和 Follower 的同步机制？ISR（In-Sync Replicas）是什么？
119. ACK 机制（acks=0/1/all）的区别？生产环境一般怎么选？
120. Kafka 的消费者组重平衡（Rebalance）机制？什么情况下会触发 Rebalance？
121. Kafka 的位移提交方式？自动提交和手动提交的区别？
122. Kafka 的零拷贝（Zero-Copy）技术是什么？`sendfile` 系统调用的流程？
123. Kafka 的日志存储结构？`.log` 文件和 `.index` 文件的关系？

### 监控与测试
124. SkyWalking 的链路追踪原理？（Agent 探针、Span、Trace、Segment）怎么实现无侵入埋点？
125. JMeter 的线程组、Ramp-Up 时间、循环次数怎么配置？分布式压测怎么搭建？
126. Apifox 和 Postman 的区别？接口自动化测试怎么做的？

---

## 八、项目深挖：秒杀系统

### 架构设计
127. 整个秒杀系统的架构图是怎样的？画出请求从用户到数据库的完整链路。
128. 为什么选用分代 ZGC 和虚拟线程？是在什么背景下做的这个技术选型？
129. 系统的 QPS 目标是多少？实际压测达到了多少？瓶颈在哪里？

### 秒杀扣减策略引擎
130. `SeckillStrategy` 接口是怎么设计的？策略模式在这里解决了什么问题？
131. 三种策略的适用场景分别是什么？什么情况下用 REDIS_ASYNC，什么情况下用 REDIS_SYNC？
132. 工厂模式是怎么按 `SeckillMode` 路由的？如果新增一种策略，需要修改哪些地方？符合开闭原则吗？
133. **REDIS_ASYNC**：Redis Lua 脚本的原子扣减是怎么写的？Lua 脚本为什么能保证原子性？
134. **REDIS_ASYNC**：Kafka fire-and-forget 异步落库，如果 Kafka 发送失败怎么办？数据会不会丢失？
135. **REDIS_SYNC**：`send.get()` 等待 Broker ACK 的具体实现？超时时间怎么设置？
136. **DB_QUEUE**：乐观锁是怎么实现的？`version` 字段还是 CAS？如果大量冲突导致失败率很高怎么办？
137. **DB_QUEUE**：MQ 顺序消费是怎么保证的？Kafka 的 Partition 内顺序消费？

### 滑动窗口自适应机制
138. 环形数组滑动窗口的具体实现？窗口大小为什么是 100 次？
139. 统计的是 Kafka 发送耗时还是端到端耗时？超时率的阈值是多少？
140. "1000 QPS 自动触发切换"这个 1000 QPS 是怎么定义的？是瞬时 QPS 还是滑动平均？
141. 切换策略时有没有抖动问题？比如 QPS 在阈值上下波动时频繁切换，怎么解决？（ hysteresis 机制？）
142. 10w 订单零错误零超卖是怎么验证的？压测脚本怎么写的？数据一致性怎么校验的？

### 数据一致性与回补机制
143. 订单取消/超时未支付的判定逻辑是什么？定时任务还是延迟消息？
144. Redis Lua 幂等回补的 Lua 脚本怎么写的？幂等键的设计是什么？
145. DB 原子更新回补是怎么做的？乐观锁还是悲观锁？
146. 唯一索引幂等是在哪张表上建的？什么字段？
147. 手动 ACK 的具体实现？如果 ACK 前消费者挂了，消息会重复消费吗？怎么保证幂等？
148. Semaphore 背压是怎么对齐 HikariCP 池大小的？Semaphore 的 permits 数量等于连接池大小吗？如果 Kafka 消费线程数大于连接池大小会怎样？

### GC 与并发治理
149. G1 的 STW 856ms 是怎么测出来的？用的什么工具？是 GC 日志里的哪个字段？
150. ZGC 的 3ms STW 是在什么压测条件下测的？并发标记阶段的停顿算不算？
151. P99 延迟下降 32%，这个 P99 是指接口响应时间还是 GC 停顿时间？压测的并发数是多少？
152. 虚拟线程重构 IO 密集型链路，具体重构了哪些接口？是 HTTP 接口还是 Kafka Consumer？
153. 600 并发下 TPS 提升 7.6%，这个 600 并发是指虚拟线程数还是 JMeter 线程数？平台线程和虚拟线程的对比测试是怎么控制的？
154. 虚拟线程的 PINNING 问题在秒杀系统里有没有遇到？怎么监控和解决的？

### 瓶颈定位与优化
155. SkyWalking 怎么定位到 Kafka `send.get()` 是根因的？看了哪个 Span？耗时分布是怎样的？
156. fire-and-forget + pending 标记回调确认的具体流程？pending 标记存在哪里？Redis 还是内存？
157. SET pending TTL 兜底是什么意思？TTL 设置多长时间？如果回调一直没来怎么办？
158. 定时补投的定时任务是怎么实现的？Spring Scheduler 还是 Quartz？补投的幂等性怎么保证？
159. P99 从 264ms 降至 92ms，这个优化是在什么 QPS 下测的？同条件具体指哪些条件保持一致？

---

## 九、项目深挖：短链平台

### 架构设计
160. 短链系统的整体架构？KeyGenerator 服务和 API 业务服务为什么拆分？
161. gRPC 契约通信的具体设计？Protobuf 的定义？为什么不用 REST 而用 gRPC？
162. 短链服务的 QPS 预估是多少？跳转接口的延迟要求是多少？

### KGS 预生成发号器
163. SecureRandom 生成 Base62 编码，6 位短码容量 568 亿是怎么算的？（62^6 ≈ 568 亿）
164. 为什么用 SecureRandom 而不是 UUID 或雪花算法？随机码防枚举是什么意思？
165. MongoDB 账本落账的数据结构是什么？状态机有哪些状态？（未使用、已使用、已回收？）
166. 批量写入 Redis List 队列，批量 1000 是怎么定的？如果 MongoDB 写入成功但 Redis 写入失败怎么办？
167. 阈值 200 自动补货，这个 200 是怎么定的？补货是同步补还是异步补？
168. RPOP 原子取号 + MongoDB CAS 条件更新的双保险，具体流程是什么？如果 CAS 失败怎么办？
169. "取号路径无锁"是什么意思？Redis 的 RPOP 是单线程的，为什么还说无锁？

### API 业务层与缓存
170. 短链 CRUD 的软删除是怎么实现的？deleted 字段还是状态字段？
171. 归属校验是怎么做的？用户只能操作自己的短链吗？权限校验在哪个层做？
172. 网关前置 Base62 格式正则校验的表达式是什么？拦截率有多少？
173. 二级缓存 Caffeine L1（3s）+ Redis L2（24h）的更新策略是什么？缓存不一致怎么解决？
174. 缓存值携带 urlId，这个 urlId 是数据库主键吗？为什么不用短码直接统计？
175. 短链跳转的 302 重定向是怎么实现的？Location 头的拼接逻辑？
176. 点击统计是怎么做的？同步写还是异步写？如果 Redis 挂了统计会丢吗？

### 存储选型
177. MongoDB 账本的唯一索引建在哪个字段？CAS 状态机的状态流转图是怎样的？
178. MySQL 业务映射表有哪些字段？和 MongoDB 账本是怎么关联的？
179. 两套存储独立演进，如果 MongoDB 和 MySQL 数据不一致怎么办？有没有对账机制？
180. 短码字符串作为解耦键，如果短码冲突了怎么办？生成时去重还是写入时去重？

---

## 十、工程能力与软技能

181. 两个项目都是独立完成的吗？从 0 到 1 花了多长时间？遇到最大的困难是什么？
182. 简历上写"善于使用 AI 工具辅助编码"，具体用了哪些 AI 工具？怎么辅助代码审查的？
183. 秒杀系统的代码量大概多少？单元测试覆盖率是多少？
184. 有没有做 CI/CD？怎么部署的？Docker？K8s？
185. 如果秒杀系统要支撑 100w QPS，现在的架构还需要做哪些改进？
186. 短链系统如果要求全球多地域部署，架构怎么调整？
187. 你平时怎么学习新技术？JDK 25 还没正式发布（注：当前是 2026 年 8 月，JDK 25 已发布），你是怎么提前学习和验证的？
188. 你的职业规划是什么？毕业后想从事什么方向？
189. 如果面试官质疑你的项目数据（比如 10w 订单零错误），你怎么证明？
190. 如果让你给学弟学妹推荐一本技术书，你推荐什么？为什么？

---

## 十一、场景设计与开放问题

191. 设计一个支持 1000w QPS 的短链系统，架构怎么设计？（全球多活、CDN、边缘计算）
192. 如果 Redis 集群挂了，秒杀系统怎么降级？（直接查 DB？限流？熔断？）
193. 如果 Kafka 消息积压了 1000w 条，怎么处理？（扩容消费者、跳过消息、重置 offset）
194. 设计一个防刷机制，同一个用户在 1 秒内只能下单一次，怎么实现？（Redis + Lua + 令牌桶）
195. 如果要求秒杀系统的订单数据实时同步到数据仓库，怎么做？（CDC、Canal、Flink）
196. 短链系统怎么防止恶意刷量？（IP 限流、用户限流、验证码）
197. 如果 MongoDB 的短码生成速度跟不上消费速度，怎么优化？（预生成更多、多实例并行生成）
198. 设计一个分布式 ID 生成器，要求全局唯一、趋势递增、高性能。（雪花算法、号段模式、Leaf）
199. 如果秒杀系统的数据库主库挂了，怎么保证可用性？（主从切换、多主架构、异地多活）
200. 你简历里的技术栈很新（JDK 25、分代 ZGC、虚拟线程），如果进的公司还在用 JDK 8，你能适应吗？怎么说服团队升级？

---

## 面试准备建议

| 优先级 | 模块 | 原因 |
|---|---|---|
| 🔴 最高 | 项目深挖（秒杀+短链） | 简历上的每一个数字、每一个技术选型都必须能自圆其说 |
| 🟠 高 | JVM（ZGC/G1）+ 并发编程 | 简历亮点，面试官必深挖 |
| 🟡 中高 | MySQL/Redis 底层原理 | 后端基础，八股必考 |
| 🟢 中 | Spring 生态 + Kafka | 框架使用，结合项目问 |
| 🔵 低 | 工具链（SkyWalking/JMeter） | 了解原理即可 |

**特别提醒**：简历中的数字（856ms→3ms、32%、7.6%、23%、600并发、10w订单、P99 264ms→92ms、568亿等）是面试官最关心的锚点，每一个数字都要准备好：
- 测试环境配置（机器型号、CPU、内存、JVM 参数）
- 测试工具版本和配置
- 测试方法论（跑了几次？取平均还是取最优？）
- 可复现的 GitHub 代码链接（你已经在简历上放了，确保代码和描述一致）

祝你面试顺利！需要我对某个模块的问题做**参考答案梳理**或**模拟面试问答**，随时告诉我。