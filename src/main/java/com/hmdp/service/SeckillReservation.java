package com.hmdp.service;

import lombok.Data;

/** Redis 中秒杀资格预占记录的只读映射。 */
@Data
public class SeckillReservation {
    private Long orderId;
    private Long voucherId;
    private Long userId;
    private String status;
    private int attempts;
    private String reason;
}
