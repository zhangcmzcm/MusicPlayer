CREATE TABLE `user_playback_progress` (
  `user_id` INT NOT NULL COMMENT '播放状态所属用户',
  `device_id` CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL COMMENT '规范化设备UUID',
  `track_id` BIGINT NULL COMMENT '最近歌曲，删除后为空',
  `position_seconds` BIGINT NOT NULL COMMENT '非负整秒位置',
  `last_sequence` BIGINT NOT NULL COMMENT '设备最后成功保存的事件序号',
  `created_at` DATETIME(6) NOT NULL COMMENT '首次保存时间，UTC',
  `updated_at` DATETIME(6) NOT NULL COMMENT '最近新事件保存时间，UTC',
  `created_by` INT NOT NULL,
  `updated_by` INT NOT NULL,
  PRIMARY KEY (`user_id`, `device_id`),
  KEY `ix_user_playback_progress_track` (`track_id`),
  CONSTRAINT `ck_user_playback_progress_position` CHECK (`position_seconds` >= 0),
  CONSTRAINT `ck_user_playback_progress_sequence` CHECK (`last_sequence` BETWEEN 1 AND 9007199254740991),
  CONSTRAINT `fk_user_playback_progress_user` FOREIGN KEY (`user_id`) REFERENCES `users` (`id`) ON DELETE CASCADE,
  CONSTRAINT `fk_user_playback_progress_track` FOREIGN KEY (`track_id`) REFERENCES `tracks` (`id`) ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
