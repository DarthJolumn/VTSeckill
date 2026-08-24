package com.jolumn.vtslseckill.biz.service.strategy;

import com.jolumn.vtslseckill.biz.mq.KafkaOrderSender;
import com.jolumn.vtslseckill.biz.mq.SyncDegradeController;
import com.jolumn.vtslseckill.biz.service.StockService;
import com.jolumn.vtslseckill.model.entity.SeckillActivity;
import com.jolumn.vtslseckill.model.enums.SendOutcome;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Redis lua 原子扣库存 + Kafka 同步发送（REDIS_SYNC，强一致性）。
 * 超高流量或 Broker 抖动时 get 等待成为瓶颈 → 自适应降级为异步受理。
 *
 * <p>职责（薄外观）：只做"模式判断 + 委托"，决策在 {@link SyncDegradeController}，
 * 发送在 {@link KafkaOrderSender}。</p>
 *
 * <p>失败处理语义（重要）：<b>单次失败 ≠ 降级</b>。同步模式单次 Kafka 发送失败
 * 返回 TRANSIENT/FATAL → 上层 placeOrder 统一幂等回补库存 + 503（Fail Fast），
 * 不做应用层重试（producer 内部 retries:3 已消化网络瞬态）；降级是滑动窗口持续
 * 超阈值后的整体决策，降级后异步发送走可靠通道（pending+回调+补投），不裸发。</p>
 */
@Component
public class RedisSyncStrategy implements SeckillStrategy {

    private static final Logger log = LoggerFactory.getLogger(RedisSyncStrategy.class);

    private final StockService stockService;
    private final KafkaOrderSender sender;
    private final SyncDegradeController degrade;

    public RedisSyncStrategy(StockService stockService,
                             KafkaOrderSender sender,
                             SyncDegradeController degrade) {
        this.stockService = stockService;
        this.sender = sender;
        this.degrade = degrade;
    }

    @Override
    public int deductStock(Long activityId, Long userId) {
        return stockService.deduct(activityId, userId);
    }

    @Override
    public SendOutcome createOrder(SeckillActivity activity, Long userId, String orderNo) {
        String msg = userId + ":" + activity.getId() + ":" + orderNo;
        if (degrade.isSyncMode()) {
            // 同步模式：动态超时发送（返回 outcome+真实耗时）→ 窗口记录真实耗时（成功也记，P99 才反映真实等待）
            long timeout = degrade.safeTimeoutMs();
            KafkaOrderSender.SendSyncResult r = sender.sendSync(msg, timeout);
            degrade.record(r.elapsedMs(), r.outcome() != SendOutcome.CONFIRMED);
            return r.outcome();   // 失败由 placeOrder 统一回补
        }
        // 降级模式（REDIS_ASYNC）：5% 探针仍走同步感知恢复，其余可靠异步受理
        if (degrade.shouldSample()) {
            long timeout = degrade.safeTimeoutMs();
            KafkaOrderSender.SendSyncResult probe = sender.sendSync(msg, timeout);
            // 探针结果也进窗口（全量统计，含超时），供恢复判定；同时可靠异步发送防丢
            degrade.record(probe.elapsedMs(), probe.outcome() != SendOutcome.CONFIRMED);
            return sender.sendAsyncReliable(msg, orderNo);
        }
        return sender.sendAsyncReliable(msg, orderNo);
    }

    @Override
    public void refundStock(Long activityId, Long userId) {
        stockService.refund(activityId, userId);
    }

    /** 供监控/管理接口查看当前执行模式 */
    public com.jolumn.vtslseckill.model.enums.SeckillMode currentMode() {
        return degrade.currentMode();
    }
}
