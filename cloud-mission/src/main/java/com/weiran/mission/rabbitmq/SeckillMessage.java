package com.weiran.mission.rabbitmq;

import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

import java.io.Serializable;

@Data
public class SeckillMessage implements Serializable {

    private long userId;

    private long goodsId;

    private String messageId;
}
