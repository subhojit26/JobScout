package com.JobHunt.appJob.notify;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

/**
 * Sends multipart (HTML + plain-text) emails via Spring's {@link JavaMailSender}, from the
 * configured sender address to a given recipient.
 */
@Service
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender mailSender;
    private final String fromEmail;
    private final boolean mailEnabled;

    public EmailSender(@Autowired(required = false) JavaMailSender mailSender,
                       EmailProperties emailProperties) {
        this.mailSender = mailSender;
        this.fromEmail = emailProperties.from();
        this.mailEnabled = mailSender != null && fromEmail != null && !fromEmail.isBlank();
    }

    public boolean send(DigestEmail digest, String toEmail) {
        if (!mailEnabled || toEmail == null || toEmail.isBlank()) {
            log.warn("Email not configured (mailSender={}, from set={}, to set={}). Skipping.",
                    mailSender != null, fromEmail != null && !fromEmail.isBlank(),
                    toEmail != null && !toEmail.isBlank());
            return false;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, true, "UTF-8");
            helper.setFrom(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject(digest.subject());
            helper.setText(digest.textBody(), digest.htmlBody());
            mailSender.send(msg);
            log.info("Digest email sent ({} job(s))", digest.jobs().size());
            return true;
        } catch (MessagingException | MailException e) {
            log.error("Failed to send digest email", e);
            return false;
        }
    }
}
