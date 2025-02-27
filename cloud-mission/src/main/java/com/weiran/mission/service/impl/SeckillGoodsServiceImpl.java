package com.weiran.mission.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.weiran.common.enums.ResponseEnum;
import com.weiran.common.obj.Result;
import com.weiran.common.pojo.dto.GoodsDTO;
import com.weiran.common.pojo.dto.SeckillGoodsDTO;
import com.weiran.common.redis.manager.RedisService;
import com.weiran.mission.manager.OrderManager;
import com.weiran.mission.manager.SeckillGoodsManager;
import com.weiran.mission.mapper.SeckillGoodsMapper;
import com.weiran.mission.pojo.entity.Order;
import com.weiran.mission.pojo.entity.SeckillGoods;
import com.weiran.mission.service.SeckillGoodsService;
import com.weiran.mission.utils.POJOConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;


@Slf4j
@Service
@DS("seckill")
@RequiredArgsConstructor
public class SeckillGoodsServiceImpl implements SeckillGoodsService {

    private final SeckillGoodsManager seckillGoodsManager;
    private final RedisService redisService;
    private final SeckillGoodsMapper seckillGoodsMapper;
    private final OrderManager orderManager;

    @Override
    public boolean reduceStock(long goodsId) {
        //查询当前的版库存和版本号
        SeckillGoods seckillGoods = seckillGoodsManager.getOne(
                Wrappers.<SeckillGoods>lambdaQuery().eq(SeckillGoods::getGoodsId, goodsId)
        );
        if (seckillGoods == null || seckillGoods.getStockCount() <= 0) {
            throw new RuntimeException("库存不足或商品不存在");
        }
        // 扣减库存
        int newStockCount = seckillGoods.getStockCount() - 1;
        if (newStockCount < 0) {
            throw new RuntimeException("库存不足，无法扣减");
        }
        //更新库存和版本号，用具有原子性的数据库更新模式
        // 多线程并发写的时候，有并发问题，这里只读redis的库存，然后写入库中，避免并发问题。
        // todo 使用具有原子性的数据库更新模式
//        reduceStockCount(goodsId, seckillGoods);
//        boolean flag = seckillGoodsManager.update(seckillGoods, Wrappers.<SeckillGoods>lambdaUpdate().eq(SeckillGoods::getGoodsId, goodsId));
        return seckillGoodsManager.update(
                null, Wrappers.<SeckillGoods>lambdaUpdate()
                        .set(SeckillGoods::getStockCount, newStockCount)
                        .set(SeckillGoods::getVersion, seckillGoods.getVersion() + 1)
                        .eq(SeckillGoods::getGoodsId, goodsId)
                        .eq(SeckillGoods::getVersion, seckillGoods.getVersion())
        );
    }

    @Override
    public boolean increaseStockCount(long goodsId) {
        try {
            // 使用原子性更新回退库存
            int affectedRows = seckillGoodsMapper.increaseStock(goodsId, 1);
            return affectedRows > 0; // 返回是否更新成功
        } catch (Exception e) {
            // 异常处理，记录日志等
            log.error("回退库存失败, 商品ID: {}", goodsId, e);
            return false;
        }
    }

    @Override
    public PageInfo<SeckillGoodsDTO> findSeckill(Integer page, Integer pageSize, Long goodsId) {
        PageHelper.startPage(page, pageSize);
        List<SeckillGoodsDTO> seckillGoodsDTOList;
        if (StringUtils.isEmpty(goodsId)) {
            seckillGoodsDTOList = seckillGoodsMapper.findSeckill();
        } else {
            seckillGoodsDTOList = seckillGoodsMapper.findByGoodsIdLike(goodsId);
        }
        return new PageInfo<>(seckillGoodsDTOList);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<Object> create(GoodsDTO goodsDTO) {
        try {
            SeckillGoodsDTO seckillGoodsDTO = POJOConverter.converter(goodsDTO);
            seckillGoodsMapper.addSeckillGoods(seckillGoodsDTO);
        } catch (Exception e) {
            log.error(e.toString());
            return Result.fail(ResponseEnum.GOODS_CREATE_FAIL);
        }
        return Result.success();
    }

    @Override
    public Result<Object> update(GoodsDTO goodsDTO) {
        SeckillGoodsDTO seckillGoodsDTO = POJOConverter.converter(goodsDTO);
        seckillGoodsMapper.updateSeckillGoods(seckillGoodsDTO);
        return Result.success();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void delete(Long id) {
        seckillGoodsMapper.deleteSeckillGoods(id);
    }

    @Override
    public void deletes(String ids) {
        String[] split = ids.split(",");
        try {
            for (String goodId : split) {
                seckillGoodsMapper.deleteSeckillGoods(Long.valueOf(goodId));
            }
        } catch (Exception e) {
            log.error(e.toString());
        }
    }

    @Override
    public boolean createOrder(long goodsId, Long orderId, long userId) {
        // 下订单，写入订单表
        Order order = new Order();
        order.setId(orderId);
        order.setUserId(userId);
        order.setGoodsId(goodsId);
        boolean orderCreated = orderManager.save(order);
        if (!orderCreated) {
            log.error("写入订单表失败: {}", goodsId);
            return false;
        }
        log.info("成功写入订单表: {}", goodsId);
        return true;
    }
}
