package com.ledgerflow.identity.internal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.util.UUID;

@Entity
@Table(name = "app_user")
public class UserEntity {
    @Id
    private UUID id;
    @Column(name = "normalized_email", nullable = false, unique = true, length = 254)
    private String email;
    @Column(name = "password_hash", nullable = false)
    private String passwordHash;
    @Column(nullable = false)
    private boolean enabled;

    protected UserEntity() { }

    public UserEntity(UUID id, String email, String passwordHash) {
        this.id = id;
        this.email = email;
        this.passwordHash = passwordHash;
        this.enabled = true;
    }

    public UUID id() { return id; }
    public String email() { return email; }
    public String passwordHash() { return passwordHash; }
    public boolean enabled() { return enabled; }
    public void updatePasswordHash(String value) { this.passwordHash = value; }
}
