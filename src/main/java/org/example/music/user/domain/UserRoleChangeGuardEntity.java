package org.example.music.user.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_role_change_guard")
public class UserRoleChangeGuardEntity {

    @Id
    @Column(name = "id", nullable = false)
    private Integer id;

    protected UserRoleChangeGuardEntity() {
    }

    public UserRoleChangeGuardEntity(Integer id) {
        this.id = id;
    }

    public Integer getId() {
        return id;
    }
}
