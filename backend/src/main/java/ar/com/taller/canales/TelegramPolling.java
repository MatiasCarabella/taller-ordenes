package ar.com.taller.canales;

import java.time.*;
import java.util.regex.Pattern;
import org.springframework.boot.autoconfigure.condition.*;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.*;

@Configuration
@EnableScheduling
@ConditionalOnWebApplication
@ConditionalOnProperty(name="taller.canales.polling",havingValue="true",matchIfMissing=true)
public class TelegramPolling {
    private final Transportes.Telegram telegram;private final CanalesService canales;private final Clock clock;
    private Long offset;private boolean ready;private Instant retry=Instant.EPOCH;
    private static final Pattern START=Pattern.compile("^/start(?:@([A-Za-z0-9_]+))? ([A-Za-z0-9_-]{43})$");
    public TelegramPolling(Transportes.Telegram telegram,CanalesService canales,Clock clock){this.telegram=telegram;this.canales=canales;this.clock=clock;}
    @Scheduled(fixedDelay=1000)
    public void poll(){
        if(!telegram.configurado()||clock.instant().isBefore(retry))return;
        try{
            if(!ready){ready=telegram.verificar();if(!ready){retry=clock.instant().plusSeconds(60);return;}}
            for(var message:telegram.recibir(offset)){
                if("private".equals(message.tipoChat())&&!message.bot()&&message.chatId()>0&&message.emisorId()==message.chatId()){
                    var match=START.matcher(message.texto());
                    if(match.matches()&&(match.group(1)==null||match.group(1).equalsIgnoreCase(telegram.usuario()))){
                        var result=canales.vincular(match.group(2),message);
                        try{telegram.enviar(Long.toString(message.chatId()),result.mensaje());}catch(RuntimeException ignored){}
                    }
                }
                offset=message.id()+1;
            }
        }catch(RuntimeException e){ready=false;retry=clock.instant().plusSeconds(30);}
    }
}
