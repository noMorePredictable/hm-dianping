package com.hmdp.service;

import com.hmdp.dto.Result;
import com.hmdp.entity.VoucherOrder;
import com.baomidou.mybatisplus.spring.service.IService;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IVoucherOrderService extends IService<VoucherOrder> {

    Result seckillVoucher(Long voucherId);

    SeckillOrderCreateResult createVoucherOrder(VoucherOrder voucherOrder);

    /** 使用状态条件更新关闭未支付订单；返回 true 表示订单已经处于已取消状态。 */
    boolean closeUnpaidOrder(Long orderId);

    /** 查询异步订单处理状态，只允许订单所属用户访问。 */
    Result queryOrderStatus(Long orderId);

    /** 项目内的模拟支付接口，用于验证“支付与超时关单”状态竞争。 */
    Result payOrder(Long orderId);
}
