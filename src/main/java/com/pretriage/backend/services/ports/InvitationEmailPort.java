package com.pretriage.backend.services.ports;

import com.pretriage.backend.model.acceso.RolMembresiaHospital;

import java.time.Instant;
import java.util.Set;

public interface InvitationEmailPort {
    DeliveryResult deliver(InvitationEmailMessage message);

    record InvitationEmailMessage(String recipient, String hospitalName,
                                  Set<RolMembresiaHospital> roles, Instant expiresAt,
                                  String rawToken) {}

    record DeliveryResult(boolean sent, boolean revealTokenToAdministrator) {}
}
