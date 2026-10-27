CREATE TABLE `user_role_change_guard` (
    `id` TINYINT UNSIGNED NOT NULL,
    PRIMARY KEY (`id`),
    CONSTRAINT `ck_user_role_change_guard_singleton` CHECK (`id` = 1)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO `user_role_change_guard` (`id`) VALUES (1);
