package org.example.music.user.repository;

import org.example.music.user.domain.UserRoleHistoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRoleHistoryRepository extends JpaRepository<UserRoleHistoryEntity, Long> {
}