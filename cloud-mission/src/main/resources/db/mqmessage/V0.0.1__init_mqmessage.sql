CREATE TABLE `mq_message_log`
(
    `id`              bigint NOT NULL AUTO_INCREMENT COMMENT '消息记录ID',
    `message_id`      VARCHAR(255) NOT NULL COMMENT '消息ID，保证唯一',
    `status`          INT          NOT NULL COMMENT '消费状态 0: 未消费, 1: 已成功消费, 2: 消费失败',
    `retry_count`     INT          NOT NULL DEFAULT 0 COMMENT '重试次数',
    `create_time`     datetime     DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime     DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `payload`         TEXT COMMENT '消息内容，JSON 格式',
    `last_retry_time` datetime     COMMENT '最后一次重试时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=UTF8MB4 COMMENT='MQ消息记录表';