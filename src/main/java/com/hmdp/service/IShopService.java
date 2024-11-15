package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IShopService extends IService<Shop> {

    /**
     * 利用redis得到商户信息
     * @param id
     * @return
     */
    Result getShopId(Long id);

    /**
     * 通过redis和数据库数据一致性更新商铺
     * @param shop
     * @return
     */
    Result updateShop(Shop shop);

    /**
     * 按照地理位置分页查询
     * @param typeId
     * @param current
     * @param x
     * @param y
     * @return
     */
    Result queryShopByType(Integer typeId, Integer current, Double x, Double y);
}
