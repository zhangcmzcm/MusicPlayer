CREATE TABLE IF NOT EXISTS `users` (
  `id` INT NOT NULL AUTO_INCREMENT COMMENT 'User ID',
  `username` VARCHAR(80) NOT NULL COMMENT 'Unique username',
  `email` VARCHAR(120) DEFAULT NULL COMMENT 'Unique email address',
  `password_token` VARCHAR(255) NOT NULL COMMENT 'Password hash token',
  `user_role` ENUM('USER','ADMIN') NOT NULL DEFAULT 'USER' COMMENT 'Highest user role',
  `created_at` DATETIME(6) DEFAULT NULL COMMENT 'Created timestamp in UTC',
  `updated_at` DATETIME(6) DEFAULT NULL COMMENT 'Updated timestamp in UTC',
  `last_login_at` DATETIME(6) DEFAULT NULL COMMENT 'Last login timestamp in UTC',
  PRIMARY KEY (`id`),
  UNIQUE KEY `ix_users_username` (`username`),
  UNIQUE KEY `ix_users_email` (`email`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
