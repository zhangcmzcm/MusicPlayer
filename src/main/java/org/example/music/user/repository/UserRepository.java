package org.example.music.user.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

    long countByUserRole(UserRole userRole);

    Optional<UserEntity> findByUsername(String username);

    Optional<UserEntity> findByEmail(String email);

    @Query("select new org.example.music.user.domain.UserAuthorizationSnapshot("
            + "user.id, user.username, user.email, user.userRole, user.tokenVersion) "
            + "from UserEntity user where user.id = :userId")
    Optional<UserAuthorizationSnapshot> findAuthorizationSnapshot(@Param("userId") Integer userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select user from UserEntity user where user.id = :userId")
    Optional<UserEntity> findAndLockById(@Param("userId") Integer userId);

    @Query(value = "select id from users where user_role = 'ADMIN' for update", nativeQuery = true)
    List<Integer> lockAdminRows();

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserEntity user set user.lastLoginAt = :lastLoginAt where user.id = :userId")
    int updateLastLoginAt(@Param("userId") Integer userId, @Param("lastLoginAt") LocalDateTime lastLoginAt);

    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update UserEntity user "
            + "set user.userRole = :newRole, "
            + "user.tokenVersion = user.tokenVersion + 1, "
            + "user.updatedAt = :updatedAt "
            + "where user.id = :userId and user.userRole = :previousRole")
    int updateRoleAndIncrementTokenVersion(
            @Param("userId") Integer userId,
            @Param("previousRole") UserRole previousRole,
            @Param("newRole") UserRole newRole,
            @Param("updatedAt") LocalDateTime updatedAt
    );
}
