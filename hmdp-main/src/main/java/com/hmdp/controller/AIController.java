package com.hmdp.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.ChatMessage;
import com.hmdp.entity.User;
import com.hmdp.mapper.AIMapper;
import com.hmdp.utils.UserHolder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;


import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/*
/ai/chat  post
 */
@RestController
@RequestMapping("/ai")
public class AIController {
    @Autowired
    private RestTemplate restTemplate;
    @Autowired
    private AIMapper aiMapper;
    private static final String KIMI_API_KEY = "****";//换成你自己的key
    private static final String BASE_URL = "https://api.moonshot.cn/v1/chat/completions";
    @PostMapping("/chat")
    public Result chat(@RequestBody Map<String, String> body) {
        String userMessage = body.get("question");
        if (userMessage == null || userMessage.trim().isEmpty()) {
            return Result.fail("请输入有效问题");
        }
        UserDTO user = UserHolder.getUser();
        if(user == null) {
            return Result.ok("请先登录");
        }
        // 新建请求体，不要修改前端 body
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", "moonshot-v1-8k");  // 或 "kimi-k2-turbo-preview"（更强，但稍贵）

        List<Map<String, String>> messages = new ArrayList<>();
        // 加 system prompt，让回答更贴合你的应用
        messages.add(Map.of(
                "role", "system",
                "content", "你是黑马点评的本地生活 AI 助手，专注于中国城市（尤其是杭州）的吃喝玩乐、约会、旅游推荐。用友好、简洁、活泼的中文回复，最好不超过250字"
        ));
        messages.add(Map.of("role", "user", "content", userMessage));

        requestBody.put("messages", messages);
        requestBody.put("temperature", 0.7);
        requestBody.put("max_tokens", 1500);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(KIMI_API_KEY);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

        try {
            ResponseEntity<Map> response = restTemplate.postForEntity("https://api.moonshot.cn/v1/chat/completions", entity, Map.class);

            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                Map<?, ?> respBody = response.getBody();
                List<?> choices = (List<?>) respBody.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<?, ?> first = (Map<?, ?>) choices.get(0);
                    Map<?, ?> msg = (Map<?, ?>) first.get("message");
                    ChatMessage cm=new ChatMessage();
                    cm.setUserId(UserHolder.getUser().getId());
                    cm.setContent(msg.get("content").toString());
                    cm.setCreateTime(LocalDateTime.now());
                    cm.setQuestion(userMessage);
                    aiMapper.insert(cm);
                    return Result.ok((String) msg.get("content"));
                }
                return Result.fail("Kimi 返回格式异常");
            } else {
                return Result.fail("Kimi API 错误: " + response.getStatusCodeValue());
            }
        } catch (Exception e) {
            e.printStackTrace();  // 打印到控制台，便于你看错误
            return Result.fail("请求 Kimi 失败：" + e.getMessage());
        }
    }
    @GetMapping("/history")
    public Result getHistory() {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        Long userId = user.getId();

        LambdaQueryWrapper<ChatMessage> wrapper = new LambdaQueryWrapper<>();
        wrapper.eq(ChatMessage::getUserId, userId)
                .orderByDesc(ChatMessage::getCreateTime);

        // 查询所有记录
        List<ChatMessage> list = aiMapper.selectList(wrapper);

        return Result.ok(list);
    }

}
