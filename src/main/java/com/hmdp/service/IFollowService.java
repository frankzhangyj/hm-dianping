package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Follow;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IFollowService extends IService<Follow> {

    /**
     * 关注和取关
     * @param followUserId
     * @param isFollow
     * @return
     */
    Result follow(Long followUserId, Boolean isFollow);

    /**
     * 判断是否关注
     * @param followUserId
     * @return
     */
    Result isFollow(Long followUserId);

    /**
     * 通过redis得到共同关注的用户信息
     * @param id
     * @return
     */
    Result commonFollows(Long id);
}
