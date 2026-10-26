package org.example.music.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "user_role_history")
public class UserRoleHistoryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "target_user_id", nullable = false)
    private Integer targetUserId;

    @Column(name = "actor_user_id", nullable = false)
    private Integer actorUserId;

    @Enumerated(EnumType.STRING)
    @Column(name = "previous_role", nullable = false, length = 10)
    private UserRole previousRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "new_role", nullable = false, length = 10)
    private UserRole newRole;

    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    @Column(name = "trace_id", length = 64)
    private String traceId;

    protected UserRoleHistoryEntity() {
    }

    public UserRoleHistoryEntity(
            Integer targetUserId,
            Integer actorUserId,
            UserRole previousRole,
            UserRole newRole,
            Instant changedAt,
            String traceId
    ) {
        this.targetUserId = targetUserId;
        this.actorUserId = actorUserId;
        this.previousRole = previousRole;
        this.newRole = newRole;
        this.changedAt = changedAt;
        this.traceId = traceId;
    }

    public Long getId() {
        return id;
    }

    public Integer getTargetUserId() {
        return targetUserId;
    }

    public Integer getActorUserId() {
        return actorUserId;
    }

    public UserRole getPreviousRole() {
        return previousRole;
    }

    public UserRole getNewRole() {
        return newRole;
    }

    public Instant getChangedAt() {
        return changedAt;
    }

    public String getTraceId() {
        return traceId;
    }
}