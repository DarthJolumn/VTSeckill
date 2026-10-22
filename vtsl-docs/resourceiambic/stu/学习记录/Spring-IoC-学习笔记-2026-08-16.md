# Spring IoC 学习笔记（2026-08-16）

> 来源：当天与 AI 的完整学习对话（约 2.5 小时）+ 手写 IoC 容器实战。
> 关联：`秋招冲刺总计划.md` §1.5 短板 2「Spring IoC 容器」。
> 本文档是长期记忆，复习时把对应小节贴回对话让 AI 考你。

---

## 0. 学习概况 & 掌握度

| 主题 | 状态 | 说明 |
|---|---|---|
| Bean 生命周期 8 步 | ✅ 会背 | 顺序 + 每步职责清晰 |
| 实例化（createBeanInstance） | ✅ 会 | 三条路径 + 构造器选择 |
| 属性填充 populateBean | ✅ 会 | 手写容器实现过 |
| Aware 回调 | ✅ 会 | 3 接口 + 分类（基础/容器能力/底层） |
| BPP before/after | ✅ 会 | 责任链模型 + @PostConstruct 执行时机 |
| 三级缓存 + 循环依赖 | ✅ 手写验证过 | 含 lambda 触发链路源码（8/17 复习强化） |
| AOP 代理生成时机 | ✅ 会 | 两个时机 + 精确表述（8/17 复习确认） |
| BeanFactory vs ApplicationContext | ✅ 会 | 组合模式 + 方法清单 |
| 注解对比 / 线程安全 | ✅ 会 | 含 @Repository 异常转换、CHM 复合操作（8/17 复习补充） |
| doCreateBean 四步 / BeanDefinition 路径 | ✅ 会 | 已跟完整源码（8/17 上午） |
| @Autowired 注入链路 | ✅ 会 | 用户确认（8/17 下午） |
| 反射 vs CGLIB | ✅ 会 | AOP 专题已补全（8/17，见 AOP-学习笔记） |
| getMergedLocalBeanDefinition | 🟡 初步学 | 父子合并概念懂 |
| @Async/@Transactional/@Cacheable 机制 | 🟡 初步学 | BPP+代理管道懂，Advisor 细节待熟 |
| @Transactional 失效 / prototype 注入 / 启动慢 | ⬜ 待补 | 第四层场景题 |

---

## 1. Bean 生命周期全链路（面试主框架）

**完整 9 步（含隐藏步骤）：**

```
① InstantiationAwareBeanPostProcessor.postProcessBeforeInstantiation  ← 隐藏！可替代 Bean
② 实例化（反射 / CGLIB）
③ 属性填充（populateBean，依赖注入）
④ Aware 回调（invokeAwareMethods）
⑤ BeanPostProcessor.postProcessBeforeInitialization（BPP 前置）
⑥ 初始化（invokeInitMethods：@PostConstruct 已在上一步 / afterPropertiesSet / init-method）
⑦ BeanPostProcessor.postProcessAfterInitialization（BPP 后置，AOP 代理在这）
⑧ 使用
⑨ 销毁（@PreDestroy / DisposableBean.destroy / destroy-method，仅单例）
```

**每步一句话职责：**

| 步骤 | 一句话 |
|---|---|
| 实例化 | 在堆上 new 出裸对象（字段全 null/默认值） |
| 属性填充 | 把依赖注入进去（@Autowired/@Value/构造器参数） |
| Aware | Bean 感知容器基础设施（名字/BeanFactory/上下文） |
| BPP 前置 | 容器级统一加工（@PostConstruct 在这里被 CommonAnnotationBeanPostProcessor 触发） |
| 初始化 | 业务自定义初始化（afterPropertiesSet / init-method） |
| BPP 后置 | 容器级后加工，**AOP 代理在这里生成** |
| 销毁 | 单例 Bean 容器关闭时回调 |

**面试话术模板（三段式）：**

> Bean 生命周期分三个阶段：创建阶段先实例化出裸对象，再通过 populateBean 完成依赖注入；初始化阶段依次执行 Aware 回调 → BPP 前置 → 初始化方法 → BPP 后置；使用和销毁阶段单例 Bean 在容器关闭时执行销毁回调。其中最重要的扩展点是 BeanPostProcessor，AOP 代理就是在 BPP 后置阶段生成的。

---

## 2. 实例化阶段 createBeanInstance

**三条路径（按优先级）：**

| 你写的代码 | 走的路径 | 频率 |
|---|---|---|
| `@Bean` 方法返回 `MyService::new` | Instance Supplier | 极少，可忽略 |
| `@Bean public Xxx xxx()` | 工厂方法（反射调 @Bean 方法） | 常见（Java Config） |
| 普通类 + 构造器 | 普通构造器（带缓存） | **最常见，主战场** |

**关键纠正（今天纠正过的）：**
- ❌ 不是 `Class.forName()` —— 那只负责"加载类"，Spring 早已持有 Class 对象。
- ✅ 是 `BeanUtils.instantiateClass()` → `constructor.newInstance(args)` + `setAccessible(true)`，本质就是最朴素的 JDK 反射。
- 「构造器的 Bean」说法不准确：普通构造器创建的是**裸对象**，依赖还没注入，后面还要走 populateBean；而 @Bean 工厂方法返回的才是完整 Bean。

**构造器选择决策：**
```
扫描构造器
├── 有 @Autowired 的 → 用它（不管有几个）
├── 只有一个构造器 → 直接用（可省略 @Autowired）
├── 多个构造器且无 @Autowired → 用无参构造器
└── 都没有 → instantiateBean() 无参反射
```

**@Configuration / @Bean 使用场景（3 个）：**
1. 第三方 jar 里的类，改不了源码（如 RedisTemplate）
2. 同一个类要注册多个不同配置的 Bean
3. 创建逻辑复杂、不适合放构造器
> 规律：自己写的类 → @Component；别人的类/复杂创建/同类型多实例 → @Configuration + @Bean。

---

## 3. 属性填充 populateBean

- 本质：从容器查依赖 → 赋值到字段（@Autowired 字段/setter 注入在这一步，构造器注入在实例化阶段）。
- 缓存里查得到 → 直接取；查不到 → 递归触发该依赖的完整创建（getBean → createBean）。

---

## 4. 初始化阶段 initializeBean

**源码顺序（AbstractAutowireCapableBeanFactory.initializeBean）：**

```
1. invokeAwareMethods()                       → Aware 回调
2. applyBeanPostProcessorsBeforeInitialization() → BPP 前置
        └── CommonAnnotationBeanPostProcessor 在这里执行 @PostConstruct
3. invokeInitMethods()                        → afterPropertiesSet → init-method
4. applyBeanPostProcessorsAfterInitialization()  → BPP 后置（AOP 代理）
```

**Aware 注入机制（8/17 深化）：** Aware 不走 @Autowired，而是容器**主动调 setter**：

```
invokeAwareMethods()（initializeBean 内，硬编码）：
  BeanNameAware      → setBeanName(beanName)
  BeanClassLoaderAware → setBeanClassLoader(classLoader)
  BeanFactoryAware   → setBeanFactory(this)

ApplicationContextAware / EnvironmentAware / MessageSourceAware 等高层 Aware：
  由 ApplicationContextAwareProcessor（一个 BPP）在 postProcessBeforeInitialization 中处理：
  ((ApplicationContextAware) bean).setApplicationContext(this.applicationContext)
```

> 为什么不用 @Autowired 注入这些？@Autowired 解决**业务 Bean 之间的依赖**，Aware 解决 **Bean 对容器基础设施的感知**，是两个契约层次。Aware 不依赖 BPP 排序、是容器初始化协议的一部分，更早更可靠。

**Aware 分类（复习补充 8/17）：** 所有 Aware 回调都在 BPP 前置**之前**（invokeAwareMethods）。

| 分类 | 代表接口 | 日常使用 |
|---|---|---|
| 基础感知 | BeanNameAware / BeanClassLoaderAware | ❌ 极少（纯框架内部） |
| 容器能力感知 | ApplicationContextAware / EnvironmentAware / MessageSourceAware | ✅ 常用（拿上下文/配置/国际化） |
| 底层感知 | BeanFactoryAware | ⚠️ 偶尔（自定义基础设施组件） |

**BPP 前置是一个责任链（复习补充 8/17）：** `@PostConstruct` 只是链条上的一环，不是全部：

```
applyBeanPostProcessorsBeforeInitialization()
├── ApplicationContextAwareProcessor      → 统一调用 ApplicationContextAware 等
├── CommonAnnotationBeanPostProcessor     → 触发 @PostConstruct
├── AutowiredAnnotationBeanPostProcessor  → @Autowired/@Value 注入确认
└── 自定义 BPP                            → 参数校验/脱敏/解密等
```

**初始化三方式顺序：** `@PostConstruct` → `InitializingBean.afterPropertiesSet()` → `init-method`（XML/@Bean 指定）。推荐 @PostConstruct，不耦合 Spring API。

**⚠️ 今天纠正 AI 幻觉的点（务必记牢）：**
- `@PostConstruct` 是在 **BPP 前置阶段**由 `CommonAnnotationBeanPostProcessor` 触发的，**不在 invokeInitMethods 里**。
- `afterPropertiesSet` 和 `init-method` 才在 invokeInitMethods 阶段执行。
- 三者的最终顺序：@PostConstruct → afterPropertiesSet → init-method。

**销毁：** `@PreDestroy` / `DisposableBean.destroy()` / `destroy-method`，只有**单例** Bean 会被容器主动销毁，prototype 不会。

---

## 5. 三级缓存 & 循环依赖（今天重点，手写验证过）

### 5.1 三个缓存

| 缓存 | 变量名 | 存什么 |
|---|---|---|
| 一级 | `singletonObjects` | 完整单例 Bean（初始化完成的成品） |
| 二级 | `earlySingletonObjects` | 早期引用（实例化+填充完，但未初始化/未代理） |
| 三级 | `singletonFactories` | ObjectFactory 工厂，**value 是 lambda**，延迟决策是否生成代理 |

### 5.2 循环依赖完整流程（A↔B）

```
实例化 A → 放三级缓存（lambda）→ 属性填充发现需要 B
  → getBean(B) 查三级缓存都没有 → 创建 B
      → 实例化 B 放三级缓存 → B 填充发现需要 A
      → 从三级缓存取 A：调 ObjectFactory.getObject()
            └── 判断 A 是否需要 AOP：需要则生成代理，返回代理；否则返回原始 A
      → A 移入二级缓存 earlySingletonObjects
      → B 拿到 A 的早期引用，完成填充 → B 初始化完成 → 移入一级缓存
  → 回到 A，拿到完整 B → A 填充完成 → A 初始化完成 → 移入一级缓存
```

### 5.3 为什么需要三级而不是两级（核心面试题）

- 二级缓存（earlySingletonObjects）能解决**普通 bean** 的循环依赖。
- 但若 A 需要 AOP 代理：最终暴露给容器的 A 是**代理对象**，而 B 在填充时拿到的早期引用如果是**原始对象**，就会出现「B 注入原始 A，容器里是代理 A」的**引用不一致**。
- 三级缓存用 **ObjectFactory 延迟决策**：把"要不要代理"从固定时间点变成**按需触发**——只有真正发生循环依赖、别的 Bean 来取早期引用时，才调 lambda 判断是否生成代理。
- `earlyProxyReferences`：记录"已提前代理过"的 bean，防止 BPP 后置阶段重复生成代理。

**Lambda 触发链路（复习补充 8/17，源码级）：**

```
B 填充需要 A → getBean("A") → doGetBean → getSingleton("A", true)
  → 一级 miss → isSingletonCurrentlyInCreation("A")=true → 二级 miss
  → 三级 hit：ObjectFactory（lambda）
  → ⭐ singletonFactory.getObject()   ← 取出的瞬间触发，四步在同步块内原子完成
      → getEarlyBeanReference(beanName, mbd, bean)
        → wrapIfNecessary()：遍历所有 Advisor 匹配 Pointcut（按需检查）
          → 匹配 → createProxy() 返回代理
          → 不匹配 → 返回原始 bean
  → 结果放二级缓存 → 删三级缓存 → 返回早期引用给 B
```

- `ObjectFactory` 是 `@FunctionalInterface`，**只有一个 `getObject()` 方法**；lambda 通过闭包捕获 `beanName`、`mbd`、`bean` 和 `this`（持有全部 Advisor），复杂逻辑都在背后。
- **为什么必须延迟到此刻才决策**：放三级缓存的时机是「实例化后、填充前」，此时部分 Advisor 可能还没注册完（如 @Import 动态注册的切面）；只有别的 Bean 真来索取早期引用时，才说明基础设施已就绪，AOP 匹配结果才可靠。

**注意表述（复习纠错 8/17）：** 不是「循环依赖时 AOP 在属性填充阶段生成」，而是「**三级缓存的工厂回调提前调用了 `AbstractAutoProxyCreator` 的代理创建逻辑**」。代理生成逻辑始终由 BPP after / `getEarlyBeanReference` 触发。

### 5.4 循环依赖边界

- **构造器循环依赖无法解决**：构造器注入在实例化阶段就要拿依赖，而三级缓存是实例化**之后**才放入的，Spring 检测到 `singletonsCurrentlyInCreation` 中已有该 Bean，直接抛 **`BeanCurrentlyInCreationException`**（死锁）。解决：`@Lazy` 延迟加载（注入代理，真正调用才初始化）或改 setter/字段注入。
- **Lombok `@RequiredArgsConstructor` 本质是构造器注入**，同样会报循环依赖错。
- **Spring Boot 2.6+ 默认禁止循环引用**（`spring.main.allow-circular-references=false`），field/setter 循环依赖也会报错，需显式打开才允许。

### 5.5 @Async 循环依赖生产故障（面试场景题）

- 现象：@Async 会生成代理；循环依赖 + @Async 时早期引用可能是原始对象，最终容器里是代理，导致类型/行为不一致，Spring 启动崩。
- 救火方案（无法大面积重构时）：① `@Lazy` 懒加载打断依赖；② 事件驱动解耦；③ 重写设计消除循环依赖。

---

## 6. AOP 代理生成时机

- **正常（无循环依赖）**：BPP 后置阶段（`postProcessAfterInitialization`）由 `AbstractAutoProxyCreator` 统一生成。
- **循环依赖**：三级缓存 lambda 提前生成早期代理，`earlyProxyReferences` 防止后置阶段重复代理。
- **@Async / @Transactional / @Cacheable 底层一致**：都是「BPP + AOP 代理」——`AnnotationAwareAspectJAutoProxyCreator` 在 BPP after 统一检查，匹配 Advisor 就 `createProxy()`，运行时拦截执行切面逻辑。差异只在各自 Advisor 内的 Interceptor：

| 注解 | Advisor | Interceptor 做的事 |
|---|---|---|
| @Transactional | BeanFactoryTransactionAttributeSourceAdvisor | TransactionInterceptor：开启事务 → 执行业务 → 提交/回滚 |
| @Cacheable | BeanFactoryCacheOperationSourceAdvisor | CacheInterceptor：查缓存 → 命中返回 → 未命中执行并写缓存 |
| @Async | AsyncAnnotationAdvisor | AnnotationAsyncExecutionInterceptor：提交线程池异步执行 |

> 一句话：Spring 所有高级特性（事务/缓存/异步/AOP）都收敛到 **BeanPostProcessor + AOP 代理** 两个基石。

---

## 7. doCreateBean 四步（创建 Bean 总入口，源码级 8/17）

```
createBean() → doCreateBean(beanName, mbd, args)
│
├── ① 实例化 createBeanInstance()            → 分配内存得到"空壳对象"
├── ② applyMergedBeanDefinitionPostProcessors() → 合并后 BD 的后处理（@Autowired 元数据收集）
├── ③ 早期暴露（循环依赖关键）：earlySingletonExposure = 单例 && allowCircularReferences && 正在创建中
│      └── addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean))
├── ④ populateBean()                         → 属性填充（递归注入依赖）
├── ⑤ initializeBean()                       → Aware → BPP before → init → BPP after（AOP 代理）
├── ⑥ exposedObject 校验：
│      earlySingletonReference = getSingleton(beanName, false)
│      ├── 若 exposedObject == bean（未被代理）→ 直接用早期引用
│      └── 若被代理过且 hasDependentBean → 抛 BeanCurrentlyInCreationException
│           （早期引用被注入其他 Bean，但最终被包装 → 引用不一致）
└── ⑦ registerDisposableBeanIfNecessary()    → 注册销毁回调（@PreDestroy/DisposableBean/destroy-method）
```

> 面试一句话：**实例化 → 早期暴露 → 填充 → 初始化 → 注册销毁**。其中③⑥是循环依赖的配套设计。

---

## 8. BeanDefinition 三条路径（8/17 深化）

| 来源 | 读取器/处理器 | 生成的 BD 类型 |
|---|---|---|
| XML | XmlBeanDefinitionReader | GenericBeanDefinition |
| 注解（@Component 扫描） | ClassPathBeanDefinitionScanner | ScannedGenericBeanDefinition |
| JavaConfig（@Bean） | ConfigurationClassPostProcessor | ConfigurationClassBeanDefinition |

最终都汇聚到 `DefaultListableBeanFactory.beanDefinitionMap`（ConcurrentHashMap<String, BeanDefinition>），容器只认 BeanDefinition——**定义与执行分离**。

### getMergedLocalBeanDefinition（父子合并）

为什么不用注册表里的原始 BD，要合并成 RootBeanDefinition？

| 原因 | 说明 |
|---|---|
| 继承合并 | `<bean parent="base">` 需要把父 BD 属性复制过来（递归） |
| 类型统一 | 注册表里是各种子类 BD，统一为 RootBeanDefinition 便于后续处理 |
| 缓存优化 | 合并昂贵，`mergedBeanDefinitions` 缓存一次 |
| 防篡改 | 返回副本，修改不影响注册表原始 BD |

---

## 9. BeanFactory vs ApplicationContext

| | BeanFactory | ApplicationContext |
|---|---|---|
| 定位 | 最底层容器契约 | BeanFactory 的超集，面向应用的完整容器 |
| 加载 | 懒加载（getBean 才创建） | **启动时实例化所有非懒加载单例**（暴露配置错误，不是"类的预加载"，类加载是 ClassLoader 的事） |
| 能力 | getBean（5 个重载）/ containsBean / getType / isTypeMatch / getAliases / getParentBeanFactory 等 | + 国际化/事件/AOP/Environment/资源加载 |
| 实现 | — | 组合模式，内部持有 beanFactory（如 AutowireCapableBeanFactory） |

---

## 10. 注解对比 & 线程安全

### @Component / @Service / @Repository / @Controller
- 基本无区别，最终都是 BeanDefinition，只是**不同技术模块的身份标记**。
- **@Repository 的特殊之处**：除了标记 Bean，还会被 `PersistenceExceptionTranslationPostProcessor` 识别，**自动把持久层原生异常（SQLException 等）转换为 Spring 统一的 `DataAccessException` 体系**，业务层不依赖具体数据库实现。

### @ResponseBody
- 加在 @Controller 方法上：返回 JSON 直接写入响应体，不走视图解析器；不加则走视图解析器渲染页面。

### @Autowired vs @Resource（复习确认 8/17）
- @Autowired：Spring 提供，默认 **byType**；**找到多个同类型时不会降级 ByName，而是抛 `NoUniqueBeanDefinitionException`**；由 AutowiredAnnotationBeanPostProcessor 处理。
- **多候选三级消歧策略（8/17 深化）**：`@Primary`（标首选）→ `@Qualifier("xxx")`（精确匹配）→ 变量名/参数名 fallback → 都失败才抛异常。
- **@Qualifier 本质**：是**独立限定符标签**，不是 beanName——注入点的 `@Qualifier("fast")` 与 BeanDefinition 上的 `@Qualifier("fast")` 标签匹配；beanName 只是**默认隐式标签**（没显式写 @Qualifier 时）。它在 ByType 候选集内做**二次过滤**，不替代 ByType。
- `@Autowired(required=false)`：找不到不抛异常，注入 null（@Resource 没有这能力）。
- @Resource：JSR-250，默认 **byName**（字段名或 name 属性）；**ByName 找不到才降级 byType**，byType 多个同样报错。
- 口诀：`@Autowired` 先看脸（Type），撞脸要指名道姓（@Qualifier）；`@Resource` 先点名（Name），点不到才看脸（Type）。

### Spring Bean 线程安全
- 默认 **singleton 单例**，所有线程共享同一个实例 → 本身是否线程安全取决于**是否有可变状态**（无状态 Bean 安全，有状态 Bean 不安全）。
- 容器**不保证**业务逻辑线程安全；ConcurrentHashMap/Caffeine 只保证**单个操作**的线程安全。
- **CHM 复合操作陷阱（复习补充 8/17）**：单次 put/get/remove 安全（JDK8 CAS+synchronized）；但 `if (!containsKey) put`（check-then-act）和 `put(k, get(k)+1)`（read-modify-write）在并发下**会丢更新/重复插入**。解决：用 `computeIfAbsent()` / `merge()` / `compute()` 原子复合方法；遍历是弱一致性（不抛 CME，但不保证最新）。

---

## 11. 手写 IoC 容器收获（今天实战）

今天实际手写了一个最小 IoC 容器并跑通：
- 自定义 `@AutoWired` 注解 + `BeanDefinition` 类 + 容器（getBean / createBean / populateBean / 三级缓存）。
- ✅ 实现了实例化 + 属性填充 + 三级缓存解决循环依赖。
- 🐛 调过 A/B/C 循环依赖报 StackOverflow 的 bug（根因：依赖直接 new 了，没走容器）；断点调试走通了 A↔B 完整流程。
- 📌 结论：手写一遍三级缓存 + 断点看流程，比背十遍八股有用得多——这是第四层面试题「你手写 IoC 容器的最大难点」的差异化弹药。

---

## 12. Spring IoC 面试题分层清单（含自评）

### 第一层：基础概念（必须秒答）
1. 什么是 IoC / DI？和直接 new 有什么区别？
2. BeanFactory 和 ApplicationContext 的区别？
3. @Component / @Service / @Repository / @Controller 有区别吗？
4. @Autowired 和 @Resource 的区别？
5. Spring Bean 是线程安全的吗？

### 第二层：生命周期 & 创建流程
6. Spring Bean 的完整生命周期？（实例化到销毁）
7. BeanPostProcessor 的作用和执行时机？before/after 分别干什么？
8. @PostConstruct、InitializingBean、init-method 的执行顺序？
9. Spring 如何解决循环依赖？三级缓存分别存什么？
10. 为什么需要三级缓存而不是两级？二级缓存行不行？
11. AOP 代理是在生命周期的哪一步创建的？为什么？

### 第三层：源码级理解（区分度最高）
12. doCreateBean 的四步主干是什么？每步做了什么？
13. BeanDefinition 是怎么来的？XML/注解/JavaConfig 最终汇聚到哪里？
14. @Autowired 的注入链路？从 populateBean 到实际赋值经过哪些类？
15. Spring 怎么决定用反射还是 CGLIB 创建实例？
16. getMergedLocalBeanDefinition 做了什么？
17. @Async / @Transactional / @Cacheable 的共同底层机制？（BPP + 代理）

### 第四层：场景题 & 坑题
18. 构造器注入的循环依赖能解决吗？为什么？
19. prototype Bean 注入到 singleton Bean 里会怎样？怎么解决？
20. 同一个类里 @Transactional 方法互相调用为什么会失效？
21. Spring 启动慢怎么排查和优化？
22. 你手写过 IoC 容器，遇到的最大难点是什么？怎么解决的？← 杀手锏

### 自评（8/17 下午更新）
- 第一层 ✅、第二层 ✅（口述过关）。
- 第三层 ✅ **已复习**：doCreateBean / BeanDefinition 三条路径 / @Autowired 注入链路（InjectionMetadataCache 复用）/ getMergedLocalBeanDefinition / @Async/@Transactional/@Cacheable 机制全过了一遍；反射 ✅、**CGLIB ✅（AOP 专题已补齐 8/17）**。
- 第四层：18 构造器循环依赖 ✅、19 prototype 注入 singleton 🟡（复习过但**答错一次**，见 §14）、20 @Transactional 自调用 ✅（会 + 3 解法）、21 启动慢 🟡（学过细节，需默写）、22 手写容器 ✅（杀手锏）。

---

## 13. 待补 / 下一步

- [x] 第三层细学：@Autowired 注入链路 — ✅ 8/17 已复习确认
- [x] 第三层细学：**CGLIB**（什么时候用、实例化阶段 vs AOP 代理阶段、final 限制）— ✅ AOP 专题已补齐（8/17）
- [ ] 第四层 19：prototype 注入 singleton — 🟡 复习过但**曾答错**，需重默写正确版
- [x] 第四层 20：@Transactional 自调用失效 — ✅ 8/17 会（3 解法），进 AOP 专题深化
- [ ] 第四层 21：Spring 启动慢排查 — 🟡 学过细节，需默写完整步骤
- [x] **AOP 专题**：JDK 动态代理 vs CGLIB、切面织入 — ✅ 8/17 下午已学，产出《AOP-学习笔记-2026-08-17.md》
- [ ] Spring Boot 自动装配（总计划 §1.5 短板 5）

---

## 14. 复习记录

### 8/17 早间复习（口述 + AI 纠错）

**本次复习确认已掌握：** ① IoC/DI 概念；② BeanFactory vs ApplicationContext；③ 生命周期口述；④ 三级缓存完整流程（含"为什么三级"）；⑤ ObjectFactory lambda 触发机制。

**纠错/深化（已更新到上文对应小节）：**
1. `@Autowired` 多候选**不降级 ByName**，抛 `NoUniqueBeanDefinitionException`；`@Resource` 才降级（ByName → ByType）。
2. `@Repository` 独有：`PersistenceExceptionTranslationPostProcessor` 做持久层异常转换。
3. BeanFactory 接口不止 getBean/containsBean，还有 getType/isTypeMatch/getAliases/getParentBeanFactory。
4. ApplicationContext 是"启动时实例化非懒加载单例"，不是"类的预加载"。
5. CHM 单操作安全、复合操作（check-then-act / read-modify-write）不安全 → 用 compute 系列。
6. Aware 分三类，容器能力感知（ApplicationContextAware 等）日常常用；全部在 BPP 之前。
7. BPP 前置是责任链：ApplicationContextAwareProcessor → CommonAnnotationBeanPostProcessor（@PostConstruct）→ 自定义。
8. AOP 代理表述修正：不是"循环依赖时在属性填充阶段生成"，而是"三级缓存工厂回调提前调用 AbstractAutoProxyCreator 的代理创建逻辑"。
9. Lambda 触发链路：getSingleton → `factory.getObject()`（同步块内原子：取出→执行→升二级→删三级）→ getEarlyBeanReference → wrapIfNecessary（遍历 Advisor 匹配 Pointcut）。
10. 三级缓存的根本设计原因：放缓存时机信息不完备（Advisor 可能未注册完），延迟到索取时刻决策才可靠。
11. 构造器循环依赖抛 `BeanCurrentlyInCreationException`，解决用 @Lazy 或 setter/字段注入。

### 8/17 上午（第一二层复习 + 第三层初步学）

**复习确认：** 第一层（IoC/DI、BeanFactory vs ApplicationContext、注解对比、线程安全）✅、第二层（生命周期、BPP、初始化顺序、三级缓存）✅ 已能脱稿口述。

**第三层初步学（已归档到 §6/§7/§8/§10）：**
1. doCreateBean 完整源码：四步 + 早期暴露 + exposedObject 校验（含 BeanCurrentlyInCreationException 的"raw version 注入"场景）+ 注册销毁。
2. BeanDefinition 三条路径具体类：XmlBeanDefinitionReader / ClassPathBeanDefinitionScanner / ConfigurationClassPostProcessor → 统一汇聚 beanDefinitionMap。
3. @Autowired 多候选三级消歧：@Primary → @Qualifier → 变量名 fallback → 抛异常；@Qualifier 是独立标签不是 beanName。
4. @Autowired(required=false) 找不到注入 null。
5. Aware 注入机制：invokeAwareMethods 硬编码 setter + ApplicationContextAwareProcessor（BPP）处理高层 Aware。
6. @Async/@Transactional/@Cacheable 共用 BPP+代理管道，差异只在 Advisor/Interceptor。
7. getMergedLocalBeanDefinition：父子合并、类型统一、缓存、防篡改。

**⚠️ AI 幻觉警示（本次对话踩坑，务必警惕）：**
- 本次对话的 AI 关于 **@PostConstruct 位置**出现了自相矛盾且错误的说法：先列时序图「BPP Before → afterPropertiesSet → @PostConstruct」，又声称「@PostConstruct 不是 BPP Before 处理的、在 Init 阶段」「正确顺序是 InitializingBean → @PostConstruct」。
- **正确版本（Spring 源码事实，8/16 已确认过）**：`@PostConstruct` 由 `CommonAnnotationBeanPostProcessor` 在 **BPP 前置阶段**（postProcessBeforeInitialization）触发，执行顺序 **@PostConstruct → afterPropertiesSet → init-method**。口诀是「注解（PostConstruct）→ 接口（InitializingBean）→ 配置（init-method）」，不是"接口优先于注解"。
- **教训**：同一主题跨会话时 AI 可能给出互相矛盾的说法；遇到关键事实冲突，以官方文档/源码/demo 实测为准，并记录下来。这也是复习时把本笔记贴回去对齐的原因。

**下一步：** 按 §13，第三层剩 2 项没细学；第四层还剩 19/20/21 三道场景题。20 题（@Transactional 自调用失效）衔接 AOP 专题。

### 8/17 下午（第三层全过 + 第四层大部分）

**第三层复习通过：** doCreateBean / BeanDefinition 三条路径 / @Autowired 注入链路（InjectionMetadataCache 复用）/ getMergedLocalBeanDefinition / @Async/@Transactional/@Cacheable 机制。反射 ✅；**CGLIB ✅（8/17 AOP 专题补齐）**。

**第四层：**
- 18 构造器循环依赖 ✅：`singletonsCurrentlyInCreation` 里有 A/B 但没有引用（构造器没执行完，无法放三级缓存），三级缓存机制根本不适配。
- 20 @Transactional 自调用 ✅：`this.method()` 走原始对象不走代理 → 失效；3 解法 = `@Autowired` 注入自身 / `AopContext.currentProxy()`（需 exposeProxy=true）/ 拆两个 Service。
- 21 启动慢 🟡：Startup Actuator + Arthas 细节学过需默写。

**⚠️ 致命纠错（这次答错的点，面试常考，务必记住）：**
> **"Prototype 注入 Singleton 会降级为 Singleton" —— 完全错误！**
- **正确版**：Prototype 注入 Singleton 后**仍然是 Prototype**，但 Singleton 只创建一次构造依赖，导致 Prototype **永不重建**，表现为"看起来像单例"——本质是**作用域失效**，不是降级。
- **解法**：① `ObjectProvider<PrototypeBean>` + `.getObject()`（推荐）；② `@Lookup` 方法（CGLIB 子类重写每次新实例）；③ `ApplicationContext.getBean()`（侵入容器 API，不推荐）。

**其他校准：**
- AOP **注解 ≠ 切点**：`@Aspect` 类=切面、`@Pointcut` 方法=匹配规则、`@Before/@Around`=通知；自定义注解驱动的绑定方式是切点里用 **`@annotation(注解全限定名)`**。
- Startup Actuator 是 **POST** 不是 GET；启动优化用 **GraalVM Native Image (AOT)**（不是 GradVM），虚拟线程优化的是运行时并发不是启动速度；`@Lazy` 注意首次访问延迟转移。

---

*本笔记覆盖 Spring IoC 全部学习内容（8/16 主学 + 8/17 两次复习/第三层初步学）。后续复习：把 §5、§12 贴回对话让 AI 考口述，24h/48h 默写三级缓存名字 + 流转图。**遇到 AI 说法与本笔记冲突，以本笔记（源码为准）为准。** *
