package com.hmdp.utils;

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * @Description 利用redis自增涨获得全局唯一id 可以满足全局唯一并且递增性
 * @Author frank
 * @Date 2024/11/11
 */
@Component
@Slf4j
public class RedisIdWorker {
    // id = 31位时间戳 + 32位redis字增长id
    private static final int COUNT_BITS = 32;
    // 2024 1 1 0:0:0 时间戳
    private static final long BEGIN_TIMESTAMP = 1704067200L;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 生成全局唯一id
     * @param keyPrefix
     * @return
     */
    public long nextId(String keyPrefix) {
        LocalDateTime now = LocalDateTime.now();
        long nowSecond = now.toEpochSecond(ZoneOffset.UTC);
        long timestamp = nowSecond - BEGIN_TIMESTAMP;
        // 生成的key为icr + 具体模块key + 当前日期天 (设置添加日期天可以保证redis自增不会到达上限2的64次方 并且方便后期统计每一天数据)
        String date = now.format(DateTimeFormatter.ofPattern("yyyy:MM:dd"));

        Long count = stringRedisTemplate.opsForValue().increment("icr:" + keyPrefix + ":" + date);
        // 计算得到全局唯一id
        return timestamp << COUNT_BITS | count;
    }

}
