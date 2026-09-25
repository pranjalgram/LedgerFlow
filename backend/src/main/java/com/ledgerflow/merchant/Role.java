package com.ledgerflow.merchant;

public enum Role {
    OWNER, ADMIN, DEVELOPER, VIEWER;

    public boolean canManage() { return this == OWNER || this == ADMIN; }
    public boolean canWrite() { return this != VIEWER; }
}
