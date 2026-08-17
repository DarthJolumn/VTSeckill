package com.jolumn.vtslseckill;

import org.apache.dubbo.config.spring.context.annotation.EnableDubbo;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.metrics.buffering.BufferingApplicationStartup;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * vtsl-seckill — 秒杀服务（端口 8082）.
 *
 * <p>承担功能点：
 * <ul>
 *   <li>3.4.1 创建秒杀活动 / 3.4.2 上架下架活动</li>
 *   <li>3.4.3 用户抢购 / 3.4.4 库存分片（Lua 原子扣减）</li>
 *   <li>3.4.5 秒杀结果推送（Kafka Consumer + Dubbo WsPushService）</li>
 *   <li>3.4.6 创建订单（Consumer 异步 INSERT）</li>
 *   <li>3.4.7 超时取消订单（@Scheduled 15s）</li>
 *   <li>3.4.8 用户取消订单 / 3.4.9 订单退款</li>
 *   <li>3.4.10 查询用户订单</li>
 * </ul>
 *
 * <h3>VT 纪律（2.3 + 1.4）</h3>
 * <ul>
 *   <li>抢购链路全程无 @Transactional（Caffeine → Redis Lua → Kafka，无 DB 操作）</li>
 *   <li>创建活动单条 INSERT 不加事务</li>
 *   <li>Consumer INSERT 用 @Transactional + MANUAL ACK</li>
 *   <li>取消/退款用 @Transactional 包裹 DB+Redis（文档承认的妥协）</li>
 *   <li>KafkaListener 内部 Thread.startVirtualThread 委托 VT 执行（P1-5 fixed）</li>
 *   <li>Snowflake ID 用 ReentrantLock（JDK 25 JEP 491 已修复 synchronized pinning，RL 保留为最佳实践）</li>
 * </ul>
 */
@SpringBootApplication(scanBasePackages = {
        "com.jolumn.vtslseckill",
        "com.jolumn.vtslcommon.filter",
        "com.jolumn.vtslcommon.interceptor",
        "com.jolumn.vtslcommon.util",
        "com.jolumn.vtslcommon.exception",
        "com.jolumn.vtslcommon.dto",
        "com.jolumn.vtslcommon.annotation"
})
@EnableDubbo
@EnableScheduling
public class VtslSeckillApplication {

    static void main(String[] args) {
        SpringApplication.run(VtslSeckillApplication.class, args);
    }

//    public static void main(String[] args) {
//        // 1. 创建 SpringApplication 对象
//        SpringApplication app = new SpringApplication(VtslSeckillApplication.class);
//
//        // 2. 开启启动记录缓冲区（容量设大点，默认4096可能不够）
//        app.setApplicationStartup(new BufferingApplicationStartup(8192));
//
//        // 3. 启动应用
//        app.run(args);
//    }

}
