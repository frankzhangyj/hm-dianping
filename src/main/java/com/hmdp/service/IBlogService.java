package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    /**
     * 分页查询所有博客
     * @param current
     * @return
     */
    Result queryHotBlog(Integer current);

    /**
     * 根据id查博客
     * @param id
     * @return
     */
    Result getBlogById(Long id);

    /**
     * 同一个用户只能点赞一次
     * @param id
     * @return
     */
    Result likeBlog(Long id);

    /**
     * 查询点赞时间前五名
     * @param id
     * @return
     */
    Result queryBlogLikes(Long id);

    /**
     * 保存blog 并且推送到粉丝的redis收件箱中
     * @param blog
     * @return
     */
    Result saveBlog(Blog blog);

    /**
     * 滚动查询收件箱
     * @param max
     * @param offset
     * @return
     */
    Result queryBlogOfFollow(Long max, Integer offset);
}
