package com.intelligent.agent.web.infrastructure.monitoring;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

/**
 * 轻量进程内指标（R-13）：计数器 + 毫秒直方图（环形缓冲 + 分位数），零外部依赖。
 * 供 {@code GET /api/metrics} 与告警触发使用；线程安全。
 */
public class MetricsRegistry {

    private final Map<String, LongAdder> counters = new ConcurrentHashMap<>();
    private final Map<String, Ring> histograms = new ConcurrentHashMap<>();
    private final int histogramCapacity;

    public MetricsRegistry() {
        this(1024);
    }

    public MetricsRegistry(int histogramCapacity) {
        this.histogramCapacity = Math.max(16, histogramCapacity);
    }

    public void increment(String name) {
        incrementBy(name, 1);
    }

    public void incrementBy(String name, long delta) {
        counters.computeIfAbsent(name, k -> new LongAdder()).add(delta);
    }

    public long counter(String name) {
        LongAdder adder = counters.get(name);
        return adder == null ? 0 : adder.sum();
    }

    /** 记录直方图采样（毫秒）。 */
    public void record(String histogramName, long valueMillis) {
        histograms.computeIfAbsent(histogramName, k -> new Ring(histogramCapacity)).add(valueMillis);
    }

    /** 合并快照：{counters: {...}, histograms: {name: {count,p50,p90,p95,p99,max}}}。 */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Long> counterMap = new TreeMap<>();
        counters.forEach((k, v) -> counterMap.put(k, v.sum()));
        out.put("counters", counterMap);
        Map<String, Object> histMap = new TreeMap<>();
        histograms.forEach((k, ring) -> histMap.put(k, ring.snapshot()));
        out.put("histograms", histMap);
        return out;
    }

    /** 环形缓冲：保留最近 N 个采样，快照按排序后线性插值给出分位数。 */
    private static final class Ring {
        private final long[] samples;
        private final Object lock = new Object();
        private int size;
        private int head;

        Ring(int capacity) {
            samples = new long[capacity];
        }

        void add(long value) {
            synchronized (lock) {
                samples[head] = value;
                head = (head + 1) % samples.length;
                if (size < samples.length) {
                    size++;
                }
            }
        }

        Map<String, Object> snapshot() {
            synchronized (lock) {
                if (size == 0) {
                    return Map.of("count", 0);
                }
                long[] copy = Arrays.copyOf(samples, size);
                Arrays.sort(copy);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("count", copy.length);
                m.put("p50", percentile(copy, 0.50));
                m.put("p90", percentile(copy, 0.90));
                m.put("p95", percentile(copy, 0.95));
                m.put("p99", percentile(copy, 0.99));
                m.put("max", copy[copy.length - 1]);
                return m;
            }
        }

        private static long percentile(long[] sorted, double q) {
            double pos = q * (sorted.length - 1);
            int lo = (int) Math.floor(pos);
            int hi = (int) Math.ceil(pos);
            if (lo == hi) {
                return sorted[lo];
            }
            double frac = pos - lo;
            return Math.round(sorted[lo] + (sorted[hi] - sorted[lo]) * frac);
        }
    }
}
