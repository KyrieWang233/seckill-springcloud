package com.weiran.mission.utils;

/**
 * @Author: Wang Yu
 * @Date: 2025/1/22 16:46
 */
public class SnowflakeIdGenerator {
    private final long epoch = 1672531200000L; // 自定义时间起点（毫秒，2023-01-01 00:00:00）

    private final long workerIdBits = 5L;    // 机器 ID 所占的位数
    private final long datacenterIdBits = 5L; // 数据中心 ID 所占的位数
    private final long sequenceBits = 12L;   // 序列号所占的位数

    private final long maxWorkerId = ~(-1L << workerIdBits);      // 最大机器 ID
    private final long maxDatacenterId = ~(-1L << datacenterIdBits); // 最大数据中心 ID
    private final long sequenceMask = ~(-1L << sequenceBits);     // 序列号掩码

    private final long workerIdShift = sequenceBits; // 机器 ID 左移位数
    private final long datacenterIdShift = sequenceBits + workerIdBits; // 数据中心 ID 左移位数
    private final long timestampLeftShift = sequenceBits + workerIdBits + datacenterIdBits; // 时间戳左移位数

    private long workerId;
    private long datacenterId;
    private long sequence = 0L; // 序列号
    private long lastTimestamp = -1L; // 上一次生成 ID 的时间戳

    public SnowflakeIdGenerator(long workerId, long datacenterId) {
        if (workerId > maxWorkerId || workerId < 0) {
            throw new IllegalArgumentException("Worker ID out of range.");
        }
        if (datacenterId > maxDatacenterId || datacenterId < 0) {
            throw new IllegalArgumentException("Datacenter ID out of range.");
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    public synchronized long nextId() {
        long timestamp = System.currentTimeMillis();

        // 如果当前时间小于上次生成时间，抛出异常
        if (timestamp < lastTimestamp) {
            throw new RuntimeException("Clock moved backwards. Refusing to generate ID.");
        }

        if (timestamp == lastTimestamp) {
            // 同一毫秒内，递增序列号
            sequence = (sequence + 1) & sequenceMask;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                timestamp = waitForNextMillis(lastTimestamp);
            }
        } else {
            // 不同毫秒，序列号归零
            sequence = 0L;
        }

        lastTimestamp = timestamp;

        // 生成唯一 ID
        return ((timestamp - epoch) << timestampLeftShift)
                | (datacenterId << datacenterIdShift)
                | (workerId << workerIdShift)
                | sequence;
    }

    private long waitForNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }
}
