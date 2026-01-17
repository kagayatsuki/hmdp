import com.hmdp.HmDianPingApplication;
import com.hmdp.entity.Shop;
import com.hmdp.service.IShopService;
import com.hmdp.utils.RedisWorker;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.core.StringRedisTemplate;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

@SpringBootTest(classes = HmDianPingApplication.class)
public class Tester {
    @Resource
    private RedisWorker redisWorker;
    @Resource
    private IShopService shopService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
     private ExecutorService executor= Executors.newFixedThreadPool(100);

     @Test
    void testWorker()throws InterruptedException {
         CountDownLatch countDownLatch = new CountDownLatch(50);
         Runnable task=()->{
             for(int i=0;i<100;i++){
                 long id=redisWorker.nextId("order:");
                 System.out.println("id: "+id);

             }countDownLatch.countDown();};

         long start=System.currentTimeMillis();
         for(int i=0;i<100;i++){
             executor.submit(task);
         }
         countDownLatch.await();
         long end=System.currentTimeMillis();
         System.out.println("costs "+(end-start)+"ms");
     }
     @Test
     void loadShopData(){
         List<Shop> list=shopService.list();
         Map<Long,List<Shop>> map=list.stream().collect(Collectors.groupingBy(Shop::getTypeId));
        //分批写入redis
         for (Map.Entry<Long,List<Shop>> entry:map.entrySet()){
             Long typeId=entry.getKey();
             String key="shop:geo:"+typeId;
             List<Shop> shopList=entry.getValue();
             //redis
             for(Shop shop:shopList){
                 stringRedisTemplate.opsForGeo().add(key,new Point(shop.getX(),shop.getY()),shop.getId().toString());
             }
         }
     }
}
