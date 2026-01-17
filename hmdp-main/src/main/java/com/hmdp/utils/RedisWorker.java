package com.hmdp.utils;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

@Component
public class RedisWorker {
    private static final long BASE_TIME= LocalDateTime.of(2024,1,1,0,0).toEpochSecond(ZoneOffset.UTC);
    private static final int COUNT = 32;
    //时间戳,序列号,拼接返回
    private StringRedisTemplate redisTemplate;
    public RedisWorker(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }
    public long nextId(String prefix){
        long nowSec = LocalDateTime.now().toEpochSecond(ZoneOffset.UTC);
        long timeSec = nowSec-BASE_TIME;
        String curDate= LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        long count = redisTemplate.opsForValue().increment("icr:" + prefix + ":" + curDate);
        return timeSec<<COUNT|count;

    }
}
