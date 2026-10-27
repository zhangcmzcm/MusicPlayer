package org.example.music.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Integer id;

    @Column(name = "username", nullable = false, length = 80)
    private String username;

    @Column(name = "email", length = 120)
    private String email;

    @Column(name = "password_token", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(name = "user_role", nullable = false, length = 10)
    private UserRole userRole = UserRole.USER;

    @Column(name = "token_version", nullable = false)
    private long tokenVersion;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    protected UserEntity() {
    }

    public UserEntity(String username, String email, String passwordHash, UserRole userRole) {
        this.username = username;
        this.email = email;
        this.passwordHash = passwordHash;
        this.userRole = userRole;
    }

    public static UserEntity create(String username, String email, String passwordHash) {
        UserEntity user = new UserEntity(username, email, passwordHash, UserRole.USER);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public static UserEntity createAdmin(String username, String email, String passwordHash) {
        UserEntity user = new UserEntity(username, email, passwordHash, UserRole.ADMIN);
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        user.createdAt = now;
        user.updatedAt = now;
        return user;
    }

    public Integer getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public UserRole getUserRole() {
        return userRole;
    }

    public long getTokenVersion() {
        return tokenVersion;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public LocalDateTime getLastLoginAt() {
        return lastLoginAt;
    }

    public void setUserRole(UserRole userRole) {
        this.userRole = userRole;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setLastLoginAt(LocalDateTime lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

}