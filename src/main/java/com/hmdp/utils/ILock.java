package com.hmdp.utils;

/**
 * @Description
 * @Author frank
 * @Date 2024/11/11
 */
public interface ILock {
    /**
     * 上锁
     * @param timeout
     * @return
     */
    boolean tryLock(long timeout);

    /**
     * 解锁
     */
    void unlock();
}
