package com.pretriage.backend.adapters.email;

import com.pretriage.backend.services.ports.PasswordResetEmailPort;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;


import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "pretriage.cambio-contrasenia.email.mode", havingValue = "smtp")
@Slf4j
public class SmtpPasswordResetEmailAdapter implements PasswordResetEmailPort {

    private static final DateTimeFormatter FORMATO_EXPIRACION = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm");
    private static final ZoneId ZONA_ARG = ZoneId.of("America/Argentina/Buenos_Aires");

    private final JavaMailSender mailSender;

    @Value("${pretriage.cambio-contrasenia.email.from:${pretriage.invitations.email.from:no-reply@pretriage.local}}")
    private String from;

    @Override
    public void enviarToken(String destinatario, String token, LocalDateTime fechaHoraExpiracion) {
        String expiracionFormateada = fechaHoraExpiracion.atZone(ZONA_ARG).format(FORMATO_EXPIRACION);
        SimpleMailMessage mail = new SimpleMailMessage();
        mail.setFrom(from);
        mail.setTo(destinatario);
        mail.setSubject("Restablecimiento de contraseña - Pretriage");
        mail.setText("Recibimos una solicitud para restablecer tu contraseña en Pretriage.\n\n"
                + "Tu token de verificación es:\n\n"
                + token + "\n\n"
                + "Este token vence el " + expiracionFormateada + " (America/Argentina/Buenos_Aires).\n"
                + "No compartas este token con nadie. Si no fuiste vos quien lo solicitó, simplemente ignorá este mensaje.\n\n"
                + "Ingresá el token en la aplicación o web para continuar con el cambio de contraseña.\n");
        try {
            mailSender.send(mail);
            log.info("Email de restablecimiento enviado a {}", destinatario);
        } catch (MailException e) {
            log.error("No se pudo enviar email de restablecimiento a {}", destinatario, e);
        }
    }
}
