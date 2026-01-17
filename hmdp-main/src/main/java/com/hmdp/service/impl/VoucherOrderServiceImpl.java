package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.*;
import org.jetbrains.annotations.NotNull;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.aop.framework.AopContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

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
    @Resource
    private RedisWorker redisWorker;
    @Autowired
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private OrderMessageProducer orderMessageProducer;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result seckillVoucher(Long voucherId) {
        //1.获取优惠券
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        //2.判断开始,结束,充足
        if(seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())){
            return Result.fail("秒杀尚未开始!");
        }
        if(seckillVoucher.getEndTime().isBefore(LocalDateTime.now())){
            return Result.fail("秒杀已结束!");
        }
        if(seckillVoucher.getStock()<1){
            return Result.fail("优惠券已售罄!");
        }
        //int Coupons=1;
        Long userId2=UserHolder.getUser().getId();
        //分布式锁:
      //  SimpleRedisLock simpleRedisLock=new SimpleRedisLock(stringRedisTemplate,"order:"+userId2+voucherId);
        //下面基于redisson实现:
        RLock lock = redissonClient.getLock("lock:order:" + userId2 + voucherId);
        boolean isLock = lock.tryLock();
        if(!isLock){
            return Result.fail("不允许重复下单!!");
        }
        try{
        synchronized (userId2.toString().intern()){
            long orderId=redisWorker.nextId("order:");
            //发送预请求到redis
            String key = "seckill:order:" + orderId;
            stringRedisTemplate.opsForHash().put(key, "status", "0");
            stringRedisTemplate.expire(key, 5, TimeUnit.MINUTES);
            orderMessageProducer.sendOrder(orderId,userId2,voucherId);
            System.out.println(">> 已发送MQ消息");
        /*     IVoucherOrderService o= (IVoucherOrderService) AopContext.currentProxy();
        return o.createVoucherOrder(voucherId);}  mq直接返回结果      */
             return Result.ok(StrUtil.toString(orderId));}
        }
        finally {
            lock.unlock();
        }
    }
    @Transactional
    //悲观锁这一块
    public Result createVoucherOrder(Long voucherId) {
        //一人一单
        Long userId2=UserHolder.getUser().getId();
        if(UserHolder.getUser().getId()==null){
            userId2=0L;
        }
        long count=query().eq("user_id",userId2).eq("voucher_id", voucherId).count();

        if(count>0){
            return Result.fail("用户已购买!!");
        }


        //3.更新
        boolean success = seckillVoucherService.update()
                .setSql("stock=stock-1")
                .eq("voucher_id", voucherId)
                .gt("stock",0)
                .update();
        if(!success){
            return Result.fail("优惠券已售罄!!");
        }
        
        //4.创建订单
        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId= redisWorker.nextId("order:");
        voucherOrder.setId(orderId);
        Long userId= null;//用户id

            userId = UserHolder.getUser().getId();

        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);

        //返回订单id
        return Result.ok(orderId);
    }
    @Override
    @Transactional
    public void createVoucherOrderKafka(Long orderId, Long userId, Long voucherId) {
        String key = "seckill:order:" + orderId;
        // 一人一单（保证幂等性）
        long count = query()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .count();

        if (count > 0) {
            stringRedisTemplate.opsForHash().put(key, "status", "2"); // 重复下单
            return; // Kafka 重复消息也不怕
        }

        // 使用乐观锁扣库存
        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();

        if(!success){
            stringRedisTemplate.opsForHash().put(key, "status", "3");
            return;  // 库存不足，丢掉该订单（正常）
        }

        // 创建订单
        VoucherOrder order = new VoucherOrder();
        order.setId(orderId);
        order.setUserId(userId);
        order.setVoucherId(voucherId);

        save(order);
        stringRedisTemplate.opsForHash().put(key, "status", "1");
        stringRedisTemplate.expire("seckill:order:" + orderId, 5, TimeUnit.MINUTES);
    }


}
