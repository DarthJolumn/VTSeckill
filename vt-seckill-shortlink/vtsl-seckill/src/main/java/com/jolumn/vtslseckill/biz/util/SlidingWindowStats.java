package com.jolumn.vtslseckill.biz.util;

/**
 * 环形数组滑动窗口统计：记录最近 N 次 Kafka 发送的耗时（含超时样本）。
 * 支持查询窗口 P99、均值、标准差、超时率。线程安全（synchronized 内部状态）。
 *
 * <p><b>全量统计（含超时样本）</b>：超时样本记录的是真实等待时长（如 2000ms），
 * 天然作为极大值推高 P99，从而极早触发降级。若剔除超时样本会陷入"幸存者偏差"——
 * Broker 假死时窗口只剩旧健康数据，P99 失真、拒绝降级、线程打满雪崩。</p>
 *
 * <p>设计：环形数组（head 指向最旧样本），窗口填满后新样本覆盖最旧样本并 O(1) 扣减统计。</p>
 */
public class SlidingWindowStats {

    /** 统计快照：count 样本数、mean 均值(ms)、p99 第99百分位(ms)、stdDev 标准差(ms) */
    public record ComputeStats(int count, long mean, long p99, double stdDev) {
        public boolean empty() {
            return count < 1;
        }
    }

    private final int capacity;
    private final long[] elapsedMs;
    private final boolean[] timeout;
    private int head;          // 环形写指针（指向最旧样本，覆盖时替换）
    private int filled;        // 已填样本数（0..capacity）
    private long sum;          // 窗口内总耗时(ms)
    private int timeoutCount;  // 窗口内超时/失败次数

    public SlidingWindowStats(int capacity) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be > 0: " + capacity);
        }
        this.capacity = capacity;
        this.elapsedMs = new long[capacity];
        this.timeout = new boolean[capacity];
    }

    /** 记录一个样本：耗时(ms) + 是否超时/失败。无论是否超时都进入窗口（全量统计） */
    public synchronized void record(long elapsedMs, boolean timeout) {
        if (filled == capacity) {
            // 覆盖最旧样本：先扣减其统计
            sum -= this.elapsedMs[head];
            if (this.timeout[head]) timeoutCount--;
        } else {
            filled++;
        }
        this.elapsedMs[head] = elapsedMs;
        this.timeout[head] = timeout;
        head = (head + 1) % capacity;
        sum += elapsedMs;
        if (timeout) timeoutCount++;
    }

    /** 窗口是否已填满（填满后统计才有意义） */
    public synchronized boolean isFull() {
        return filled == capacity;
    }

    /** 窗口平均耗时(ms)；窗口为空返回 0。含超时样本（恢复判定用的 Mean 即此值） */
    public synchronized long averageMs() {
        return filled == 0 ? 0 : sum / filled;
    }

    /** 窗口超时率(0.0~1.0)；窗口为空返回 0 */
    public synchronized double timeoutRate() {
        return filled == 0 ? 0.0 : (double) timeoutCount / filled;
    }

    /** 已填样本数 */
    public synchronized int size() {
        return filled;
    }

    /** 窗口容量 */
    public int capacity() {
        return capacity;
    }

    /**
     * 全量（含超时）计算 P99 / 均值 / 标准差。
     * 实现：拷贝 filled 个样本到临时数组（解决环形顺序问题）→ 排序 → O(1) 取 P99。
     * N=100 时排序开销亚微秒级，可接受；不维护额外有序结构。
     */
    public synchronized ComputeStats computeStats() {
        if (filled == 0) {
            return new ComputeStats(0, 0, 0, 0.0);
        }
        long[] samples = new long[filled];
        System.arraycopy(elapsedMs, 0, samples, 0, filled);
        java.util.Arrays.sort(samples);

        long p99Idx = (long) Math.ceil(0.99 * filled) - 1;
        if (p99Idx < 0) p99Idx = 0;
        if (p99Idx > filled - 1) p99Idx = filled - 1;
        long p99 = samples[(int) p99Idx];

        long mean = sum / filled;
        double variance = 0.0;
        for (long v : samples) {
            long diff = v - mean;
            variance += (double) (diff * diff) / filled;
        }
        double stdDev = Math.sqrt(variance);

        return new ComputeStats(filled, mean, p99, stdDev);
    }

    /** 清空窗口（恢复后重置统计，避免降级前的超时残留影响新窗口） */
    public synchronized void reset() {
        head = 0;
        filled = 0;
        sum = 0;
        timeoutCount = 0;
    }
}
