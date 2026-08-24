package com.jolumn.vtslseckill.biz.mq;

import com.jolumn.vtslseckill.biz.util.SlidingWindowStats;
import com.jolumn.vtslseckill.model.enums.SeckillMode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REDIS_SYNC 自适应降级决策器（与发送动作解耦，独立可测）。
 *
 * <p>非对称滞后状态机（Hysteresis）：
 * <ul>
 *   <li><b>降级（灵敏）</b>：窗口 P99 &gt; max-p99-ms <b>或</b> 超时率 &gt; max-timeout-rate → 立即切 REDIS_ASYNC</li>
 *   <li><b>恢复（迟钝）</b>：Mean &lt; mean-recover-ms <b>且</b> 超时率 == 0，并<b>持续 recover-evals 次</b>才切回 REDIS_SYNC</li>
 * </ul>
 * 降级看尾部(P99)、恢复看中心(Mean)且需持续达标，二者之间形成滞后缓冲区，过滤网络毛刺导致的
 * 状态机反复横跳（Thrashing）。恢复瞬间 {@code window.reset()} 清掉降级前的超时残留，干净起步。</p>
 *
 * <p>动态超时：{@code safeTimeoutMs() = clamp(P99 + timeoutK * σ, min-timeout-ms, max-timeout-ms)}，
 * 健康时极速释放线程，抖动时自动放宽，恶化时兜底。冷启动（样本不足）用固定 3s。</p>
 *
 * <p>线程安全：热路径只做 O(1) 的 {@link #record}（窗口内部锁）；状态判定移到
 * {@link #evaluate} 定时任务（默认 100ms），不在每次发送时持有状态锁；
 * {@link #isSyncMode} 用 AtomicBoolean 无锁读。</p>
 */
@Component
public class SyncDegradeController {

    private static final Logger log = LoggerFactory.getLogger(SyncDegradeController.class);

    /** 是否已降级（true = REDIS_ASYNC 异步受理；false = REDIS_SYNC 同步等待） */
    private final AtomicBoolean degraded = new AtomicBoolean(false);
    /** 恢复阶段：连续健康判定计数 */
    private final AtomicInteger recoverCount = new AtomicInteger(0);

    private final SlidingWindowStats window;
    private final long maxP99Ms;
    private final double maxTimeoutRate;
    private final long meanRecoverMs;
    private final int recoverEvals;
    private final double timeoutK;
    private final long minTimeoutMs;
    private final long maxTimeoutMs;
    private final int coldStartSamples;
    private final long coldStartTimeoutMs;
    private final double sampleRate;

    public SyncDegradeController(@Value("${seckill.sync-degrade.window-size:100}") int windowSize,
                                 @Value("${seckill.sync-degrade.max-p99-ms:800}") long maxP99Ms,
                                 @Value("${seckill.sync-degrade.max-timeout-rate:0.05}") double maxTimeoutRate,
                                 @Value("${seckill.sync-degrade.mean-recover-ms:200}") long meanRecoverMs,
                                 @Value("${seckill.sync-degrade.recover-evals:50}") int recoverEvals,
                                 @Value("${seckill.sync-degrade.timeout-k:2.5}") double timeoutK,
                                 @Value("${seckill.sync-degrade.min-timeout-ms:100}") long minTimeoutMs,
                                 @Value("${seckill.sync-degrade.max-timeout-ms:2000}") long maxTimeoutMs,
                                 @Value("${seckill.sync-degrade.cold-start-samples:30}") int coldStartSamples,
                                 @Value("${seckill.sync-degrade.send-timeout-seconds:3}") long sendTimeoutSeconds,
                                 @Value("${seckill.sync-degrade.sample-rate:0.05}") double sampleRate) {
        this.window = new SlidingWindowStats(windowSize);
        this.maxP99Ms = maxP99Ms;
        this.maxTimeoutRate = maxTimeoutRate;
        this.meanRecoverMs = meanRecoverMs;
        this.recoverEvals = recoverEvals;
        this.timeoutK = timeoutK;
        this.minTimeoutMs = minTimeoutMs;
        this.maxTimeoutMs = maxTimeoutMs;
        this.coldStartSamples = coldStartSamples;
        this.coldStartTimeoutMs = sendTimeoutSeconds * 1000;
        this.sampleRate = sampleRate;
    }

    /** 是否处于同步模式（正常态） */
    public boolean isSyncMode() {
        return !degraded.get();
    }

    /** 当前执行模式 */
    public SeckillMode currentMode() {
        return degraded.get() ? SeckillMode.REDIS_ASYNC : SeckillMode.REDIS_SYNC;
    }

    /**
     * 记录一次发送结果（仅写窗口，不在热路径做判定）。
     * 超时样本也要记录真实等待时长（isTimeout=true），否则会陷入幸存者偏差。
     *
     * @param elapsedMs 本次发送实际耗时(ms)——含超时实际等待
     * @param isTimeout 是否超时/失败（CONFIRMED 之外均视为 true）
     */
    public void record(long elapsedMs, boolean isTimeout) {
        window.record(elapsedMs, isTimeout);
    }

    /** 降级模式下：按 sample-rate 决定本次是否走同步探针 */
    public boolean shouldSample() {
        return ThreadLocalRandom.current().nextDouble() < sampleRate;
    }

    /**
     * 动态超时安全边界：P99 + k·σ，clamp 到 [min, max]。
     * 样本不足（冷启动）返回固定 3s。热路径调用，只锁窗口不锁状态。
     */
    public long safeTimeoutMs() {
        SlidingWindowStats.ComputeStats s = window.computeStats();
        if (s.empty() || s.count() < coldStartSamples) {
            return coldStartTimeoutMs;
        }
        long boundary = (long) (s.p99() + timeoutK * s.stdDev());
        if (boundary < minTimeoutMs) return minTimeoutMs;
        return Math.min(boundary, maxTimeoutMs);
    }

    /**
     * 状态机判定（定时执行，不在每次发送时调用）。
     * 降级灵敏 / 恢复迟钝 + 持续达标 + 恢复清窗。
     */
    @Scheduled(fixedDelayString = "${seckill.sync-degrade.evaluate-interval-ms:100}")
    public synchronized void evaluate() {
        SlidingWindowStats.ComputeStats s = window.computeStats();
        if (s.empty()) return;

        double rate = window.timeoutRate();

        if (!degraded.get() && (s.p99() > maxP99Ms || rate > maxTimeoutRate)) {
            degraded.set(true);
            recoverCount.set(0);
            log.warn("【降级】REDIS_SYNC 降级为 REDIS_ASYNC: p99={}ms, timeoutRate={}%, window={}",
                    s.p99(), String.format("%.2f", rate * 100), s.count());
            return;
        }

        if (degraded.get() && s.mean() < meanRecoverMs && rate == 0.0) {
            if (recoverCount.incrementAndGet() >= recoverEvals) {
                degraded.set(false);
                recoverCount.set(0);
                window.reset();   // 清残留，干净起步
                log.info("【恢复】连续 {} 次窗口健康(Mean<{}ms, 超时率=0)，恢复 REDIS_SYNC", recoverEvals, meanRecoverMs);
            }
            return;
        }

        if (degraded.get()) {
            recoverCount.set(0);  // 出现毛刺，重新计数
        }
    }

    /**
     * 诊断轮询：每 2s 打印窗口内部状态（压测观察用）。
     */
    @Scheduled(fixedDelayString = "${seckill.sync-degrade.print-interval-ms:2000}")
    public void printWindowStats() {
        SlidingWindowStats.ComputeStats s = window.computeStats();
        if (s.empty()) return;
        log.info("【窗口】mode={}, filled={}/{}, mean={}ms, p99={}ms, σ={}, rate={}%, safeTimeout={}ms",
                degraded.get() ? "ASYNC" : "SYNC", s.count(), window.capacity(),
                s.mean(), s.p99(), String.format("%.1f", s.stdDev()),
                String.format("%.1f", window.timeoutRate() * 100), safeTimeoutMs());
    }
}
