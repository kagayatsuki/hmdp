package com.hmdp.service.impl;


import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
//import cn.hutool.core.lang.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@Service
public class UserInsertService {

    @Resource
    private UserMapper userMapper;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    private static final String BACKUP_PATH = "C:\\Users\\ASUS\\Desktop\\hmdp\\hmdp-main\\data.txt";
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final String LOGIN_USER_KEY = "login:token:";
    public void insert1000Users() {
        List<User> list = new ArrayList<>();

        for (int i = 0; i < 1000; i++) {
            User u = new User();

            String phone = "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(0, 10_0000_0000L));
            u.setPhone(phone);


            // 生成昵称
            long rand = ThreadLocalRandom.current().nextLong(100000000L, 999999999L);
            u.setNickName("user_" + rand);

            // 不设置密码和icon，即不插入，让数据库默认值生效

            u.setCreateTime(LocalDateTime.now());
            u.setUpdateTime(LocalDateTime.now());

            list.add(u);
        }

        // 批量插入（MyBatis-Plus 自动分批）
        list.forEach(userMapper::insert);

    }


    public void generateTokensForUsers() throws IOException {
        // 1. 查询 ID 为 3022 ~ 4000 的用户
        List<User> users = userMapper.selectList(
                new LambdaQueryWrapper<User>()
                        .between(User::getId, 3022, 4000)
        );

        if (users == null || users.isEmpty()) {
            System.out.println("❌ 没有查询到指定范围内的用户！");
            return;
        }

        System.out.println("👍 查询用户数量：" + users.size());

        // 2. 准备写入 token.txt 文件（相对路径）
        File file = new File("token.txt");
        if (!file.exists()) {
            file.createNewFile();
        }

        BufferedWriter writer = new BufferedWriter(new FileWriter(file, true)); // 追加写入

        // 3. 遍历生成 token、写入 Redis + 写入文件
        for (User user : users) {
            // 生成 token（保持与你登录一致）
            String token = UUID.randomUUID().toString().replace("-", "");

            // 转 DTO → Map（与你登录完全一致）
            UserDTO userDTO = BeanUtil.copyProperties(user, UserDTO.class);
            Map<String, Object> map = BeanUtil.beanToMap(
                    userDTO,
                    new HashMap<>(),
                    CopyOptions.create()
                            .setIgnoreNullValue(true)
                            .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
            );

            // 写入 Redis（Hash）
            stringRedisTemplate.opsForHash().putAll(LOGIN_USER_KEY + token, map);
            stringRedisTemplate.expire(LOGIN_USER_KEY + token, 30, TimeUnit.MINUTES);

            // 写入 token.txt（只写 token，换行）
            writer.write(token);
            writer.newLine(); // 换行
        }

        writer.flush();
        writer.close();

        System.out.println("🎉 批量生成完成！文件已写入 token.txt");
    }



    public void exportAll() throws Exception {
        Set<String> keys = stringRedisTemplate.keys("*");
        if (keys == null) keys = new HashSet<>();

        List<Map<String, Object>> result = new ArrayList<>();

        for (String key : keys) {
            String type = Objects.requireNonNull(stringRedisTemplate.getConnectionFactory())
                    .getConnection().type(key.getBytes()).toString();

            Map<String, Object> item = new HashMap<>();
            item.put("key", key);
            item.put("type", type);

            switch (type) {
                case "string":
                    item.put("value", stringRedisTemplate.opsForValue().get(key));
                    break;

                case "hash":
                    item.put("value", stringRedisTemplate.opsForHash().entries(key));
                    break;

                case "list":
                    item.put("value", stringRedisTemplate.opsForList().range(key, 0, -1));
                    break;

                case "set":
                    item.put("value", stringRedisTemplate.opsForSet().members(key));
                    break;

                case "zset":
                    item.put("value", stringRedisTemplate.opsForZSet().rangeWithScores(key, 0, -1));
                    break;
            }

            result.add(item);
        }

        // 写入文件
        FileWriter fw = new FileWriter(new File(BACKUP_PATH));
        fw.write(mapper.writeValueAsString(result));
        fw.close();

        System.out.println("已成功导出到: " + BACKUP_PATH);
    }

    public void importAll() throws Exception {

        System.out.println("===== 开始导入 Redis 数据 =====");
        System.out.println("Redis 连接信息：");
        System.out.println(stringRedisTemplate.getConnectionFactory().getConnection().info("server"));

        File file = new File(BACKUP_PATH);
        if (!file.exists()) {
            System.out.println("⚠ 备份文件不存在！");
            return;
        }

        List<Map<String, Object>> list =
                mapper.readValue(file, new TypeReference<List<Map<String, Object>>>() {
                });

        System.out.println("读取到条目：" + list.size());

        for (Map<String, Object> item : list) {
            String key = (String) item.get("key");
            String type = (String) item.get("type");
            Object value = item.get("value");

            System.out.println("正在写入 key：" + key + " 类型：" + type);

            switch (type) {
                case "string":
                    stringRedisTemplate.opsForValue().set(key, (String) value);
                    break;

                case "hash":
                    Map<String, String> hash = mapper.convertValue(value,
                            new TypeReference<Map<String, String>>() {
                            });
                    stringRedisTemplate.opsForHash().putAll(key, hash);
                    break;

                case "list":
                    List<String> listValue = mapper.convertValue(value,
                            new TypeReference<List<String>>() {
                            });
                    stringRedisTemplate.opsForList().rightPushAll(key, listValue);
                    break;

                case "set":
                    Set<String> setValue = mapper.convertValue(value,
                            new TypeReference<Set<String>>() {
                            });
                    stringRedisTemplate.opsForSet().add(key, setValue.toArray(new String[0]));
                    break;

                case "zset":
                    List<Map<String, Object>> zsetList =
                            mapper.convertValue(value, new TypeReference<List<Map<String, Object>>>() {
                            });
                    for (Map<String, Object> entry : zsetList) {
                        String member = (String) entry.get("value");
                        Double score = (Double) entry.get("score");
                        stringRedisTemplate.opsForZSet().add(key, member, score);
                    }
                    break;
            }
        }

        System.out.println("===== 导入完成 =====");
    }

}
