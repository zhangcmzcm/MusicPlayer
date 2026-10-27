package org.example.music.user.repository;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.example.music.user.domain.UserRoleChangeGuardEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface UserRoleChangeGuardRepository extends JpaRepository<UserRoleChangeGuardEntity, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select guard from UserRoleChangeGuardEntity guard where guard.id = 1")
    Optional<UserRoleChangeGuardEntity> findAndLock();
}
