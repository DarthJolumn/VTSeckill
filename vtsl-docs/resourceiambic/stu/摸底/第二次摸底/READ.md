按你给的调整方案，更新后的完整框架如下：

---

### 模块一：Java 语言基础（根基）

- **1.1 并发编程**：线程池（继承体系、拒绝策略、工作队列）、锁机制（锁升级、AQS、ReentrantLock）、线程通信（wait set、notify/notifyAll、虚假唤醒）、内存可见性（volatile）、并发工具类（CountDownLatch、CyclicBarrier、Semaphore）
- **1.2 语言特性**：ThreadLocal、ScopedValue、虚拟线程、ForkJoinPool
- **1.3 IO 模型**：BIO / NIO / AIO
- **1.4 新特性**：Lambda / Stream / Optional
- **1.5 设计模式**：单例（DCL）、工厂模式、策略模式、模板方法模式、生产者消费者模式

> 并发编程与模块四（MySQL）优先级并列为 P0。

### 模块二：JVM（语言运行时）

- **2.1 内存模型**：运行时数据区、对象创建流程（TLAB、指针碰撞、对象头）
- **2.2 垃圾回收**：分代 GC、G1、CMS、GC 调优参数
- **2.3 类加载机制**：双亲委派、打破双亲委派、上下文类加载器（SPI 机制）
- **2.4 诊断工具**：jps、jstat、jmap、jstack

### 模块三：框架层（开发工具）

- **3.1 Spring 核心**：IoC、AOP、Bean 生命周期、注解装配、组件扫描
- **3.2 Spring Boot 自动装配**：`@EnableAutoConfiguration` → `spring.factories` / `AutoConfiguration.imports` → 条件注解（优先级提升至与 IoC/AOP 同级）
- **3.3 ORM**：MyBatis / MyBatis-Plus 基础用法恢复

### 模块四：数据存储（持久层）

- **4.1 MySQL 索引**：B+ 树结构、聚簇/非聚簇索引、回表、覆盖索引、最左前缀、索引失效
- **4.2 MySQL 事务**：ACID、隔离级别、MVCC（Read View + Undo Log 版本链）、当前读 vs 快照读
- **4.3 MySQL 锁**：行锁、表锁、间隙锁、临键锁、死锁排查
- **4.4 InnoDB 存储引擎**：Buffer Pool、Log Buffer、Redo Log 刷盘机制（write pos / checkpoint）

> 与模块一（并发编程）优先级并列为 P0。

### 模块五：缓存层

- **5.1 Redis 基础**：数据类型、底层编码、过期淘汰策略
- **5.2 缓存问题**：穿透 / 击穿 / 雪崩
- **5.3 持久化**：RDB、AOF、混合持久化
- **5.4 高可用**：主从复制、哨兵、集群
- **5.5 进阶**：分布式锁、Lua 脚本

### 模块六：消息队列

- **6.1 Kafka 核心概念**：Topic / Partition / Consumer Group
- **6.2 消息顺序性保证**
- **6.3 消费者偏移量提交机制**

### 模块八：网络基础

- **8.1 TCP**：三次握手、四次挥手
- **8.2 HTTP**：HTTP / HTTPS 区别、状态码

### 模块九：分布式理论

- **9.1 CAP 理论**
- **9.2 BASE 理论**

### 模块十：工程化 + 项目

- **10.1 工程化**：Docker、Linux
- **10.2 项目链路**：高并发秒杀场景完整链路回顾

### 模块十一：面试话术与表达训练

- **11.1 口述表达训练**：针对每个模块的核心知识点，练习用 2-3 分钟清晰口述
- **11.2 项目追问模拟**：模拟面试官追问项目细节，练习临场组织语言

---

**最终学习顺序**：模块一 → 模块二 → 模块三 → 模块四 → 模块五 → 模块六 → 模块八 → 模块九 → 模块十 → 模块十一

确认无误的话，下一步我就按这个框架排逐天的学习计划。