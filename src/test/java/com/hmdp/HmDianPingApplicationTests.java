package com.hmdp;

import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SpringBootTest
class HmDianPingApplicationTests {
    @Autowired
    private StringRedisTemplate redisTemplate;
    @Autowired
    private RedisIdWorker redisIdWorker;
    private ExecutorService es = Executors.newFixedThreadPool(300);
    /**
     * 测试redis操作
     */
    @Test
    public void testRedis() {
        redisTemplate.opsForValue().set("name", "fuck");
        String name = redisTemplate.opsForValue().get("name");
        System.out.println(name);
    }

    /**
     * 测试300个线程每个执行创建100个id测试redis全局唯一id
     * @throws InterruptedException
     */
    @Test
    void testIdWorker() throws InterruptedException {
        // 由于程序是异步的，当异步程序没有执行完时，主线程就已经执行完了
        // countdownlatch名为信号枪：主要的作用是同步协调在多线程的等待于唤醒问题 标记300个线程
        CountDownLatch latch = new CountDownLatch(300);
        // 每执行完一个线程CountDownLatch减1
        Runnable task = () -> {
            for (int i = 0; i < 100; i++) {
                long id = redisIdWorker.nextId("order");
                System.out.println("id = " + id);
            }
            latch.countDown();
        };
        long begin = System.currentTimeMillis();
        // 执行300个线程
        for (int i = 0; i < 300; i++) {
            es.submit(task);
        }
        latch.await();
        long end = System.currentTimeMillis();
        System.out.println("time = " + (end - begin));
    }

}
