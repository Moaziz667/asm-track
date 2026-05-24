package com.asm.driver.service;

import com.resend.Resend;
import com.resend.services.emails.model.CreateEmailOptions;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class EmailService {

    private final Resend resend;
    private final String from;

    public EmailService(
            @Value("${resend.api-key}") String apiKey,
            @Value("${resend.from}") String from) {
        this.resend = new Resend(apiKey);
        this.from   = from;
    }

    public void sendDriverInvite(String toEmail, String driverName, String token) {
        String html = buildInviteHtml(driverName, token);
        try {
            CreateEmailOptions req = CreateEmailOptions.builder()
                    .from(from)
                    .to(toEmail)
                    .subject("Invitation ASM Track — Configurez votre compte chauffeur")
                    .html(html)
                    .build();
            resend.emails().send(req);
            log.info("Invite email sent to {}", toEmail);
        } catch (Exception e) {
            log.error("Failed to send invite email to {}: {}", toEmail, e.getMessage());
            throw new RuntimeException("Email sending failed: " + e.getMessage());
        }
    }

    private String buildInviteHtml(String name, String token) {
        return """
            <!DOCTYPE html>
            <html lang="fr">
            <head><meta charset="UTF-8"><meta name="viewport" content="width=device-width,initial-scale=1"></head>
            <body style="margin:0;padding:0;background:#F4F4F5;font-family:Inter,system-ui,sans-serif;">
              <table width="100%%" cellpadding="0" cellspacing="0" style="background:#F4F4F5;padding:40px 16px;">
                <tr><td align="center">
                  <table width="100%%" cellpadding="0" cellspacing="0" style="max-width:520px;background:#FFFFFF;border-radius:8px;overflow:hidden;border:1px solid #E4E4E7;">
                    <!-- Header -->
                    <tr>
                      <td style="background:#FF5722;padding:28px 32px;">
                        <p style="margin:0;font-size:11px;font-weight:700;letter-spacing:0.15em;color:rgba(255,255,255,0.7);text-transform:uppercase;">Plateforme de livraison</p>
                        <h1 style="margin:6px 0 0;font-size:22px;font-weight:900;color:#FFFFFF;letter-spacing:-0.03em;">ASM Track</h1>
                      </td>
                    </tr>
                    <!-- Body -->
                    <tr>
                      <td style="padding:32px;">
                        <h2 style="margin:0 0 8px;font-size:18px;font-weight:700;color:#09090B;letter-spacing:-0.02em;">Bienvenue, %s 👋</h2>
                        <p style="margin:0 0 24px;font-size:14px;color:#71717A;line-height:1.6;">
                          Votre responsable vous a invité à rejoindre <strong style="color:#09090B;">ASM Track</strong>.
                          Pour activer votre compte chauffeur, ouvrez l'application et utilisez le code ci-dessous.
                        </p>
                        <!-- Token box -->
                        <div style="background:#F4F4F5;border:1px solid #E4E4E7;border-radius:6px;padding:20px;text-align:center;margin-bottom:24px;">
                          <p style="margin:0 0 6px;font-size:11px;font-weight:700;color:#71717A;text-transform:uppercase;letter-spacing:0.1em;">Votre code d'activation</p>
                          <p style="margin:0;font-size:18px;font-weight:800;color:#FF5722;font-family:monospace;letter-spacing:0.05em;word-break:break-all;">%s</p>
                        </div>
                        <!-- Steps -->
                        <p style="margin:0 0 12px;font-size:13px;font-weight:700;color:#09090B;">Comment configurer votre compte :</p>
                        <ol style="margin:0 0 24px;padding-left:20px;font-size:13px;color:#71717A;line-height:1.8;">
                          <li>Ouvrez l'application <strong style="color:#09090B;">ASM Track</strong> sur votre téléphone</li>
                          <li>Appuyez sur <strong style="color:#09090B;">"Configurer mon compte"</strong></li>
                          <li>Saisissez le code d'activation ci-dessus</li>
                          <li>Choisissez votre mot de passe</li>
                        </ol>
                        <div style="background:#FBE9E7;border-left:3px solid #FF5722;border-radius:4px;padding:12px 16px;">
                          <p style="margin:0;font-size:12px;color:#BF360C;">
                            ⏱ Ce code expire dans <strong>48 heures</strong>.
                          </p>
                        </div>
                      </td>
                    </tr>
                    <!-- Footer -->
                    <tr>
                      <td style="padding:20px 32px;border-top:1px solid #F4F4F5;background:#FAFAFA;">
                        <p style="margin:0;font-size:11px;color:#A1A1AA;text-align:center;">
                          Vous recevez cet email car votre responsable vous a invité sur ASM Track.<br>
                          Si vous pensez avoir reçu cet email par erreur, ignorez-le simplement.
                        </p>
                      </td>
                    </tr>
                  </table>
                </td></tr>
              </table>
            </body>
            </html>
            """.formatted(name, token);
    }
}
