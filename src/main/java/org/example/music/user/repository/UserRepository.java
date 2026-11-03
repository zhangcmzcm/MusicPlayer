package org.example.music.user.repository;

import jakarta.persistence.LockModeType;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.example.music.user.domain.UserAuthorizationSnapshot;
import org.example.music.user.domain.UserEntity;
import org.example.music.user.domain.UserRole;
import org.example.music.user.domain.UserProfileSnapshot;
import org.example.music.user.domain.UserPasswordSnapshot;
import org.example.music.user.domain.UserReauthenticationSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRepository extends JpaRepository<UserEntity, Integer> {

    @Query("select new org.example.music.user.domain.UserReauthenticationSnapshot("
            + "user.id, user.email, user.passwordHash, user.userRole, user.tokenVersion) "
            + "from UserEntity user where user.id = :userId")
    Optional<UserReauthenticationSnapshot> findReauthenticationSnapshot(@Param("userId") Integer userId);

    @Query("select new org.example.music.user.domain.UserPasswordSnapshot("
            + "user.id, user.passwordHash, user.tokenVersion) from UserEntity user where user.id = :userId")
    Optional<UserPasswordSnapshot> findPasswordSnapshot(@Param("userId") Integer userId);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE users SET password_token = :newHash, token_version = token_version + 1, "
            + "updated_at = UTC_TIMESTAMP(6) WHERE id = :userId "
            + "AND CAST(password_token AS BINARY) = CAST(:previousHash AS BINARY) "
            + "AND token_version = :expectedVersion AND token_version < 9223372036854775807", nativeQuery = true)
    int changePasswordIfSnapshotMatches(@Param("userId") Integer userId,
            @Param("previousHash") String previousHash, @Param("expectedVersion") long expectedVersion,
            @Param("newHash") String newHash);

    @Query("select new org.example.music.user.domain.UserProfileSnapshot("
            + "user.id, user.username, user.email, user.userRole, cast(user.createdAt as String)) "
            + "from UserEntity user where user.id = :userId")
    Optional<UserProfileSnapshot> findProfileSnapshot(@Param("userId") Integer userId);

    @Modifying(clearAutomatically = true)
    @Query(value = "UPDATE users SET username = :username, updated_at = UTC_TIMESTAMP(6) "
            + "WHERE id = :userId AND CAST(username AS BINARY) <> CAST(:username AS BINARY)", nativeQuery = true)
    int updateUsernameIfChanged(@Param("userId") Integer userId, @Param("username") String username);

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
