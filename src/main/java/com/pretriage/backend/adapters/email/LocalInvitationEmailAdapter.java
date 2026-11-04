package com.pretriage.backend.adapters.email;

import com.pretriage.backend.services.ports.InvitationEmailPort;

import lombok.extern.slf4j.Slf4j;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "pretriage.invitations.email.mode", havingValue = "local", matchIfMissing = true)
@Slf4j
public class LocalInvitationEmailAdapter implements InvitationEmailPort {
    @Override
    public DeliveryResult deliver(InvitationEmailMessage message) {
        log.info(
                "[LOCAL] Mail mensaje {}, link de aceptacion: /invitaciones/aceptar#{} - No se envía email real.",
                message.toString(), message.rawToken());
        return new DeliveryResult(false, true);
    }
}
