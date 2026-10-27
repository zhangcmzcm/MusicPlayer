ALTER TABLE `users`
    ADD COLUMN `token_version` BIGINT NOT NULL DEFAULT 0 AFTER `user_role`,
    ADD CONSTRAINT `ck_users_token_version_nonnegative` CHECK (`token_version` >= 0);

CREATE INDEX `ix_users_user_role` ON `users` (`user_role`);
