package com.weiran.mission.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.weiran.common.enums.RedisCacheTimeEnum;
import com.weiran.common.enums.ResponseEnum;
import com.weiran.common.obj.Result;
import com.weiran.common.pojo.dto.GoodsDTO;
import com.weiran.common.redis.key.SeckillGoodsKey;
import com.weiran.common.redis.key.SeckillKey;
import com.weiran.common.redis.key.UserKey;
import com.weiran.common.redis.manager.RedisLua;
import com.weiran.common.redis.manager.RedisService;
import com.weiran.common.utils.AuthUtil;
import com.weiran.common.utils.SM3Util;
import com.weiran.mission.manager.OrderManager;
import com.weiran.mission.manager.SeckillGoodsManager;
import com.weiran.mission.pojo.entity.MqMessageLog;
import com.weiran.mission.pojo.entity.Order;
import com.weiran.mission.pojo.entity.SeckillGoods;
import com.weiran.mission.rabbitmq.BasicPublisher;
import com.weiran.mission.rabbitmq.SeckillMessage;
import com.weiran.mission.service.MqMessageService;
import com.weiran.mission.service.SeckillService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillServiceImpl implements SeckillService {

    private final RedisService redisService;
    private final SeckillGoodsManager seckillGoodsManager;
    private final OrderManager orderManager;
    private final BasicPublisher messageSender;
    private final RedisLua redisLua;
    final RedisTemplate<String, Object> redisTemplate;
    private final MqMessageService mqMessageService;

    private final static String saltString = "123456";

    // 内存标记，减少redis访问，并且为线程安全的集合
    private final Map<Long, Boolean> localMap = new ConcurrentHashMap<>();

    /**
     * 系统初始化，把秒杀商品库存剩余加载到Redis缓存中。库存预热。
     */
    @PostConstruct
    public void initSeckillGoodsCount() {
        List<SeckillGoods> seckillGoodsList = seckillGoodsManager.list();
        if (seckillGoodsList != null) {
            for (SeckillGoods seckillGoods : seckillGoodsList) {
                // 用商品Id作为key，加载秒杀商品的剩余数量
                redisService.set(SeckillGoodsKey.seckillCount, String.valueOf(seckillGoods.getGoodsId()),
                        seckillGoods.getStockCount(), RedisCacheTimeEnum.GOODS_LIST_EXTIME.getValue());
                localMap.put(seckillGoods.getGoodsId(), seckillGoods.getStockCount() > 0);
            }
        }
    }

    public void updateSeckillLocalMap(GoodsDTO goodsDTO) {
        localMap.put(goodsDTO.getId(), goodsDTO.getGoodsStock() > 0);
    }

    //指定事务的传播方式为新建一个事务
    @Override
    @Transactional(rollbackFor = Exception.class)
    @DS("seckill")
    public Result<Integer> doSeckill(long goodsId, String path, HttpServletRequest request) {
        long userId = getUserId(request);
        // 验证path
        if (!checkPath(userId, goodsId, path)) {
            return Result.fail(ResponseEnum.REQUEST_ILLEGAL);
        }
        // 内存标记，减少对redis的访问
        // 若为非，则为商品已经售完
        if (!localMap.get(goodsId)) {
            return Result.fail(ResponseEnum.SECKILL_OVER);
        }
        //检查幂等性，是否重复秒杀
        if (checkRepeat(goodsId, userId)) {
            return Result.fail(ResponseEnum.REPEATED_SECKILL);
        }
        // LUA脚本判断库存和预减库存
        Long count = redisLua.judgeStockAndDecrStock(goodsId);
        if (count == -1) {
            localMap.put(goodsId, false);
            return Result.fail(ResponseEnum.SECKILL_OVER);
        }
        // 入队
        handleMQ(goodsId, userId);
        return Result.success(0); // 排队中
    }

    private void handleMQ(long goodsId, long userId) {
        SeckillMessage seckillMessage = new SeckillMessage();
        seckillMessage.setUserId(userId);
        seckillMessage.setGoodsId(goodsId);
        String messageId = UUID.randomUUID().toString();
        seckillMessage.setMessageId(messageId);

        //使用
        MqMessageLog messageLog = new MqMessageLog();
        messageLog.setMessageId(messageId);
        messageLog.setStatus(0);
        messageLog.setCreatedTime(LocalDateTime.now());
        messageLog.setUpdatedTime(LocalDateTime.now());
        messageLog.setRetryCount(0);
        messageLog.setPayload(JSONUtil.toJsonStr(seckillMessage));
        mqMessageService.save(messageLog);

        // 判断库存、判断是否已经秒杀到了和减库存 下订单 写入订单都由消息队列来执行，做到削峰填谷
        boolean sendSuccess = messageSender.sendMsg(seckillMessage);
        if (sendSuccess) {
            messageLog.setStatus(3);
            messageLog.setUpdatedTime(LocalDateTime.now());
            mqMessageService.updateById(messageLog);
        } else {
            // 发送失败，后续可以做重试
            log.error("消息发送失败，消息ID: {}", messageLog.getMessageId());
        }
    }

    private boolean checkRepeat(long goodsId, long userId) {
        String tempRedisKey = "temp:" + goodsId + ":" + userId;
        boolean isTempExist = redisService.exists(SeckillKey.isRepeat, tempRedisKey);
        if (isTempExist) {
            return true;
        }
        // 设置临时幂等标记，有效期 10 秒
        redisService.set(SeckillKey.isRepeat, tempRedisKey, "1", RedisCacheTimeEnum.SECKILL_REPEAT_EXTIME.getValue());

        // 永久幂等性校验，防止重复秒杀。该标记为订单成功生成后写入缓存
        boolean isRepeat = redisService.exists(SeckillKey.isRepeat, goodsId + ":" + userId);
        if (isRepeat) {
            return true;
        }
        //todo redis检查都不通过，去数据库中查询。查到就回写redis，防止缓存击穿可以考虑分布式锁
//        Long orderId = goodsId * 1000000 + userId;
//        Order order = orderManager.getOne(Wrappers.<Order>lambdaQuery()
//                .eq(Order::getId, orderId));
//        if (order != null) {
//            return Result.fail(ResponseEnum.REPEATED_SECKILL);
//        }
        return false;
    }

    private long getUserId(HttpServletRequest request) {
        return redisService.get(UserKey.getById, AuthUtil.getLoginTokenByRequest(request), Long.class);
    }

    // 客户端-前端服务器轮询查询是否下单成功
    @Override
    public Result<Long> seckillResult(long goodsId, HttpServletRequest request) {
        long userId = getUserId(request);
        // resultId 为 orderId：成功; 0：排队中; -1：秒杀失败(秒杀时间已结束)
        long resultId = getResultId(goodsId, userId);
        return Result.success(resultId);
    }

    private long getResultId(long goodsId, long userId) {
        long resultId;
        // 查寻订单
        Order order = orderManager.getOne(Wrappers.<Order>lambdaQuery()
                .eq(Order::getUserId, userId)
                .eq(Order::getGoodsId, goodsId));
        if (order != null) { // 秒杀成功
            resultId = order.getId();
        } else if (getGoodsIsOver(goodsId)) {
            resultId = -1; // 秒杀失败(秒杀时间已结束)
        } else {
            resultId = 0; // 排队中
        }
        return resultId;
    }

    // 返回一个唯一的path的id
    @Override
    public Result<String> getSeckillPath(HttpServletRequest request, long goodsId) {
        long userId = getUserId(request);
        String path = createSeckillPath(userId, goodsId);
        return Result.success(path);
    }

    // 在redis里验证path
    private boolean checkPath(Long userId, long goodsId, String path) {
        if (userId == null || path == null) {
            return false;
        }
        String redisPath = redisService.get(SeckillKey.getSeckillPath, userId + "_" + goodsId, String.class);
        return path.equals(redisPath);
    }

    // 加盐生成唯一path，构成URl动态化
    private String createSeckillPath(Long userId, Long goodsId) {
        if (userId == null || goodsId == null) {
            return null;
        }
        // 随机返回一个唯一的id，加上盐，然后sm3加密
        String str = SM3Util.sm3(UUID.randomUUID() + saltString);
        redisService.set(SeckillKey.getSeckillPath, userId + "_" + goodsId,
                str, RedisCacheTimeEnum.GOODS_ID_EXTIME.getValue());
        return str;
    }

    // 查看秒杀商品是否已经结束
    private boolean getGoodsIsOver(long goodsId) {
        return redisService.exists(SeckillKey.isGoodsOver, String.valueOf(goodsId));
    }
}
