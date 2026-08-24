package com.jolumn.vtslseckill.biz.mq;

import com.jolumn.vtslseckill.model.enums.SendOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Kafka 订单消息发送器（纯发送，不关心降级决策）。
 *
 * <p>职责单一：只负责"把订单消息发给 Kafka"并返回结果语义。
 * <ul>
 *   <li>{@link #sendSync}：同步发送，send().get(timeoutMs) 等 broker ack → CONFIRMED / TRANSIENT / FATAL</li>
 *   <li>{@link #sendAsyncReliable}：异步可靠发送（pending 标记 + 回调 + 补偿队列），
 *       返回 ACCEPTED（受理，送达由回调/补偿保证）</li>
 * </ul>
 *
 * <p>超时值由调用方动态传入（{@code SyncDegradeController.safeTimeoutMs()}），打破固定时限。
 * 耗时用 {@code nanoTime()} 掐真实等待（含超时实际耗时），不用 {@code timeoutMs} 直接套，
 * 避免系统调度误差放大 P99/σ 统计。</p>
 *
 * <p>降级决策（窗口/采样/恢复）在 {@code SyncDegradeController}，不在此类——避免职责交叉。</p>
 */
@Component
public class KafkaOrderSender {

    private static final Logger log = LoggerFactory.getLogger(KafkaOrderSender.class);
    private static final String PENDING_KEY_PREFIX = "pending:";
    private static final String COMPENSATE_QUEUE = "compensate_queue";

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final StringRedisTemplate redisTemplate;

    private final long pendingTtlSeconds;

    public KafkaOrderSender(KafkaTemplate<String, String> kafkaTemplate,
                            StringRedisTemplate redisTemplate,
                            @Value("${seckill.async.pending-ttl-seconds:300}") long pendingTtlSeconds) {
        this.kafkaTemplate = kafkaTemplate;
        this.redisTemplate = redisTemplate;
        this.pendingTtlSeconds = pendingTtlSeconds;
    }

    /**
     * 同步发送：send().get(timeoutMs) 等 broker ack。
     *
     * @param timeoutMs 动态超时（由降级决策器计算）
     * @return CONFIRMED（broker ack）/ TRANSIENT（超时或中断）/ FATAL（发送异常）
     *         实际耗时(ms)一并返回，超时样本记录的是真实等待时长（非 timeoutMs 常量）
     */
    public SendSyncResult sendSync(String msg, long timeoutMs) {
        long start = System.nanoTime();
        try {
            kafkaTemplate.send("seckill-order", msg)
                    .get(timeoutMs, TimeUnit.MILLISECONDS);
            return new SendSyncResult(SendOutcome.CONFIRMED, elapsedMs(start));
        } catch (TimeoutException e) {
            log.warn("Kafka 同步发送超时: msg={}, timeoutMs={}", msg, timeoutMs);
            return new SendSyncResult(SendOutcome.TRANSIENT, elapsedMs(start));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Kafka 同步发送中断: msg={}", msg);
            return new SendSyncResult(SendOutcome.TRANSIENT, elapsedMs(start));
        } catch (ExecutionException e) {
            log.warn("Kafka 同步发送异常: msg={}, cause={}",
                    msg, e.getCause() == null ? e.getMessage() : e.getCause().getMessage());
            return new SendSyncResult(SendOutcome.FATAL, elapsedMs(start));
        }
    }

    private long elapsedMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    /** 同步发送结果：outcome + 实际耗时(ms)。耗时是降级判定的核心输入 */
    public record SendSyncResult(SendOutcome outcome, long elapsedMs) {}

    /**
     * 异步可靠发送：fire-and-forget + pending 标记 + 回调 + 补偿队列。
     * 返回 ACCEPTED（受理）。送达由回调/补偿保证：
     * <ul>
     *   <li>回调成功 → 删 pending</li>
     *   <li>回调失败 → 保留 pending + 进补偿队列（PendingOrderScanner/CompensateQueueConsumer 兜底）</li>
     *   <li>JVM 崩溃 → pending 残留 → PendingOrderScanner 定时补投</li>
     * </ul>
     */
    public SendOutcome sendAsyncReliable(String msg, String orderNo) {
        // ① 先落 pending 标记（防丢）；Redis 异常降级放行（可靠性交给对账）
        String pendingKey = PENDING_KEY_PREFIX + orderNo;
        try {
            redisTemplate.opsForValue().set(pendingKey, msg, Duration.ofSeconds(pendingTtlSeconds));
        } catch (Exception e) {
            log.warn("pending 标记写入失败（降级放行）: orderNo={}, err={}", orderNo, e.getMessage());
        }

        // ② 异步发送 + 回调（回调由 Kafka I/O 线程触发，不阻塞请求线程）
        try {
            CompletableFuture<SendResult<String, String>> future =
                    kafkaTemplate.send("seckill-order", msg);
            future.whenComplete((result, failure) -> {
                if (failure == null) {
                    try {
                        redisTemplate.delete(pendingKey);
                    } catch (Exception e) {
                        log.warn("pending 删除失败（残留由定时补投扫描，幂等兜底）: orderNo={}", orderNo, e.getMessage());
                    }
                    log.info("Kafka 异步发送成功: orderNo={}", orderNo);
                } else {
                    // 保留 pending（TTL 内定时任务补投），进补偿队列加速重发
                    try {
                        redisTemplate.opsForList().leftPush(COMPENSATE_QUEUE, msg);
                    } catch (Exception e) {
                        log.warn("补偿队列写入失败: orderNo={}, err={}", orderNo, e.getMessage());
                    }
                    log.error("Kafka 异步发送失败（已进补偿队列）: orderNo={}, err={}",
                            orderNo, failure.getMessage());
                }
            });
        } catch (Exception e) {
            // send() 本身抛异常（producer 关闭等）：pending 保留，由定时任务补投
            log.error("Kafka send() 调用失败（pending 保留待补投）: orderNo={}, err={}",
                    orderNo, e.getMessage());
        }
        // 受理即返回（~1ms），不等待送达
        return SendOutcome.ACCEPTED;
    }
}
