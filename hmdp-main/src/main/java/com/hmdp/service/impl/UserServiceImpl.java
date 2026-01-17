package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.bean.copier.CopyOptions;
import cn.hutool.core.util.RandomUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.LoginFormDTO;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.User;
import com.hmdp.mapper.UserMapper;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RegexUtils;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.BitFieldSubCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.hmdp.utils.RedisConstants.LOGIN_CODE_KEY;
import static com.hmdp.utils.RedisConstants.LOGIN_USER_KEY;
import static com.hmdp.utils.SystemConstants.USER_NICK_NAME_PREFIX;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User> implements IUserService {

    private final StringRedisTemplate stringRedisTemplate;

    public UserServiceImpl(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public Result sendCode(String phone, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(phone)){
            return Result.fail("手机号格式错误!!!");
        };
        String s = RandomUtil.randomNumbers(6);

        //set session
        stringRedisTemplate.opsForValue().set(LOGIN_CODE_KEY + phone, s,10, TimeUnit.MINUTES);
        session.setAttribute("code", s);
        log.debug("发送短信验证码:{}",s);

        return Result.ok();
    }

    @Override
    public Result login(LoginFormDTO loginForm, HttpSession session) {
        if(RegexUtils.isPhoneInvalid(loginForm.getPhone())){
            return Result.fail("手机号格式错误!!!");
        }
        if(!loginForm.getCode().equals(session.getAttribute("code"))||session.getAttribute("code")==null){
            return Result.fail("验证码错误!!");
        }
        //mp的查询这一块
        User user = query().eq("phone", loginForm.getPhone()).one();
        if(user==null){
            user=createUserWithPhone(loginForm.getPhone());
        }
        String token = UUID.randomUUID().toString().replace("-", "");

        UserDTO userDTO= BeanUtil.copyProperties(user,UserDTO.class);
        Map<String, Object> map = BeanUtil.beanToMap(
                userDTO,
                new HashMap<>(),
                CopyOptions.create()
                        .setIgnoreNullValue(true)
                        .setFieldValueEditor((fieldName, fieldValue) -> fieldValue.toString())
        );//一定要注意!!!

        stringRedisTemplate.opsForHash().putAll((String) LOGIN_USER_KEY+token,map);
        stringRedisTemplate.expire("login:token:"+token,300, TimeUnit.MINUTES);
        session.setAttribute("user", user);
        // DEBUG ↓↓↓是这样的
        System.out.println("=== login success ===");
        System.out.println("session id = " + session.getId());
        System.out.println("user saved = " + session.getAttribute("user"));
        return Result.ok(token);
    };;;;;;;;;;;;;;;;;;;;;;;;;;;;

    private User createUserWithPhone(String phone) {
        User user = new User();
        user.setPhone(phone);
        user.setNickName(USER_NICK_NAME_PREFIX+RandomUtil.randomNumbers(10));
        save(user);
        return user;
    }
    @Override
    public Result logout() {
        HttpServletRequest request=((ServletRequestAttributes)
                RequestContextHolder.getRequestAttributes()).getRequest();
        String token=request.getHeader("authorization");
        if (token == null || token.isEmpty()) {
            return Result.fail("未提供 token");
        }
        // 1. 删除 Redis 里的登录信息
        String redisKey = LOGIN_USER_KEY + token;
        stringRedisTemplate.delete(redisKey);
            return Result.ok("退出成功（token 已失效）");
    }

    @Override
    public Result sign() {
        Long userId= UserHolder.getUser().getId();
        LocalDateTime now=LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String key="sign:"+userId+format;
        int day=now.getDayOfMonth();//获取当前是第几天
        stringRedisTemplate.opsForValue().setBit(key,day-1,true);
        return Result.ok();
    }

    @Override
    public Result countTimes() {
        Long userId= UserHolder.getUser().getId();
        LocalDateTime now=LocalDateTime.now();
        String format = now.format(DateTimeFormatter.ofPattern("yyyy/MM"));
        String key="sign:"+userId+format;
        int day=now. getDayOfMonth();
        //获取这一块
        List<Long> result = stringRedisTemplate.opsForValue().bitField(key,
                BitFieldSubCommands.create().get(BitFieldSubCommands.BitFieldType.unsigned(day)).valueAt(0));
        if(result==null||result.isEmpty()){
            return Result.ok(0);
        }//TODO:get(0) and calculate
        Long num=result.get(0);
        int count = 0;
        while (true){
            if((num&1)==0){
                break;
            }else {
                count++;
            }
            num>>=1;
        }
        return Result.ok(count);
    }

}
