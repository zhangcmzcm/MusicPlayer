CREATE TABLE `user_role_history` (
    `id` BIGINT NOT NULL AUTO_INCREMENT,
    `target_user_id` INT NOT NULL,
    `actor_user_id` INT NOT NULL,
    `previous_role` VARCHAR(10) NOT NULL,
    `new_role` VARCHAR(10) NOT NULL,
    `changed_at` DATETIME(6) NOT NULL,
    `trace_id` VARCHAR(64) NULL,
    PRIMARY KEY (`id`),
    KEY `ix_user_role_history_target_changed_at` (`target_user_id`, `changed_at`),
    KEY `ix_user_role_history_actor_changed_at` (`actor_user_id`, `changed_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;