# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

LiveMall — 高并发直播电商秒杀系统，目标 10w QPS，秋招项目。后端 Spring Boot 3.2.7 + Java 21 (Virtual Threads)，前端 Vue 3 + Vite。

## Repository Layout

```
LiveMallBk/                   # 后端 Maven 多模块父工程
├── livemall-common/          # 公共模块：DTO、异常、JWT、幂等、Snowflake、DFA
├── livemall-user/            # 用户服务：注册/登录/登出/设备管理 (port 8081)
├── livemall-seckill/         # 秒杀服务：Redis Lua 库存扣减、订单
├── livemall-websocket/       # WebSocket 长连接：直播弹幕、心跳、踢人
├── livemall-leaderboard/     # 排行榜服务：Redis ZSet 实时排名
├── livemall-gateway/         # 网关：Spring Cloud Gateway (WebFlux)，路由/JWT/签名/限流/CORS (port 8080)
└── docker/                   # Docker Compose（MySQL、Redis、Nacos）

livemall-ui-vue/              # 前端 Vue 3 SPA
md/docs/                      # 设计文档（见下方文档导航）
```

## Tech Stack

| 层 | 技术 |
|---|---|
| 语言 | Java 21 (Virtual Threads `--enable-preview`) |
| 框架 | Spring Boot 3.2.7, Spring Cloud 2023.0.3, Spring Cloud Alibaba 2023.0.1.2 |
| RPC | Dubbo 3.3.0 (协议端口 2088x，注册中心 Nacos) |
| 数据库 | MySQL + MyBatis-Plus 3.x (无 XML mapper，纯注解) |
| 缓存 | Redis (Lettuce) |
| 消息 | Kafka 3.6 (KRaft，暂时注释未启用) |
| 网关 | Spring Cloud Gateway (WebFlux reactive) |
| 限流 | Sentinel 1.8.8 |
| 可观测 | Micrometer Tracing + Zipkin + Prometheus |
| 工具 | Hutool 5.8.28, Lombok, jBCrypt 0.4 |
| 前端 | Vue 3.4 + Vite 5 + Pinia + Vue Router 4 + Axios |

## Commands

### Backend (Maven, from `LiveMallBk/`)

```bash
# 编译全部模块
mvn clean compile -pl livemall-common,livemall-user,livemall-gateway

# 运行单个服务（指定模块）
mvn spring-boot:run -pl livemall-user
mvn spring-boot:run -pl livemall-gateway

# 运行单个测试类
mvn test -pl livemall-user -Dtest=AuthControllerTest

# 打包全部（跳过测试）
mvn clean package -DskipTests
```

### Frontend (from `livemall-ui-vue/`)

```bash
npm install                # 安装依赖
npm run dev                # 启动开发服务器（Vite）
npm run build              # 生产构建
npm run lint               # ESLint
```

### Docker (from `LiveMallBk/docker/`)

```bash
docker compose up -d                      # 启动全部中间件
docker compose up -d mysql redis nacos    # 仅核心
```

依赖中间件：MySQL (`root/root123`)、Redis (密码 `redis123`)、Nacos (`standalone`，无鉴权)。服务地址默认 `192.168.147.132`，可通过环境变量覆盖：`MYSQL_HOST`、`REDIS_HOST`、`NACOS_ADDR`。

## Architecture Patterns

### 统一返回格式
所有接口返回 `Result<T>`（`com.jolumn.livemallcommon.dto.Result`），结构：`{ code: int, message: string, data: T, timestamp: long }`。`code=200` 表示成功。

### 异常体系
- `BizException(code, message)` — 业务异常，由 `GlobalExceptionHandler` (`@RestControllerAdvice`) 统一捕获转为 `Result.error()`
- 参数校验异常 (`MethodArgumentNotValidException`)、缺少必填头 (`MissingRequestHeaderException`) 等均有统一处理

### 认证体系 (双 Token)
- **Access Token**: JWT（HS256），有效期 15 分钟，存 sessionStorage
- **Refresh Token**: `rft_` + UUID，存 Redis（7 天），key 为 `refresh:{token}`，value 为 `userId:role:deviceId`
- 登出时删除 Redis 中对应的 refresh token 和 device session
- 前端 401 自动触发 refresh，并发刷新合并（同一时刻只发一个 refresh 请求）

### 幂等性
`IdempotencyService` 基于 Redis `SETNX`，key 前缀 `idempotency:`，TTL 5 分钟。用于注册、登录等写接口，客户端传 `X-Idempotency-Key` 头。

### 设备管理
客户端生成 UUID v4 存 localStorage，每次请求通过 `X-Device-Id` 头传递。登录时写入 Redis Set `device_sessions:{userId}`，登出时移除。

### Dubbo RPC
各服务通过 Dubbo 暴露接口（`com.jolumn.livemalluser.service` 包自动扫描），注册中心为 Nacos（`nacos://addr?registry-type=service`）。consumer 端 `check: false`，允许 provider 未就绪时启动。

### Gateway 路由
所有外部请求经 Gateway (8080) 按路径前缀路由：
- `/auth/**`, `/user/**` → `livemall-user` (lb)
- `/seckill/**` → `livemall-seckill`
- `/leaderboard/**` → `livemall-leaderboard`
- `/ws/**` → `livemall-websocket` (lb:ws)

Gateway 是唯一 WebFlux 服务（`web-application-type: reactive`），强制排除 spring-boot-starter-web。

### 前端角色路由
三种角色入口：`/` (audience)、`/anchor` (anchor/admin)、`/admin` (admin)。路由守卫 `guards.js` 根据 `useUserStore.role` 校验。

## Design Documents

编码前必读对应模块设计文档（`md/docs/` 下按编号分层）：

| 编号 | 内容 |
|---|---|
| 1.x | 架构设计（项目概览、技术选型、功能清单、约束、性能目标、安全） |
| 2.x | 模块详细设计（公共模块、用户服务、秒杀、WebSocket、排行榜、网关、DB、接口） |
| 3.x | 功能实现细节（单功能 I/O、流程、边界） |

关键约定：
- 统一返回 `Result<T>`，用 Hutool、Lombok `@Data` 用于 VO/DTO
- 全局异常处理 `@RestControllerAdvice`
- 设计文档间的依赖关系见 `md/docs/文档地图.md`
