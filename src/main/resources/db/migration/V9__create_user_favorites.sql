CREATE TABLE `user_favorites` (
  `user_id` INT NOT NULL COMMENT 'Favorite owner ID',
  `track_id` BIGINT NOT NULL COMMENT 'Favorited track ID',
  `created_at` DATETIME(6) NOT NULL COMMENT 'First favorite timestamp in UTC',
  `updated_at` DATETIME(6) NOT NULL COMMENT 'Last updating timestamp in UTC',
  `created_by` INT NOT NULL COMMENT 'Creating user ID',
  `updated_by` INT NOT NULL COMMENT 'Last updating user ID',
  PRIMARY KEY (`user_id`, `track_id`),
  KEY `ix_user_favorites_track_id` (`track_id`),
  CONSTRAINT `fk_user_favorites_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_favorites_track` FOREIGN KEY (`track_id`) REFERENCES `tracks` (`id`) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
