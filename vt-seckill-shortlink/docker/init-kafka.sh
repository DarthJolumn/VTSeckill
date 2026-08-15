#!/usr/bin/env bash
# =====================================================================
# Kafka 集群初始化：显式创建业务 topic（AUTO_CREATE_TOPICS 已关闭）
#
# ⚠️ 宿主机是 Windows：本脚本必须【在 Kafka 容器内】执行（kafka-topics.sh
#    只在容器里有；宿主机无 bash 也不能直接 sh 运行）。
#
# 用法（PowerShell，在 vt-seckill-shortlink 根目录）：
#   docker compose -f docker/docker-compose-kafka.yml up -d          # 1. 先起集群
#   docker cp docker/init-kafka.sh seckill-kafka1:/tmp/init-kafka.sh  # 2. 拷进容器
#   docker exec seckill-kafka1 bash /tmp/init-kafka.sh               # 3. 容器内执行
#
# 或不用脚本，直接 docker exec 单条命令（效果相同，见文件尾部注释）
# =====================================================================
set -euo pipefail

# 容器内执行：seckill-kafka1:9092 走 INTERNAL listener（容器网络，容器名可达）
BOOTSTRAP="${BOOTSTRAP_SERVER:-seckill-kafka1:9092}"
TOPICS_SCRIPT="/opt/kafka/bin/kafka-topics.sh"

echo "==> Kafka 集群: $BOOTSTRAP"

# 秒杀订单 topic：6 partition × RF 3
#   - partition=6 ≥ 消费并发(concurrency=4)，4 个消费线程瓜分 6 分区并行消费
#   - RF=3：3 broker 各持一份副本，单 broker 宕机不丢（producer acks=all 等全部 ISR）
#   - 顺序性：DBQueueStrategy 用 key=activityId，同 key 恒进同 partition，与分区数无关
$TOPICS_SCRIPT --bootstrap-server "$BOOTSTRAP" --create \
    --if-not-exists \
    --topic seckill-order \
    --partitions 6 \
    --replication-factor 3 \
    --config cleanup.policy=delete \
    --config retention.ms=86400000

# 注：ws:push:seckill-result 是 Redis Pub/Sub channel（SeckillOrderConsumer 用
# redisTemplate.convertAndSend），不是 Kafka topic，无需在此创建。

echo "==> 已创建 topics:"
$TOPICS_SCRIPT --bootstrap-server "$BOOTSTRAP" --list
