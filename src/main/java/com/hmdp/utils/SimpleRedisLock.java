package com.hmdp.utils;

import cn.hutool.core.lang.UUID;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.concurrent.TimeUnit;

/**
 * @Description 利用redis实现分布式锁 set key value ex ttl nx
 *              但是没有实现可重入 不可重试(没有重试机制) 超时释放(如果业务时间过程 导致锁提前失效) 集群问题的主从一致
 *
 *              使用redisson可以解决上述问题
 *              实现可重入(使用hash结果 设置一个value记录线程获取锁的次数 当为0时释放锁)
 *              可重试(没有重试机制) 采用信号量机制每一次等待 当一个锁释放后才去重试 直到到达最大等待时间
*               超时预约 利用watchDog定时任务 获取锁后每隔一段时间重置锁的过期时间 避免释放锁时业务延迟导致锁提前释放
 *              在释放锁时 发送释放锁消息 取消watchDog (可能发生redis宕机 发生主从一致性问题)
 *              主从一致 mutilock 对于redis集群 redisson直接取消主从 全部都单独配置分布式锁 然后一次请求所有redis中的锁 每一个都和单个锁过程一致
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
        // 获取锁 设置过期时间防止redis待机发生死锁  set key value ex ttl nx
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
