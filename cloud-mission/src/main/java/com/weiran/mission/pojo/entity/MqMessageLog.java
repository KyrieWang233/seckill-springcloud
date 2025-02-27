package com.weiran.mission.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * @Author: Wang Yu
 * @Date: 2025/2/11 16:19
 */
@Data
@TableName("mq_message_log")
@ApiModel(description = "Mq消息记录表")
public class MqMessageLog implements Serializable {

    public static final int STATUS_READY = 0;
    public static final int STATUS_SUCCESS = 1;
    public static final int STATUS_FAIL = 2;
    public static final int STATUS_SANDED = 3;

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    @ApiModelProperty("消息记录ID")
    private Long id;

    @ApiModelProperty("消息ID，保证唯一")
    private String messageId;

    @ApiModelProperty("消费状态 0: 待处理, 1: 已成功消费, 2: 消费失败, 3: 已发送")
    private Integer status;

    @ApiModelProperty("重试次数")
    private Integer retryCount;

    @ApiModelProperty("创建时间")
    private LocalDateTime createdTime;

    @ApiModelProperty("更新时间")
    private LocalDateTime updatedTime;

    @ApiModelProperty("消息内容，JSON 格式")
    private String payload;

    @ApiModelProperty("最后一次重试时间")
    private LocalDateTime lastRetryTime;

}
