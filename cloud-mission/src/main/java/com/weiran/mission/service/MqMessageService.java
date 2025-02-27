package com.weiran.mission.service;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.extension.service.IService;
import com.weiran.mission.pojo.entity.MqMessageLog;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * @Author: Wang Yu
 * @Date: 2025/2/12 11:04
 */
public interface MqMessageService extends IService<MqMessageLog> {
    Integer getRetryCount(String messageId);

    boolean incrementRetryCount(String messageId);

    boolean updateMqMessageLog(MqMessageLog mqMessageLog);
}
