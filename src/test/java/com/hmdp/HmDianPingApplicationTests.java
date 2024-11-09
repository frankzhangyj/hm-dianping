package com.hmdp;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private StringRedisTemplate redisTemplate;

    @Test
    public void testRedis() {

        redisTemplate.opsForValue().set("name", "fuck");
        String name = redisTemplate.opsForValue().get("name");
        System.out.println(name);
    }

}
