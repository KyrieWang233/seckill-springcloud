CREATE TABLE `order_info`
(
    `id`         bigint NOT NULL AUTO_INCREMENT COMMENT '主键',
    `user_id`    bigint   DEFAULT NULL COMMENT '用户id',
    `goods_id`   bigint   DEFAULT NULL COMMENT '商品id',
    `created_at` datetime DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at` datetime DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`) USING BTREE
) ENGINE=InnoDB DEFAULT CHARSET=UTF8MB4 COMMENT='订单表';

CREATE INDEX `idx_user_goods` ON `order_info` (`user_id`, `goods_id`);
