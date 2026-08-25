# 压测概览

## 环境

| 项目 | 规格 |
|------|------|
| 应用服务 | 8vCPU / 16GB RAM |
| 中间件 VM | 192.168.147.132 (8vCPU/16GB): Redis + MySQL + Nacos + Kafka + Sentinel |
| JDK | OpenJDK 25.0.3 |
| 框架 | Spring Boot 4.1.0 |
| 压测工具 | Apache JMeter |
| 场景 | 秒杀扣减接口 `POST /seckill/order` |
| 样本量 | 每轮 100,000 请求 |
| 指标采集 | JMeter 聚合报告 + jstat GC 统计 |

## 对比维度

| 文档 | 对比内容 | 核心结论 |
|------|---------|---------|
| [01-vt-vs-platform.md](01-vt-vs-platform.md) | 虚拟线程 vs 平台线程 | 600 并发下 VT TPS +7.6%, P99 -23% |
| [02-g1-vs-zgc.md](02-g1-vs-zgc.md) | G1 vs 分代 ZGC | ZGC GC 总耗时 G1 的 1/285，零 STW |
| [03-async-shave.md](03-async-shave.md) | Kafka 异步削峰 + Redis 本地化 | P99 从 264ms 降至 87ms，Max 从 3342ms 降至 139ms |

## 原始数据

原始 CSV 数据存放于 `../raw-data/` 目录：
- `vt-vs-platform/` — 线程模型对比 CSV
- `g1/` — G1 各阶段压测数据
- `zgc/` — ZGC 各阶段压测数据
