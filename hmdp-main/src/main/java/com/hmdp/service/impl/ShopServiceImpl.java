package com.hmdp.service.impl;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisData;
import com.hmdp.utils.SystemConstants;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Autowired
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {


        //表示：把当前对象的 getById 方法 作为一个函数参数, 但不执行。
         Shop shop=cacheClient.queryWithLogicalExpire("cache:shop:",id,Shop.class,this::getById,30L,TimeUnit.SECONDS);
        if(shop==null){
            shop = getById(id);
            System.out.println("first query...");
            if(shop==null){
                return Result.fail("店铺不存在!");
            }
        }
        return Result.ok(shop);
    }

    @Override
    public Shop queryWithMutex(Long id)  {

        String key="cache:shop:" + id;
        String lockKey= null;
        Shop shop = null;
        try {
            String result = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isNotBlank(result)) {
                //存在的话转化为shop,返回
                // System.out.println("use redis");
                return JSONUtil.toBean(result, Shop.class);
            }

            if(result!=null){
                return null;
            }//进入这个分支的时候,如果有真实的数据,那么它在前面一个if已经返回,所以能够到达这里的不是什么好东西()
            //获取锁
            lockKey = "shop:lock:"+id;
            Boolean isLock=tryLock(lockKey);
            if(!isLock){
                Thread.sleep(200);
                return queryWithMutex(id);
            }
            //成功了,新店铺,需要更新的
            shop = getById(id);
            if(shop==null){
                //写空值这一块
                stringRedisTemplate.opsForValue().set(key,"",2, TimeUnit.MINUTES);

                return null;
            }
            stringRedisTemplate.opsForValue().set(key,JSONUtil.toJsonStr(shop),30);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        } finally {
            if(lockKey!=null){unlock(lockKey);}

        }

        return shop;
       // RedisConstants.LOGIN_CODE_KEY;

    }
    private static final ExecutorService executorService = Executors.newFixedThreadPool(10);//线程池
    @Override
    public Shop queryWithLogicalExpire(Long id)  {

        String key="cache:shop:" + id;
            String result = stringRedisTemplate.opsForValue().get(key);
            if (StrUtil.isBlank(result)) {
                // 缓存不存在也要尝试重建
                String lockKey = "shop:lock:" + id;
                Boolean isLock = tryLock(lockKey);
                if (isLock) {
                    executorService.execute(() -> {
                        try {
                            this.saveShop2Redis(id, 30L);
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
        Shop shop = JSONUtil.toBean((JSONObject) redisData.getData(), Shop.class);
        LocalDateTime expireTime=redisData.getExpireTime();
        System.out.println(expireTime+"contrasts "+LocalDateTime.now());
        //没有过期
        if(expireTime==null||expireTime.isAfter(LocalDateTime.now())){
            return shop;
        }
        //过期了,缓存重建,获取互斥锁,判断,开启独立线程(线程池),返回过期的商铺信息
        String lockKey="shop:lock:"+id;
        Boolean isLock=tryLock(lockKey);
        if(isLock){
            executorService.execute(()->{
                try {
                    this.saveShop2Redis(id,30L);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    unlock(lockKey);
                }
            });
        }
        return shop;
   }

    private void unlock(String lockKey) {
        stringRedisTemplate.delete(lockKey);
    }

    private Boolean tryLock(String lockKey) {
        return stringRedisTemplate.opsForValue().setIfAbsent(lockKey, "", 120, TimeUnit.SECONDS);
    }
    private void saveShop2Redis(Long id,Long seconds) {
        Shop shop=getById(id);
        if(shop==null){
            stringRedisTemplate.opsForValue().set("cache:shop:" + id, "", 120, TimeUnit.SECONDS);
        }
        RedisData redisData=new RedisData();
        redisData.setData(shop);
        System.out.println("==== DB 查询结果 ====> " + shop);
        redisData.setExpireTime(LocalDateTime.now().plusSeconds(seconds));

        stringRedisTemplate.opsForValue().set("cache:shop:"+id,JSONUtil.toJsonStr(redisData) );
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result update(Shop shop) {
        //一定要记住!!更新数据库然后删除缓存
        updateById(shop);
        String key="cache:shop:" + shop.getId();
        stringRedisTemplate.delete(key);
        return Result.ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        if(x==null||y==null){
            // 根据类型分页查询
            Page<Shop> page = query()
                .eq("type_id", typeId)
                .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            // 返回数据
            return Result.ok(page.getRecords());
        }
        int from=(current-1)*SystemConstants.DEFAULT_PAGE_SIZE;
        int end=current*SystemConstants.DEFAULT_PAGE_SIZE;
        String key="shop:geo:"+typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo()
                .search(key, GeoReference.fromCoordinate(x, y)
                        , new Distance(5000),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs().includeDistance().limit(end));
        if(results==null){
            return Result.ok(Collections.emptyList());
        }
        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> content = results.getContent();
        if(content.size()<=from){
            return Result.ok(Collections.emptyList());
        }
        //截取
        List<Long> ids=new ArrayList<>(content.size());
        Map<String,Distance> distanceMap=new HashMap<>(content.size());
        content.stream().skip(from)
                .forEach(result->{
                    String shopIdStr=result.getContent().getName();
                    ids.add(Long.valueOf(shopIdStr));
                    Distance distance =result.getDistance();
                    distanceMap.put(shopIdStr,distance);
                });
        String idStr = StrUtil.join(",", ids);
        List<Shop> shops = query().in("id", ids).last("order by field(id," + idStr + ")").list();
        for(Shop shop:shops){
            shop.setDistance(distanceMap.get(shop.getId().toString()).getValue());
        }

        return Result.ok(shops) ;
    }
}
