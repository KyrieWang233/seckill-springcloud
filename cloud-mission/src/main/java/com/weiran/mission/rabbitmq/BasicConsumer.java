package com.weiran.mission.rabbitmq;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rabbitmq.client.Channel;
import com.weiran.common.enums.RedisCacheTimeEnum;
import com.weiran.common.exception.BaseCustomizeException;
import com.weiran.common.redis.key.SeckillGoodsKey;
import com.weiran.common.redis.key.SeckillKey;
import com.weiran.common.redis.manager.RedisService;
import com.weiran.mission.manager.OrderManager;
import com.weiran.mission.pojo.entity.MqMessageLog;
import com.weiran.mission.pojo.entity.Order;
import com.weiran.mission.service.MqMessageService;
import com.weiran.mission.service.SeckillGoodsService;
import com.weiran.mission.utils.SnowflakeIdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.AmqpHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;

/**
 * rabbitmq demo-消费者
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BasicConsumer {

    public static final int MAX_RETRY_COUNT = 3;

    final OrderManager orderManager;
    final SeckillGoodsService seckillGoodsService;
    private final RedisService redisService;
    private final MqMessageService mqMessageService;
    private final SnowflakeIdGenerator idGenerator = new SnowflakeIdGenerator(1, 1);

    /**
     * 监听并接收消费队列中的消息-在这里采用单一容器工厂实例即可
     * todo 返回请求的格式（考虑前端给一个轮询接口持续查询订单状态）
     */
    @RabbitListener(queues = RabbitMqConstants.BASIC_QUEUE, containerFactory = "singleListenerContainer")
    // 设置消费者监听的队列以及监听的消息容器
    public void consumeMsg(SeckillMessage seckillMessage, Channel channel, @Header(AmqpHeaders.DELIVERY_TAG) Long tag) throws IOException {
        log.info("rabbitmq demo-消费者-监听消息：{} ", JSONUtil.toJsonStr(seckillMessage));
        //消息记录表处理更新的异常，直接丢弃
        try {
            //判断并记录消息队列重试次数
            Integer retryCount = mqMessageService.getRetryCount(seckillMessage.getMessageId());
            //记录表里找不到直接丢弃
            if (retryCount == null) {
                redisService.increase(SeckillGoodsKey.seckillCount, String.valueOf(seckillMessage.getGoodsId()));
                handleSuccess(seckillMessage, channel,  tag);
                return;
            }
            if (retryCount > MAX_RETRY_COUNT) {
                log.error("消息重试次数达到上限，丢弃消息：{}", JSONUtil.toJsonStr(seckillMessage));
                //此条秒杀请求彻底失败，回退之前预扣减的库存并清除标记
                redisService.delete(SeckillKey.isRepeat, "temp:" + seckillMessage.getGoodsId() + ":" + seckillMessage.getUserId());
                redisService.delete(SeckillKey.isRepeat, seckillMessage.getGoodsId() + ":" + seckillMessage.getUserId());
                handleFailure(seckillMessage, channel, tag);
                return;
            } else {
                mqMessageService.incrementRetryCount(seckillMessage.getMessageId());
            }
        } catch (Exception e) {
            log.error("rabbitmq demo-消息记录统计-发生异常：", e);
            handleFailure(seckillMessage, channel, tag);
            return;
        }
        //库存扣减、订单生成部分的异常可以重新入队
        try {
            long userId = seckillMessage.getUserId();
            long goodsId = seckillMessage.getGoodsId();
            //改进1:校验幂等性，检查订单是否已存在
            if (orderManager.count(Wrappers.<Order>lambdaQuery()
                    .eq(Order::getUserId, userId)
                    .eq(Order::getGoodsId, goodsId)) > 0) {
                log.warn("订单已存在，丢弃重复消息：{}", JSONUtil.toJsonStr(seckillMessage));
                redisService.increase(SeckillGoodsKey.seckillCount, String.valueOf(seckillMessage.getGoodsId()));
                handleSuccess(seckillMessage, channel, tag);
                return;
            }
            //改进2:扣减库存，加入错误回滚。并用乐观锁保证原子性
            boolean stockDeductionSuccess = seckillGoodsService.reduceStock(goodsId);
            if (!stockDeductionSuccess) {
                log.warn("库存不足：{}", JSONUtil.toJsonStr(seckillMessage));
                channel.basicNack(tag, false, true); // 重新入队
                return;
            }
            //改进3:订单生成逻辑，分布式全局唯一ID
            Long orderId = generateOrderId(goodsId, userId);
            boolean createOrderSuccess = seckillGoodsService.createOrder(goodsId, orderId, userId);
            if (!createOrderSuccess) {
                log.warn("库存不足或订单生成失败：{}", JSONUtil.toJsonStr(seckillMessage));
                // todo 如果回归库存失败逻辑上就不好处理了，因此接入分布式事务是更加完善的方法（但是增加了项目的复杂性）
                seckillGoodsService.increaseStockCount(goodsId);
                //这里mysql中库存已经扣减，订单生成错误需要回滚库存。
                channel.basicNack(tag, false, true);
                return;
            }
            log.info("成功消费消息：{}", JSONUtil.toJsonStr(seckillMessage));
            // 消费成功，设置永久幂等性参数防止重复秒杀
            redisService.set(SeckillKey.isRepeat, seckillMessage.getGoodsId() + ":" + seckillMessage.getUserId(),
                    "1", RedisCacheTimeEnum.LOGIN_EXTIME.getValue());
            handleSuccess(seckillMessage, channel, tag);
            //改进4: 订单支付问题，延迟队列处理15分钟内未支付问题。）
            //todo sendOrderTimeoutMessage(orderId);
        } catch (Exception e) {
            // 改进：失败后重新入队，防止消息丢失（配置重试次数防止无限循环）
            // 潜在问题，如果重视次数增加之前出现异常导致未更新数据库可能会无限重试。(可以尝试在发送端控制来重发消息）
            channel.basicNack(tag, false, true);
            log.error("rabbitmq demo-消费者-发生异常：", e);
        }
    }

    private Long generateOrderId(long goodsId, long userId) {
        // 生成全局唯一订单 ID（例如基于雪花算法或 UUID）
        return idGenerator.nextId();
    }

    private boolean shouldRequeue(Exception e) {
        // 判断是否应该重新入队，例如对于非业务异常可重新入队
        return !(e instanceof BaseCustomizeException);
    }

    private void handleSuccess(SeckillMessage seckillMessage, Channel channel, Long tag) {
        try{
            channel.basicAck(tag, false);
        }catch (Exception e){
            log.error("rabbitmq-消息确认-发生异常：", e);
        }
        MqMessageLog messageLog = new MqMessageLog();
        messageLog.setStatus(MqMessageLog.STATUS_SUCCESS);
        messageLog.setMessageId(seckillMessage.getMessageId());
        mqMessageService.updateMqMessageLog(messageLog);
    }

    private void handleFailure(SeckillMessage seckillMessage, Channel channel, Long tag) {
        redisService.increase(SeckillGoodsKey.seckillCount, String.valueOf(seckillMessage.getGoodsId()));
        try{
            channel.basicReject(tag, false);
        }catch (Exception e){
            log.error("rabbitmq-消息确认-发生异常：", e);
        }
        MqMessageLog messageLog = new MqMessageLog();
        messageLog.setStatus(MqMessageLog.STATUS_FAIL);
        messageLog.setMessageId(seckillMessage.getMessageId());
        messageLog.setLastRetryTime(LocalDateTime.now());
        mqMessageService.updateMqMessageLog(messageLog);
    }
}
