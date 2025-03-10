# 基于SpringCloud的秒杀项目

## 前言
项目是基于Java微服务方案的商品秒杀系统。是前后端分离的项目。
前端用React完成web前端；后端为Java的Spring cloud微服务架构，客户端用flutter跨平台完成mobile端，小程序端用uniapp完成。

学习交流项目，感谢开源分享，原项目地址[seckillcloud](https://github.com/weiraneve/seckillcloud)。经过一些资料的学习，具体后端的秒杀业务做了一些调整修改。 项目前端直接使用源项目即可。

## 秒杀业务思路
```
【用户请求】
↓
【前端防刷】（限流+验证码）
↓
【秒杀服务】（校验 path + Redis 预扣减库存 + 临时幂等）
↓
【消息队列】（削峰填谷，异步处理订单）
↓
【订单服务】（数据库扣减库存，创建订单）
↓
【最终 Redis 幂等标记】（防止重复秒杀）
```
**具体步骤**  
**1、用户身份获取与秒杀路径验证**  
**2、内存标记**  
在内存中标记商品是否已经售罄，减少对 Redis 的访问压力。  
**3、临时 Redis 幂等标记（防止用户短时间重复提交）**  
通过用户 ID 和商品 ID 生成唯一的订单号，防止重复秒杀。  
**4、Redis + Lua 脚本库存扣减**  
使用 Lua 脚本保证库存扣减的原子性。  
风险点：如果redis挂掉，整个服务会不可用  
**5、消息队列（MQ）异步下单（削峰填谷）**  
将秒杀请求通过消息队列异步处理。  
消息队列会将秒杀请求转发给后续的订单创建服务  
**6、数据库乐观锁 + Redis 回滚（保证最终一致性）**  
**7、最终 Redis 幂等标记（防止重复秒杀）**  

## 一些改进想法
### mq异步处理优化
1. 增加mq消息发送表记录状态，防止重复消息幂等性问题。
2. 生产者使用job，增加重试机制，防止消息丢失、redis库存不一致问题。
3. 模拟增加下单功能，使用延迟队列处理订单超时问题。

### 秒杀后消费逻辑优化
#### 1、订单生成逻辑
生成全局唯一ID（使用雪花算法）。 
#### 2、幂等性检查
使用redis减少直接查询数据库。
+ 生成唯一ID后在redis中标记该用户已经秒杀过商品。例如："seckill:" + goodsId + ":" + userId。
+ 用户点击秒杀链接后，生成短时效的redis标识，防止mq处理延迟期间，用户再次快速批量请求。

注意的问题：
+ 设置redis key的有效时间。
+ MQ 消费端需要确保足够的性能和处理能力，否则可能影响整体秒杀体验。
+ 如果标记延迟到消费端，用户可能在秒杀接口调用成功后（即入队后）短时间内还能重复提交请求（接口中无法通过 Redis Key 判断幂等性）。

#### 3、多数据源事务问题
扣减库存、生成订单是一个较为经典的多数据源sql操作的场景，可以考虑使用分布式事务（但是考虑到目前这里sql业务比较简单，直接顺序try catch回滚性能开销比较小，增加了逻辑复杂性）。增加重试回滚机制。
#### 4、扣减库存使用乐观锁来保证原子性
sql语句示例如下：
```sql
UPDATE seckill_goods
SET stock_count = stock_count - 1,
    version = version + 1
WHERE goods_id = #{goodsId} AND version = #{version} AND stock_count > 0;
```
## License
Licensed under either of [MIT License](./LICENSE) at your option.
