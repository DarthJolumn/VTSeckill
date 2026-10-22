# 短链系统（vtsl-shortlink）学习文档

> 面向：想理解并讲清楚 `vtsl-shortlink` 两个核心子模块（keygenerator / api）实现的人。
> 配套代码：`vt-seckill-shortlink/vtsl-shortlink/`
> 阅读顺序建议：阶段 0 → 阶段 5，循序渐进，别一上来读全部源码。

---

## 0. 先跑起来（建立体感，最重要）

光看代码记不住。先让服务跑通、curl 通，再回头看实现才有锚点。

### 0.1 依赖
各 `application.yml` 里写死了环境 IP（默认 `192.168.147.134`），本地按自己环境改：
- Redis（短码队列 + L2 缓存）
- MongoDB（短码库存 `shortkeys` 集合）
- MySQL（业务映射表 `url`）
- Nacos（服务发现，api 经网关才需要；直连 api:8085 不需要）

### 0.2 启动
- `vtsl-shortlink-keygenerator`（端口 8082，gRPC 50051）
- `vtsl-shortlink-api`（端口 8085）

### 0.3 跑通一条链路（直连 api，绕过 JWT）
`UserContextFilter` 直接读 `X-User-Id` 请求头，本地 curl 加这个头即可当登录：

```bash
# 创建短链
curl -X POST http://localhost:8085/api/v1/url/shorten \
  -H "X-User-Id: 1" -H "Content-Type: application/json" \
  -d '{"originalUrl":"https://example.com/very/long/url","title":"测试"}'
# 记下返回的 shortKey，假设 "aB3xZ9"

# 跳转（公开接口，免登录）
curl "http://localhost:8085/api/v1/url/redirect/aB3xZ9"

# 列表 / 详情 / 统计
curl "http://localhost:8085/api/v1/url/" -H "X-User-Id: 1"
curl "http://localhost:8085/api/v1/url/aB3xZ9" -H "X-User-Id: 1"
curl "http://localhost:8085/api/v1/analytics/1" -H "X-User-Id: 1"

# KGS 健康检查
curl "http://localhost:8082/api/v1/health"
```

### 0.4 看数据（比看代码直观）
```bash
redis-cli LLEN shortly-kgs-redis-queue        # 队列长度，阈值 200 触发补货
mongosh livemall_shortlink --eval "db.shortkeys.countDocuments({status:'available'})"  # 库存
```
观察 `shorten` 之后 Redis 队列减少、`shortkeys` 里对应码变 `used`。

---

## 1. 模块分工（双服务架构）

| 角色 | 模块 | 端口 | 关键类 |
|---|---|---|---|
| 发号器 KGS | `vtsl-shortlink-keygenerator` | 8082 / gRPC 50051 | `KeyGenerator` `KeyService` `KeyGrpcService` |
| 业务层 API | `vtsl-shortlink-api` | 8085 | `UrlController` `UrlService` `KeyServiceClient` |

两者**完全解耦**：API 不知道短码怎么生成，只在建链时向 KGS「管你要一个」。通信走 gRPC（`KeyServiceClient.getKey()` → `KeyGrpcService.getKey()`，2s 超时）。

存储选型分离：
- **MongoDB** `shortkeys`：短码全局库存，唯一索引保证不重复，CAS 状态机（`available`/`used`）。
- **MySQL** `url`：业务映射（归属 userId、originalUrl、标题、点击数），JPA 访问。

---

## 2. 一条 shorten 请求的生命周期（重点）

```
POST /api/v1/url/shorten
  UrlService.create()
    ├─ 查重 existsByOriginalUrlAndUserIdAndDeletedAtIsNull   (MySQL) 防同用户重复缩短
    ├─ shortKey 为空 → keyServiceClient.getKey()  ──gRPC──┐
    │                                                     │
    │   KGS.getKey()：                                    │
    │     ├─ Redis LLEN 队列 < 200(QUEUE_THRESHOLD)?      │
    │     │   是 → KeyGenerator.generateBatch(1000)        │
    │     │        ├─ Base62 随机 6 位 (KEY_LENGTH=6)      │
    │     │        ├─ Mongo insert shortkeys[available]   │
    │     │        └─ Redis LPUSH 批量入队                 │
    │     ├─ Redis RPOP 取一个 (O(1) 无竞争)               │
    │     └─ Mongo CAS: available→used (双保险)            │
    │                                                     │
    ├─ existsByShortKey   (Base62 6位≈568亿空间，几乎不撞)
    ├─ MySQL save(Url)
    └─ 写二级缓存 Caffeine(3s) + Redis(24h)
```

**为什么「不阻塞创建链路」**：KGS 是**预生成**——阈值触发时提前把短码灌进 Redis 队列，API 取号只是一次 `RPOP`，毫秒级，无需现场算码。

---

## 3. 控制层（对外能力）

文件：`vtsl-shortlink-api/.../controller/UrlController.java`

| 方法 | 路径 | 鉴权 | 限流 | 说明 |
|---|---|---|---|---|
| POST | `/api/v1/url/shorten` | `@RequireAuth` | 5/60s | 创建短链（核心） |
| GET | `/api/v1/url/` | `@RequireAuth` | 20/60s | 我的短链列表 |
| GET | `/api/v1/url/{shortKey}` | `@RequireAuth` | 20/60s | 详情 |
| PATCH | `/api/v1/url/{shortKey}` | `@RequireAuth` | 5/60s | 改短码/标题 |
| DELETE | `/api/v1/url/{shortKey}` | `@RequireAuth` | 5/60s | 软删除 |
| GET | `/api/v1/url/redirect/{shortKey}` | `@PublicApi` | 50/60s | 跳转 + 记 analytics |
| GET | `/api/v1/analytics/{urlId}` | `@RequireAuth` | 10/60s | 点击统计 |
| GET | `/api/v1/health`(8082) | 公开 | — | KGS 健康 |

思考：为什么 redirect 公开、其余要登录？→ 跳转是给别人点的，创建是 owner 自己的。

---

## 4. API 业务层（核心难点）

文件：`vtsl-shortlink-api/.../service/UrlService.java`

三块重点：
1. **`create()` 取号**（UrlService.java:60-68）：shortKey 为空才向 KGS 要；用户自定义 shortKey 走 `existsByShortKey` 校验。
2. **二级缓存**（`getFromCache` / `cacheUrl`）：
   - L1 Caffeine 3s + L2 Redis 24h。
   - 缓存值格式 `"{urlId}|{originalUrl}"`，命中即返回且能直接记 analytics，**免回查 DB**。
3. **`redirect()`**（UrlService.java:130）：先查缓存，未命中回源 MySQL 再回填；命中且有 urlId 才异步记 analytics。

---

## 5. KGS 发号器（简历亮点）

### 5.1 常量集中地
`KgsConstants.java`：`QUEUE_THRESHOLD=200`、`BATCH_SIZE=1000`、`KEY_LENGTH=6`、`MAX_GENERATE_ATTEMPTS=3`、`MAX_ISSUE_ATTEMPTS=3`、`STATUS_AVAILABLE/USED`。

### 5.2 随机码生成
`Base62Util.java`：用 `SecureRandom` 生成 Base62（62 字符集）6 位短码，随机码防枚举。

### 5.3 预生成
`KeyGenerator.generateBatch()`：批量生成 → Mongo insert(`available`) → Redis `LPUSH` 入库。
> ⚠️ 顺序隐患：先 Mongo 后 Redis。若 Mongo 成功、Redis 故障，这批码永久 `available` 却不在队列（孤儿库存，不破坏唯一性但泄漏）。面试被问「Redis 写失败怎么办」要能答。

### 5.4 发放（最关键）
`KeyService.getKey()`：
- Redis `LLEN` 低于阈值 → 触发 `generateBatch` 补货。
- `RPOP` 取一个（O(1) 无竞争）。
- Mongo `CAS`：`available→used`，`modifiedCount>0` 才返回。
- CAS 失败（脏 key，已是 used）→ **直接丢弃取下一个**，不回队，避免「取出→失败→回队」死循环。
- 最多 `MAX_ISSUE_ATTEMPTS=3` 次，仍失败抛异常 → gRPC 返回 INTERNAL。

### 5.5 gRPC 入口
`KeyGrpcService.getKey()`：包 try-catch，异常转 `Status.INTERNAL` 返回，不让 gRPC 线程炸。

---

## 6. 横切（理解为什么能跑）

- `UserContextFilter.java` + `UserContext.java`：Gateway 注入 `X-User-Id` 头（或本地直连自己带），Filter 读头绑定到 `ScopedValue`，整请求链路内 `currentUserId()` 可用。WebFlux 网关不含此类（无 Servlet API）。
- `AuthInterceptor` + `@RequireAuth` / `@PublicApi`：Method 级鉴权注解，拦截器生效。
- Gateway `application.yml`：路由 `Path=/api/v1/**` → `vtsl-shortlink-api`；`public-get-paths` 含 `/api/v1/url/redirect/**`（仅 GET 免 JWT）。
- `ShortCodeValidationFilter`：网关前置 Base62 格式正则校验，无效短码直接 400，不进业务。

---

## 7. 面试自问自答（学完逼自己答）

- **短码会重复吗？** Base62 6 位空间 ≈ 568 亿；Mongo `shortkeys.key` 唯一索引兜底；CAS 发放保证同一码只发一次。
- **创建链路为什么快？** 取号是 `RPOP` O(1)，短码早已预生成入队，无现场算码/无锁竞争。
- **Redis 队列空了 / 写失败怎么办？** 阈值触发同步补货；Mongo 先写后 Redis 的顺序隐患见 5.3；发放端 `MAX_ISSUE_ATTEMPTS` 兜底。
- **二级缓存怎么防雪崩？** TTL 固定 24h（可补「随机抖动」更稳）；L1 3s 拦截热点消除网络 IO。
- **缓存命中怎么记统计还不回查 DB？** 缓存值带 `urlId`，命中即异步 `record(urlId, ...)`。
- **gRPC 调用挂了？** `KeyServiceClient` 2s 超时 → `create()` 捕获抛 500「生成短码失败」，不影响其他链路。

---

## 8. 高效学习法

- **改→测→看**：把 `QUEUE_THRESHOLD` 调很小，观察是否频繁打 `generateBatch` 日志；比死读理解深。
- **画时序图**：把 shorten 一次请求经过的服务/存储标出来，就是简历里的架构图。
- **写自己的话**：笔记写「为什么这样设计」，别抄代码。
