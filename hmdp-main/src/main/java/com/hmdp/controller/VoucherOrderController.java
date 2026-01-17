package com.hmdp.controller;


import com.hmdp.dto.Result;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

/**
 * <p>
 *  前端控制器
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@RestController
@RequestMapping("/voucher-order")
public class VoucherOrderController {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @PostMapping("seckill/{id}")
    public Result seckillVoucher(@PathVariable("id") Long voucherId) {
        return voucherOrderService.seckillVoucher(voucherId);
    }
    /**
     * 查询订单状态（供前端轮询）
     */
    @GetMapping("/status/{orderId}")
    public Result queryOrderStatus(@PathVariable("orderId") Long orderId) {

        String key = "seckill:order:" + orderId;
        Object statusObj = stringRedisTemplate.opsForHash().get(key, "status");

        if (statusObj == null) {
            return Result.fail("订单不存在或已过期");
        }
        //前端是数字
        Integer status = Integer.parseInt(statusObj.toString());
        return Result.ok(status);
    }
}
