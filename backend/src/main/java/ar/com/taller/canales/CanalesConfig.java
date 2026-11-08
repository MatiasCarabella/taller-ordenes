package ar.com.taller.canales;

import java.net.URI;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class CanalesConfig {
    final boolean habilitados;
    final String portal,host,usuarioSmtp,claveSmtp,remitente,botUsuario,botToken;
    final int puerto;
    public CanalesConfig(Environment e){
        habilitados=e.getProperty("taller.canales.habilitados",Boolean.class,false);
        portal=e.getProperty("taller.canales.portal","http://localhost:8080").replaceAll("/+$","");
        host=e.getProperty("taller.canales.smtp.host","");puerto=e.getProperty("taller.canales.smtp.puerto",Integer.class,587);
        usuarioSmtp=e.getProperty("taller.canales.smtp.usuario","");claveSmtp=e.getProperty("taller.canales.smtp.clave","");
        remitente=e.getProperty("taller.canales.smtp.remitente","");
        botUsuario=e.getProperty("taller.canales.telegram.usuario","");botToken=e.getProperty("taller.canales.telegram.token","");
        URI u=URI.create(portal);
        boolean local=u.getHost()!=null&&java.util.Set.of("localhost","127.0.0.1","[::1]").contains(u.getHost());
        if(habilitados&&(!u.isAbsolute()||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||
                !("https".equals(u.getScheme())||("http".equals(u.getScheme())&&local))))
            throw new IllegalArgumentException("La URL del portal debe usar HTTPS o localhost.");
    }
    boolean smtp(){return habilitados&&!host.isBlank()&&!usuarioSmtp.isBlank()&&!claveSmtp.isBlank()&&remitente.matches("[^\\s<>]+@[^\\s<>]+")&&java.util.Set.of(587,465,2525).contains(puerto);}
    boolean telegram(){return habilitados&&botUsuario.matches("[A-Za-z0-9_]{5,32}")&&botToken.matches("[0-9]+:[A-Za-z0-9_-]+");}
    @Override public String toString(){return "CanalesConfig[credenciales ocultas]";}
}
