package com.hmdp.utils;

import cn.hutool.json.JSONObject;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;

@Service
public class OrderMessageProducer {
    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;
    public void sendOrder(Long orderId,Long userId,Long voucherId) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("orderId", orderId);
        jsonObject.put("userId", userId);
        jsonObject.put("voucherId", voucherId);
        kafkaTemplate.send("orders", jsonObject.toString());
    }
}
