package ar.com.taller.canales;

import org.springframework.mail.javamail.*;
import org.springframework.stereotype.Component;

@Component
public class CorreoSmtp implements Transportes.Correo {
    private final CanalesConfig config;private final JavaMailSenderImpl sender;
    public CorreoSmtp(CanalesConfig config){
        this.config=config;sender=new JavaMailSenderImpl();sender.setHost(config.host);sender.setPort(config.puerto);
        sender.setUsername(config.usuarioSmtp);sender.setPassword(config.claveSmtp);sender.setDefaultEncoding("UTF-8");
        var p=sender.getJavaMailProperties();p.setProperty("mail.smtp.auth","true");
        p.setProperty("mail.smtp.connectiontimeout","3000");p.setProperty("mail.smtp.timeout","3000");p.setProperty("mail.smtp.writetimeout","3000");
        p.setProperty("mail.smtp.ssl.checkserveridentity","true");p.setProperty("mail.smtp.ssl.protocols","TLSv1.2 TLSv1.3");
        if(config.puerto==465)p.setProperty("mail.smtp.ssl.enable","true");
        else {p.setProperty("mail.smtp.starttls.enable","true");p.setProperty("mail.smtp.starttls.required","true");}
        p.setProperty("mail.debug","false");
    }
    public boolean configurado(){return config.smtp();}
    public void enviar(String destino,String asunto,String texto){
        if(!configurado())throw new Transportes.Fallo();
        try{
            var message=sender.createMimeMessage();var helper=new MimeMessageHelper(message,false,"UTF-8");
            helper.setFrom(config.remitente);helper.setTo(destino);helper.setSubject(asunto);helper.setText(texto,false);sender.send(message);
        }catch(Exception e){throw new Transportes.Fallo();}
    }
}
