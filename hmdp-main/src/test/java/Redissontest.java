import com.hmdp.HmDianPingApplication;
import com.hmdp.service.impl.UserInsertService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.boot.test.context.SpringBootTest;

import javax.annotation.Resource;
import java.io.IOException;

@SpringBootTest(classes = HmDianPingApplication.class)
public class Redissontest {
@Resource
    private UserInsertService userInsertService;
    @Test
    public void insert() {
        userInsertService.insert1000Users();
    }
    @Test
    public void tokenInsert() throws InterruptedException , IOException {
        userInsertService.generateTokensForUsers();
    }
    @Test
    public void toTxt() throws Exception {
        userInsertService.exportAll();
    }
    @Test
    public void toRedis() throws Exception {
        userInsertService.importAll();
    }
}
