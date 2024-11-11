package com.hmdp.service.impl;

import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.SimpleRedisLock;
import com.hmdp.utils.UserHolder;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder> implements IVoucherOrderService {
    @Resource
    private ISeckillVoucherService seckillVoucherService;
    @Autowired
    private RedisIdWorker redisIdWorker;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Override
    @Transactional
    public Result seckkillVoucher(Long voucherId) {
        // 先判断秒杀券是否超时
        SeckillVoucher voucher= seckillVoucherService.getById(voucherId);
        if (voucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀尚未开始");
        }
        if (voucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已经结束");
        }

        if (voucher.getStock() < 1) {
            return Result.fail("库存不足");
        }


        Long userId = UserHolder.getUser().getId();

/*        // toString创建了新对象 所以并发的线程每次都创建的是新对象id 所以锁不住同一个用户 所以需要使用intern()得到字符串常量池中的同一个值
        // 但是通过synchronized只能保证在单体程序下的上锁 分布式集群会导致不同jvm存在不同的锁监视器 导致重复
        synchronized (userId.toString().intern()) {
            // Spring的事务实现需要使用动态代理得到的AOP 所以这里得到当前service的代理对象 实现事务和锁的原子性
            IVoucherOrderService proxy = (IVoucherOrderService)AopContext.currentProxy();
            Result result = proxy.createVoucherOrder(voucherId);
            return result;
        }*/

        // 通过redis分布式锁实现避免商品超卖和一人多次下单成功
        SimpleRedisLock simpleRedisLock = new SimpleRedisLock("order" + userId, stringRedisTemplate);

        boolean isLock = simpleRedisLock.tryLock(1200);
        if (!isLock) {
            return Result.fail("不允许重复下单");
        }

        try {
            //获取代理对象(事务)
            IVoucherOrderService proxy = (IVoucherOrderService) AopContext.currentProxy();
            return proxy.createVoucherOrder(voucherId);
        } finally {
            //释放锁
            simpleRedisLock.unlock();
        }
    }

    /**
     * 避免线程并发导致同一用户下单多个秒杀券(同一用户多次查询值为空 所以可以多次修改数据库)
     * 乐观锁适合更新数据 悲观锁适合插入数据
     * 此处使用悲观锁synchronized 如果在方法内使用 可能会导致当前方法事务还没有提交，但是锁已经释放也会导致问题
     * @param voucherId
     * @return
     */
    public Result createVoucherOrder(Long voucherId) {
            // 一人一单逻辑
            // 用户id
            Long userId = UserHolder.getUser().getId();
            int count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
            // 判断是否存在
            if (count > 0) {
                // 用户已经购买过了
                return Result.fail("用户已经购买过一次！");
            }

            // TODO 为什么lambda更新报错
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
                return Result.fail("库存不足");
            }

            VoucherOrder voucherOrder = new VoucherOrder();
            // 设置全局唯一id
            long orderId = redisIdWorker.nextId("order");
            voucherOrder.setId(orderId);
            voucherOrder.setUserId(userId);
            voucherOrder.setVoucherId(voucherId);
            save(voucherOrder);
            // TODO 是否需要更新redis中stock
            return Result.ok(orderId);
    }
}
