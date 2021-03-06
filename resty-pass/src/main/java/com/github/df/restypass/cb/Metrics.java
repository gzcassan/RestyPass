package com.github.df.restypass.cb;

import com.github.df.restypass.util.CacheLongAdder;
import lombok.ToString;

import java.util.Deque;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 执行结果计数器
 * 线程不安全
 * Created by darrenfu on 17-7-24.
 */
@SuppressWarnings("WeakerAccess")
public class Metrics {

    /**
     * The constant DEFAULT_PERIOD_MILLISECONDS.
     */
    private static final Long DEFAULT_PERIOD_MILLISECONDS = 1000 * 30L;
    /**
     * The constant DEFAULT_MAX_SEGMENT_NUMBER.
     */
    private static final Integer DEFAULT_MAX_SEGMENT_NUMBER = 10;

    /**
     * 分段计数器 链表
     */
    private Deque<SegmentMetrics> metricsDeque;

    /**
     * 计数器分段周期
     */
    private Long period;

    /**
     * 最大分段数量
     */
    private Integer maxSegmentNumber;


    /**
     * Instantiates a new Metrics.
     */
    public Metrics() {
        this(DEFAULT_PERIOD_MILLISECONDS, DEFAULT_MAX_SEGMENT_NUMBER);
    }

    /**
     * Instantiates a new Metrics.
     *
     * @param period           the period
     * @param maxSegmentNumber the max segment number
     */
    public Metrics(Long period, Integer maxSegmentNumber) {
        this.period = period;
        this.maxSegmentNumber = maxSegmentNumber;
        this.metricsDeque = new ConcurrentLinkedDeque<>();
        SegmentMetrics newMetrics = new SegmentMetrics();
        this.metricsDeque.addFirst(newMetrics);
    }


    /**
     * Store segment metrics.
     *
     * @param success the success
     * @return the segment metrics
     */
    @SuppressWarnings("UnusedReturnValue")
    public SegmentMetrics store(boolean success, boolean forceUseNewMetrics) {
        SegmentMetrics firstMetrics = getMetrics();
        if (success) {
            // 成功的数据，判断是否需要生成新的计数器，失败的不需要
            if (forceUseNewMetrics || firstMetrics.isOverTime()) {
                firstMetrics = addMetrics();
            }
            firstMetrics.success();
        } else {
            firstMetrics.fail();
        }
        return firstMetrics;
    }

    /**
     * Add first metrics segment metrics.
     *
     * @return the segment metrics
     */
    public SegmentMetrics addMetrics() {
        SegmentMetrics newMetrics = new SegmentMetrics(this.period);
        metricsDeque.addFirst(newMetrics);
        if (metricsDeque.size() > this.maxSegmentNumber) {
            metricsDeque.removeLast();
        }
        return newMetrics;
    }

    /**
     * Gets first metrics.
     *
     * @return the first metrics
     */
    public SegmentMetrics getMetrics() {
        SegmentMetrics metrics = this.metricsDeque.peekFirst();
        if (metrics == null) {
            metrics = new SegmentMetrics(this.period);
            metricsDeque.offerFirst(metrics);
        }
        return metrics;
    }


    /**
     * 按照索引获取计数器 从0开始
     *
     * @param index
     * @return
     */
    public SegmentMetrics getMetrics(int index) {
        Iterator<SegmentMetrics> iterator = metricsDeque.iterator();

        int i = 0;
        while (iterator.hasNext()) {
            if (i == index) {
                return iterator.next();
            }
            i++;
        }

        return null;
    }

    @Override
    public String toString() {
        return "Metrics{" +
                "metricsDeque=" + metricsDeque.getFirst() +
                ", period=" + period +
                ", maxSegmentNumber=" + maxSegmentNumber +
                '}';
    }

    /**
     * 计数器
     */
    @ToString(exclude = {"lock"})
    protected class SegmentMetrics {
        /**
         * 总数
         */
        private LongAdder total;

        /**
         * 失败数量
         */
        private LongAdder fail;

        /**
         * 失败比例 （性能考虑，失败比例只在增加fail记录时才重新计算）
         */
        private Integer failPercentage;

        /**
         * 最近一次记录时间戳
         */
        private Long last;

        /**
         * 最近一次失败记录的时间戳
         */
        private Long lastFail;

        /**
         * 失败次数，快速返回
         */
        private Long failCount;

        /**
         * 最近请求中连续失败次数
         */
        private Integer continuousFailCount;


        /**
         * 锁
         */
        private ReentrantLock lock;

        private Long endline;

        /**
         * Instantiates a new Metrics metricsDeque.
         */
        public SegmentMetrics() {
            this(DEFAULT_PERIOD_MILLISECONDS);
        }


        /**
         * Instantiates a new Segment metrics.
         *
         * @param period the period
         */
        public SegmentMetrics(Long period) {
            this.total = new CacheLongAdder();
            this.fail = new CacheLongAdder();
            this.failPercentage = 0;
            this.last = null;
            this.lastFail = null;
            this.failCount = null;
            this.continuousFailCount = 0;
            this.lock = new ReentrantLock();
            this.endline = System.currentTimeMillis() + period;
        }

        /**
         * 失败总数
         *
         * @return the long
         */
        public Long failCount() {
            if (failCount == null) {
                failCount = fail.longValue();
            }
            return failCount;
        }

        /**
         * 失败比例 (%)
         *
         * @return the integer
         */
        public Integer failPercentage() {
            return failPercentage;
        }

        /**
         * 失败记录
         */
        public void fail() {
            fail.increment();
            total.increment();

            final ReentrantLock lock = this.lock;
            lock.lock();
            try {
                // 上次请求是失败请求
                if (lastFail == last) {
                    continuousFailCount++;
                }
                lastFail = last = System.currentTimeMillis();
                failCount = fail.longValue();
                failPercentage = Math.toIntExact(failCount * 100 / total.longValue());
            } finally {
                lock.unlock();
            }
        }


        /**
         * 成功记录
         */
        public void success() {
            total.increment();
            last = System.currentTimeMillis();
            // （有成功请求，则置0）
            continuousFailCount = 0;
        }


        /**
         * Last long.
         *
         * @return the long
         */
        public Long last() {
            return this.last;
        }

        /**
         * Is over time boolean.
         *
         * @return the boolean
         */
        public boolean isOverTime() {
            return System.currentTimeMillis() > this.endline;
        }

        /**
         * 获取最近请求连续失败的次数
         *
         * @return
         */
        public Integer getContinuousFailCount() {
            return this.continuousFailCount;
        }
    }

}
