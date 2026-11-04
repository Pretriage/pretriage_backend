package com.pretriage.backend.adapters.email;

import com.pretriage.backend.services.ports.InvitationEmailPort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pretriage.invitations.email.mode", havingValue = "smtp")
public class SmtpInvitationEmailAdapter implements InvitationEmailPort {
    private final JavaMailSender mailSender;

    @Value("${pretriage.frontend.base-url:http://localhost:3000}")
    private String frontendBaseUrl;

    @Value("${pretriage.invitations.email.from:no-reply@pretriage.local}")
    private String from;

    @Override
    public DeliveryResult deliver(InvitationEmailMessage message) {
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(message.recipient());
        mail.setSubject("Invitación a " + message.hospitalName());
        String roles = message.roles().stream().map(Enum::name).collect(Collectors.joining(", "));
        String expires = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")
                .withZone(ZoneId.of("America/Argentina/Buenos_Aires"))
                .format(message.expiresAt());
        mail.setText("Te invitaron a trabajar en " + message.hospitalName() + ".\n\n"
                + "Funciones: " + roles + "\n"
                + "La invitación vence el " + expires + ".\n\n"
                + frontendBaseUrl.replaceAll("/$", "") + "/invitaciones/aceptar#" + message.rawToken()
                + "\n\nSi no esperabas esta invitación, podés ignorar este mensaje.");
        try {
            mailSender.send(mail);
            return new DeliveryResult(true, false);
        } catch (MailException ignored) {
            return new DeliveryResult(false, false);
        }
    }
}
