package com.weiran.mission.service.impl;

import com.baomidou.dynamic.datasource.DynamicRoutingDataSource;
import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.weiran.mission.mapper.MqMessageLogMapper;
import com.weiran.mission.pojo.entity.MqMessageLog;
import com.weiran.mission.service.MqMessageService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import javax.sql.DataSource;
import java.time.LocalDateTime;

/**
 * @Author: Wang Yu
 * @Date: 2025/2/12 11:27
 */
@Service
public class MqMessageServiceImpl extends ServiceImpl<MqMessageLogMapper, MqMessageLog> implements MqMessageService {

    @Override
    public Integer getRetryCount(String messageId) {
        MqMessageLog messageLog = baseMapper.selectOne(Wrappers.<MqMessageLog>lambdaQuery()
                .select(MqMessageLog::getRetryCount)
                .eq(MqMessageLog::getMessageId, messageId)
        );
        if (messageLog == null){
            return null;
        }
        return messageLog.getRetryCount() != null ? messageLog.getRetryCount() : 0;
    }

    @Override
    public boolean incrementRetryCount(String messageId) {
        int affectRows = baseMapper.update(
                null,
                Wrappers.<MqMessageLog>lambdaUpdate()
                        .setSql("retry_count = retry_count + 1")
                        .eq(MqMessageLog::getMessageId, messageId));
        return affectRows > 0;
    }

    @Override
    public boolean updateMqMessageLog(MqMessageLog mqMessageLog){
        if (mqMessageLog.getMessageId() == null){
            return false;
        }
        LambdaUpdateWrapper<MqMessageLog> updateWrapper = Wrappers.lambdaUpdate();
        if (mqMessageLog.getRetryCount() != null){
            updateWrapper.set(MqMessageLog::getRetryCount, mqMessageLog.getRetryCount());
        }
        if (mqMessageLog.getStatus() != null){
            updateWrapper.set(MqMessageLog::getStatus, mqMessageLog.getStatus());
        }
        if (mqMessageLog.getLastRetryTime() != null){
            updateWrapper.set(MqMessageLog::getLastRetryTime, mqMessageLog.getLastRetryTime());
        }
        if (mqMessageLog.getCreatedTime() != null){
            updateWrapper.set(MqMessageLog::getCreatedTime, mqMessageLog.getCreatedTime());
        }
        updateWrapper.set(MqMessageLog::getUpdatedTime, LocalDateTime.now());
        updateWrapper.eq(MqMessageLog::getMessageId, mqMessageLog.getMessageId());
        return baseMapper.update(null,updateWrapper) > 0;
    }
}
