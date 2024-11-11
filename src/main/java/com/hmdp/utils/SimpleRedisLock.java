package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @Description 利用redis实现分布式锁 set key value ex ttl nx
 * @Author frank
 * @Date 2024/11/11
 */
public class SimpleRedisLock implements ILock {
    private StringRedisTemplate stringRedisTemplate;
    // 锁的key前缀
    private static final String KEY_PREFIX = "lock:";
    // 锁中的值 线程的id在jvm中自增 所以需要设置唯一性
    private static final String ID_PREFIX = UUID.randomUUID().toString(true) + "-";
    // 具体用户值
    private String name;
    // 得到释放锁的lua脚本
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    public SimpleRedisLock() {
    }

    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
        this.name = name;
    }

    @Override
    public boolean tryLock(long timeout) {
        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁 设置过期时间防止redis待机发生死锁
        Boolean success = stringRedisTemplate.opsForValue()
                .setIfAbsent(KEY_PREFIX + name, threadId, timeout, TimeUnit.SECONDS);
        return Boolean.TRUE.equals(success);
    }

    /**
     * 第一次误删在线程一拿到锁后发生阻塞 线程一锁超时释放 线程二拿到锁 线程一恢复释放了线程二的锁
     * 避免误删分布式锁 需要给锁的值加上一个唯一id 每次判断当前线程是否和锁中的值匹配
     * 第二次误删在线程一释放锁 先判断是否一致 发生阻塞 线程一锁超时释放 线程二拿到锁 线程一恢复后直接释放了线程二的锁
     * 为了避免误删锁 需要保证判断和释放原子性 使用lua脚本使得判断和释放一次执行
     */
    @Override
    public void unlock() {
/*        // 获取线程标示
        String threadId = ID_PREFIX + Thread.currentThread().getId();
        // 获取锁中的标示
        String id = stringRedisTemplate.opsForValue().get(KEY_PREFIX + name);
        // 判断标示是否一致
        if (threadId.equals(id)) {
            // 释放锁
            stringRedisTemplate.delete(KEY_PREFIX + name);
        }*/

        // 通过lua脚本实现判断和删除原子性
        stringRedisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(KEY_PREFIX + name),
                ID_PREFIX + Thread.currentThread().getId());
    }
}
