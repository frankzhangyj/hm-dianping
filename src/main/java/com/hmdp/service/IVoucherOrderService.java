package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {
    /**
     * 抢购秒杀券
     * @param voucherId
     * @return
     */
    Result seckkillVoucher(Long voucherId);

    /**
     * 通过AOP保证在抢购秒杀券时事务和锁的原子性
     * @param voucherId
     * @return
     */
    public Result createVoucherOrder(Long voucherId);
}
