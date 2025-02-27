package com.weiran.mission.mapper;

import com.baomidou.dynamic.datasource.annotation.DS;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.weiran.mission.pojo.entity.MqMessageLog;
import org.apache.ibatis.annotations.Mapper;

/**
 * @Author: Wang Yu
 * @Date: 2025/2/12 10:41
 */
@Mapper
@DS("seckill")
public interface MqMessageLogMapper extends BaseMapper<MqMessageLog> {
}
