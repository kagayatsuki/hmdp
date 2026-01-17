package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.service.IVoucherOrderService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
@Service
public class OrderMessageConsumer {
    @Resource
    private IVoucherOrderService voucherOrderService;
    @KafkaListener(topics = "orders",groupId = "order-consumer-group")
    public void handleMessage(String message) {
        JSONObject jsonObject = new JSONObject(message);
        Long orderId = jsonObject.getLong("orderId");
        Long userId = jsonObject.getLong("userId");
        Long voucherId = jsonObject.getLong("voucherId");
        voucherOrderService.createVoucherOrderKafka(orderId, userId, voucherId);

    }
}
