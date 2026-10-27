ALTER TABLE `user_role_history`
    ADD CONSTRAINT `fk_user_role_history_target_user`
        FOREIGN KEY (`target_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT,
    ADD CONSTRAINT `fk_user_role_history_actor_user`
        FOREIGN KEY (`actor_user_id`) REFERENCES `users` (`id`) ON DELETE RESTRICT;
