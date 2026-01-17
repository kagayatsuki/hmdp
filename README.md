黑马点评初步完工版本，包括redis缓存，kafka消息队列等等核心技术

1.redis版本8.4.0

2.kafka默认使用wsl运行(下面只是本机示例)
```palintext
# 1. 进入目录
cd /mnt/c/Users/ASUS/Desktop/kafka/kafka

# 2. 直接启动（两个终端）
# 终端1:
./bin/zookeeper-server-start.sh ./config/zookeeper.properties

# 终端2 (等10秒后):
./bin/kafka-server-start.sh ./config/server.properties
```
