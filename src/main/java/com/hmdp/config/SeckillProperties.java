package com.hmdp.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 秒杀链路的可调参数。
 *
 * <p>把限流、消息重投和关单时间放进配置，而不是散落成魔法数字，
 * 便于压测时只修改配置就能观察吞吐量和可靠性的变化。</p>
 */
@Data
@ConfigurationProperties(prefix = "hmdp.seckill")
public class SeckillProperties {

    /** 未支付订单多久后自动关闭，默认 15 分钟。 */
    private long orderTimeoutMillis = 15 * 60 * 1000L;

    /** 发布后多久仍未收到 Confirm，就由恢复任务检查并重投。 */
    private long publishConfirmTimeoutMillis = 5_000L;

    /** RabbitMQ 创建订单消息最多发送次数。 */
    private int publishMaxAttempts = 3;

    /** Redis 中资格预占记录的保留时间，必须大于订单超时时间。 */
    private long reservationTtlSeconds = 24 * 60 * 60L;

    /** 消息恢复任务的扫描间隔。 */
    private long recoveryFixedDelayMillis = 3_000L;

    /** 数据库兜底关单任务的扫描间隔。 */
    private long closeFallbackFixedDelayMillis = 60_000L;

    /** 每批最多恢复多少条消息，避免一次扫描长时间占用线程。 */
    private int recoveryBatchSize = 100;

    /** 秒杀接口分布式滑动窗口限流参数。 */
    private RateLimit rateLimit = new RateLimit();

    @Data
    public static class RateLimit {
        private long windowMillis = 1_000L;
        private int globalRequests = 2_000;
        private int userRequests = 5;
    }
}
