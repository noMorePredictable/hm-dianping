package com.hmdp.utils;

public interface ILock {
    //获取锁
    boolean tryLock(Long timeoutSec);
    /*
    * 释放锁
    * */
    void unlock();
}
