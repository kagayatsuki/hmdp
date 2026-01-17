package com.hmdp.utils;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.hmdp.entity.Shop;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

@Slf4j
@Component
public  class CacheClient {
    private final StringRedisTemplate stringRedisTemplate;

    public CacheClient(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void set(String key, Object value,Long time, TimeUnit timeUnit) {
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(value), time, timeUnit);
    }
    public void setWithLogicalExpire(String key, Object value,Long time, TimeUnit timeUnit) {
        RedisData redisData = new RedisData();
        redisData.setData(value);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(timeUnit.toSeconds(time)));
        stringRedisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(redisData));
    }
    ///<R<ID>定义泛型,R是返回值类型,fun里面输入一个 ID，返回一个 R,Class<R> type —— 用于 JSON 反序列化
    public <R,ID> R queryWithPassThrough
            (String keyPrefix, Class<R> type, ID id, Function<ID,R> dbFallBack,Long time, TimeUnit timeUnit) {
        String key=keyPrefix+id.toString();

        String jsonStr = stringRedisTemplate.opsForValue().get(key);
        if(StrUtil.isNotBlank(jsonStr)) {
            return JSONUtil.toBean((JSONObject) JSONUtil.toBean(jsonStr, RedisData.class).getData(), type);
        }
        if(jsonStr!=null) {
            return null;
        }
        //查询数据库
        R r = dbFallBack.apply(id);
        if(r==null){
            stringRedisTemplate.opsForValue().set(key, "",2,TimeUnit.MINUTES);
            return null;
        }
        this.set(key,r,time,timeUnit);
        return r;
    }
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);//线程池
    public <R,ID> R queryWithLogicalExpire(String prefix,ID id,Class<R> type,Function<ID,R>dbFallBack,Long time, TimeUnit timeUnit)  {

        String key=prefix + id;
        String result = stringRedisTemplate.opsForValue().get(key);
        if (StrUtil.isBlank(result)) {
            // 缓存不存在也要尝试重建
            String lockKey = "shop:lock:" + id;
            Boolean isLock = tryLock(lockKey);
            if (isLock) {
                executorService.execute(() -> {
                    try {
                        R r = dbFallBack.apply(id);
                        this.setWithLogicalExpire(key,r,time,timeUnit);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    } finally {
                        unlock(lockKey);
                    }
                });
            }
            return null; // 返回空，让用户稍后再查
        }
        RedisData redisData =JSONUtil.toBean(result, RedisData.class);
        R r= JSONUtil.toBean((JSONObject) redisData.getData(), type);
        LocalDateTime expireTime=redisData.getExpireTime();
        System.out.println(expireTime+" contrasts "+LocalDateTime.now());
        //没有过期
        if(expireTime==null||expireTime.isAfter(LocalDateTime.now())){
            System.out.println("has not expired");
            return r;
        }
        //过期了,缓存重建,获取互斥锁,判断,开启独立线程(线程池),返回过期的商铺信息
        System.out.println("has expired");
        String lockKey="shop:lock:"+id;
        Boolean isLock=tryLock(lockKey);
        if(isLock){
            executorService.execute(()->{
                try {
                    R r1 = dbFallBack.apply(id);
                    this.setWithLogicalExpire(key,r1,time,timeUnit );
                    System.out.println("后台更新完成 shop:" + id);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return r;
    }
    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    private Boolean tryLock(String lockKey) {
        //var q=new ArrayList<ILock>();
        return stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", 120, TimeUnit.SECONDS);
    }



    //List


}
