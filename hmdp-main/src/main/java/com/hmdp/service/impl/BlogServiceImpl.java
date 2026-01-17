package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.BooleanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.conditions.query.QueryChainWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.UserHolder;
import org.apache.kafka.common.protocol.types.Field;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * <p>
 *  服务实现类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {
    @Resource
    private IUserService userService;
    @Resource
    private StringRedisTemplate stringRedisTemplate;
    @Resource
    private IFollowService followService;
    @Override
    public Result queryBlogById(Long id) {
        Blog blog=getById(id);
        if(blog==null){
            return Result.fail("笔记不存在");
        }
        Long userId=blog.getUserId();
        User user=userService.getById(userId);
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
        isBlogLiked(blog);
        return Result.ok(blog)                                  ;
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if(user==null){
            return ;
        }

        Long userId= UserHolder.getUser().getId();
        String key="blog:like:"+blog.getId();
        Double score=stringRedisTemplate.opsForZSet().score(key,userId.toString());
        //防止null带来的异常
        blog.setIsLike(score!=null);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        Long userId= UserHolder.getUser().getId();
        String key="blog:like:"+id;
        ///注意是使用score,而不是普通集合的isMember
        Double score = stringRedisTemplate.opsForZSet().score(key, userId.toString());
        if(score==null){
            //点赞数+1
            boolean isUpdated = update().setSql("liked=liked+1").eq("id", id).update();
            //升级版,使用zset找到最早点赞的
            if(isUpdated){
                stringRedisTemplate.opsForZSet().add(key,userId.toString(),System.currentTimeMillis());
            }
        }else {
            //-1
            boolean isUpdated = update().setSql("liked=liked-1").eq("id", id).update();
            if(isUpdated){
                stringRedisTemplate.opsForZSet().remove(key,userId.toString());
            }
        }
        return Result.ok(id);
    }

    /*
    查询top5的点赞用户
     */


    @Override
    public Result queryBlogLikes(Long id) {
        Set<String> users = stringRedisTemplate.opsForZSet().range("blog:like:" + id, 0, 4);
        if(users==null){
            return Result.ok(Collections.emptyList());
        }
        //解析里面的5个id
        List<Long> ids=users.stream().map(Long::valueOf).collect(Collectors.toList());
        String idStr= StrUtil.join(",",ids);
        List<UserDTO> collect = userService.query().in("id",ids).last("order by field(id, "+idStr+")").list()
        .stream()
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(collect) ;
    }

    @Override
    public Result saveBlog(Blog blog) {
        UserDTO user = UserHolder.getUser();
        blog.setUserId(user.getId());
        // 保存探店博文
        boolean saved = save(blog);
        if(!saved){
            return Result.fail("上传失败!");
        }//查询粉丝并推送,follow是用户关注的id
        List<Follow> follows = followService.query().eq("follow_user_id", user.getId()).list();
        for(Follow follow:follows){
            Long userId=follow.getUserId();//fans
            String key="feed:" +userId;
            stringRedisTemplate.opsForZSet().add(key,blog.getId().toString(),System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogOfFollow(Long max, Integer offset) {
        //获取当前用户,查询收件箱,  解析数据mintime,offset,   id查询blog,封装返回
        //ZREVRANGEBYSCORE key max min LIMIT offset count
        Long userId=UserHolder.getUser().getId();
        String key="feed:"+userId;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, max, offset, 3);
        //score 是从max->0查询的,一次查询3个
        //empty
        if(typedTuples==null||typedTuples.isEmpty()){
            return Result.ok();
        }
        //ids we need
        List<Long>ids=new ArrayList<>(typedTuples.size());
        long minTime=0;//本批结果中最早的时间戳
        int os=1;
        for(ZSetOperations.TypedTuple<String> typedTuple:typedTuples){
            ids.add(Long.valueOf(typedTuple.getValue()));
            long time=typedTuple.getScore().longValue();
            if(time==minTime){
                os++;
            }else {
                minTime=time;
                os=1;
            }
        }
        String idStr=StrUtil.join(",",ids);
        List<Blog> blogs = query().in("id", ids).last("order by field(id, " + idStr + ")").list();
        for(Blog blog:blogs){
            Long userId2=blog.getUserId();
            User user=userService.getById(userId2);
            blog.setName(user.getNickName());
            blog.setIcon(user.getIcon());
            isBlogLiked(blog);
        }
        ScrollResult r=new ScrollResult();
        r.setList(blogs);
        r.setOffset(os);
        r.setMinTime(minTime);
        return Result.ok(r);
    }
}
