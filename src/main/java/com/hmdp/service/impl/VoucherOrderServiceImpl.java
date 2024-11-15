package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.redisson.Redisson;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

/**
 * 通过redisson中的分布式锁利用重试机制解决并发问题和可重入问题 利用lua脚本解决redis的操作原子性避免误删操作 通过父子线程优化 异步处理订单(阻塞队列 or 消息队列)
 *
 * @author
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    // 全局唯一id
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private RedissonClient redissonClient;
    // redis判断脚本
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckillRecord.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    //异步处理线程池
    private static final ExecutorService SECKILL_ORDER_EXECUTOR = Executors.newSingleThreadExecutor();

    // aop代理对象 便于spring管理事务
    private IVoucherOrderService proxy;

    //在类初始化之后执行，生成子线程异步处理，因为当这个类初始化好了之后，随时都是有可能要执行的
    @PostConstruct
    private void init() {
        SECKILL_ORDER_EXECUTOR.submit(new VoucherOrderHandler());
    }

    // 用于线程池处理的任务


    /**
     * 子线程通过redis消息队列stream的消费者组(消息队列可以被多个线程拿到消息) 解决异步操作
     */
    private class VoucherOrderHandler implements Runnable {
        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取消息队列中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 >
                    // 当前线程从c1(消费者)从消息队列为stream.orders中的g1消费者组中获取下一个未读消息 最大延迟时间2000ms
                    List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                            Consumer.from("g1", "c1"),
                            StreamReadOptions.empty().count(1).block(Duration.ofSeconds(2)),
                            StreamOffset.create("stream.orders", ReadOffset.lastConsumed())
                    );
                    // 2.判断订单信息是否为空
                    if (list == null || list.isEmpty()) {
                        // 如果为null，说明没有消息，继续下一次循环
                        continue;
                    }
                    // 解析数据
                    MapRecord<String, Object, Object> record = list.get(0);
                    Map<Object, Object> value = record.getValue();
                    VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                    // 3.创建订单
                    createVoucherOrder(voucherOrder);
                    // 4.确认消息 XACK
                    stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                    //处理异常消息
                    handlePendingList();
                }
            }
        }
    }

    /**
     * 处理pending-list中的异常消息
     */
    private void handlePendingList() {
        while (true) {
            try {
                // 1.获取pending-list中的订单信息 XREADGROUP GROUP g1 c1 COUNT 1 BLOCK 2000 STREAMS s1 0
                List<MapRecord<String, Object, Object>> list = stringRedisTemplate.opsForStream().read(
                        Consumer.from("g1", "c1"),
                        StreamReadOptions.empty().count(1),
                        StreamOffset.create("stream.orders", ReadOffset.from("0"))
                );
                // 2.判断订单信息是否为空
                if (list == null || list.isEmpty()) {
                    // 如果为null，说明没有异常消息，结束循环
                    break;
                }
                // 解析数据
                MapRecord<String, Object, Object> record = list.get(0);
                Map<Object, Object> value = record.getValue();
                VoucherOrder voucherOrder = BeanUtil.fillBeanWithMap(value, new VoucherOrder(), true);
                // 3.创建订单
                createVoucherOrder(voucherOrder);
                // 4.确认消息 XACK
                stringRedisTemplate.opsForStream().acknowledge("s1", "g1", record.getId());
            } catch (Exception e) {
                log.error("处理pendding订单异常");
                try {
                    // 等待20ms 再次处理pending-list消息
                    Thread.sleep(20);
                } catch (Exception e1) {
                    continue;
                }
            }
        }
    }

    // 当初始化完毕后，就会去从足阻塞对列中去拿信息
// 阻塞队列 异步处理数据库操作
/*private BlockingQueue<VoucherOrder> orderTasks = new ArrayBlockingQueue<>(1024 * 1024);
    private class VoucherOrderHandler implements Runnable {

        @Override
        public void run() {
            while (true) {
                try {
                    // 1.获取队列中的订单信息
                    VoucherOrder voucherOrder = orderTasks.take();
                    // 2.创建订单
                    handleVoucherOrder(voucherOrder);
                } catch (Exception e) {
                    log.error("处理订单异常", e);
                }
            }
        }
    }*/

    /**
     * 子线程通过阻塞队列操作数据库
     *
     * @param voucherOrder
     */
/*    private void handleVoucherOrder(VoucherOrder voucherOrder) {
        //1.获取用户 子线程不能从ThreadLocal取信息
        Long userId = voucherOrder.getUserId();
        // 2.创建锁对象
        RLock redisLock = redissonClient.getLock("lock:order:" + userId);
        // 3.尝试获取锁
        boolean isLock = redisLock.tryLock();
        // 4.判断是否获得锁成功
        if (!isLock) {
            // 获取锁失败，直接返回失败或者重试
            log.error("不允许重复下单！");
            return;
        }
        try {
            //注意：由于是spring的事务是放在threadLocal中，此时的是多线程，事务会失效
            proxy.createVoucherOrder(voucherOrder);
        } finally {
            // 释放锁
            redisLock.unlock();
        }
    }*/

    /**
     * 通过消息队列实现异步操作 主线程将订单信息存到redis 同时放入消息队列 子线程读取消息队列进行数据库操作
     *
     * @param voucherId
     * @return
     */
    @Override
    public Result seckkillVoucher(Long voucherId) {
        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 利用redis获得全局唯一id
        long orderId = redisIdWorker.nextId("order");
        // redis利用lua执行判断是否重复下单和库存是否充足
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        // 获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        // 返回订单id
        return Result.ok(orderId);
    }

    /**
     * 主线程操作redis进行判断操作并将订单信息存在阻塞队列中 提前将订单信息 子线程通过阻塞队列中的信息操作数据库
     *
     * @param voucherId
     * @return
     */
    public Result seckkillVoucher1(Long voucherId) {
        // 这一部分交给redis来判断
/*        // 先判断秒杀券是否超时
        SeckillVoucher voucher= seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }*/


        //获取用户
        Long userId = UserHolder.getUser().getId();
        // 利用redis获得全局唯一id
        long orderId = redisIdWorker.nextId("order");
        // redis利用lua执行判断是否重复下单和库存是否充足
        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(), userId.toString(), String.valueOf(orderId)
        );

        int r = result.intValue();
        if (r != 0) {
            return Result.fail(r == 1 ? "库存不足" : "不能重复下单");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        voucherOrder.setId(orderId);
        // 2.4.用户id
        voucherOrder.setUserId(userId);
        // 2.5.代金券id
        voucherOrder.setVoucherId(voucherId);
//        // 2.6.放入阻塞队列
//        orderTasks.add(voucherOrder);
        //3.获取代理对象
        proxy = (IVoucherOrderService) AopContext.currentProxy();
        //4.返回订单id
        return Result.ok(orderId);


/*        // toString创建了新对象 所以并发的线程每次都创建的是新对象id 所以锁不住同一个用户 所以需要使用intern()得到字符串常量池中的同一个值
        // 但是通过synchronized只能保证在单体程序下的上锁 分布式集群会导致不同jvm存在不同的锁监视器 导致重复
        synchronized (userId.toString().intern()) {
            // Spring的事务实现需要使用动态代理得到的AOP 所以这里得到当前service的代理对象 实现事务和锁的原子性
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            Result result = proxy.createVoucherOrder(voucherId);
            return result;
        }*/

        // 通过redis分布式锁实现避免商品超卖和一人多次下单成功
//        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order" + userId, stringRedisTemplate);

//        boolean isLock = simpleRedisLock.tryLock(1200);

/*        // 通过redisson得到分布式锁
        RLock lock = redissonClient.getLock("lock:order:" + userId);

        boolean isLock = false;
        try {
            isLock = lock.tryLock(1, 10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }

        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            lock.unlock();
        }*/
    }

    /**
     * 避免线程并发导致同一用户下单多个秒杀券(同一用户多次查询值为空 所以可以多次修改数据库)
     * 乐观锁适合更新数据 悲观锁适合插入数据
     * 此处可以使用悲观锁synchronized 如果在方法内使用 可能会导致当前方法事务还没有提交，但是锁已经释放也会导致问题
     * 优化后使用异步方式和redisson分布式锁来解决
     * 通过消息队列中的消息在子线程中操作数据库
     * @param voucherOrder
     * @return
     */
    @Transactional
    public void createVoucherOrder(VoucherOrder voucherOrder) {
        // 一人一单逻辑
        // 用户id
        Long userId = voucherOrder.getUserId();
        Long voucherId = voucherOrder.getVoucherId();

        int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        // 判断是否存在
        if (count > 0) {
            // 用户已经购买过了
//            return Result.fail("用户已经购买过一次！");
            log.error("重复下单");
        }


//        boolean success = seckillVoucherService.lambdaUpdate()
//                .set(SeckillVoucher::getStock, voucher.getStock() - 1)
//                .eq(SeckillVoucher::getVoucherId, voucher.getVoucherId())
//                .update();
        // 利用乐观锁类似版本号法解决并发问题(也可以使用版本号解决 但是容易造成操作失败 因为没有自旋重复得到锁)
        // 这里使用类似版本号法 通过对比stock是否大于0 可以保证秒杀券最后全部抢光 即不会出现超卖现象 也不会出现少卖现象
        boolean success = seckillVoucherService.update()
                .setSql("stock= stock -1")
                .eq("voucher_id", voucherId).gt("stock", 0).update(); //where id = ? and stock > 0

        if (!success) {
//            return Result.fail("库存不足");
            log.error("库存不足");
        }
// 已经在主线程中创建了订单
//        VoucherOrder voucherOrder = new VoucherOrder();
//        // 设置全局唯一id
//        long orderId = redisIdWorker.nextId("order");
//        voucherOrder.setId(orderId);
//        voucherOrder.setUserId(userId);
//        voucherOrder.setVoucherId(voucherId);
          save(voucherOrder);
    }
}
