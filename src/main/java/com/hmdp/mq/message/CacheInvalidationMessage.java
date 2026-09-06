package com.hmdp.mq.message;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/** 多实例之间广播本地缓存失效事件。 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class CacheInvalidationMessage implements Serializable {

    private static final long serialVersionUID = 1L;

    private String cacheName;
    private Long key;
}
