package com.ledgerflow.shared;

import java.util.UUID;

/** Verified API-key identity; never carries the secret. */
public record ApiPrincipal(UUID keyId, UUID merchantId, boolean canWrite) { }
