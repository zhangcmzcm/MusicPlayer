CREATE TABLE `verification_codes` (
    `id` BIGINT NOT NULL AUTO_INCREMENT COMMENT '验证码记录ID',
    `email` VARCHAR(120) NOT NULL COMMENT '规范化邮箱',
    `purpose` VARCHAR(20) NOT NULL COMMENT '验证码用途',
    `code_hash` VARCHAR(255) NOT NULL COMMENT '验证码BCrypt摘要',
    `attempts` INT NOT NULL DEFAULT 0 COMMENT '错误尝试次数',
    `expires_at` DATETIME(6) NOT NULL COMMENT '过期时间UTC',
    `used_at` DATETIME(6) NULL COMMENT '消费时间UTC',
    `created_at` DATETIME(6) NOT NULL COMMENT '创建时间UTC',
    `updated_at` DATETIME(6) NOT NULL COMMENT '更新时间UTC',
    `created_by` BIGINT NULL COMMENT '创建者用户ID',
    `updated_by` BIGINT NULL COMMENT '更新者用户ID',
    PRIMARY KEY (`id`),
    KEY `ix_verification_codes_email_purpose_created_at` (`email`, `purpose`, `created_at`),
    KEY `ix_verification_codes_expires_at` (`expires_at`),
    CONSTRAINT `ck_verification_codes_purpose` CHECK (`purpose` = 'REGISTER'),
    CONSTRAINT `ck_verification_codes_attempts` CHECK (`attempts` >= 0)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
