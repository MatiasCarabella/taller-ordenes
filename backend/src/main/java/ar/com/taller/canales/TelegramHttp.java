package ar.com.taller.canales;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class TelegramHttp implements Transportes.Telegram {
    private final CanalesConfig config;private final HttpClient http;private final URI endpoint;
    private final JsonMapper json=JsonMapper.builder().build();
    private final AtomicReference<String> state=new AtomicReference<>("SIN_CONFIGURAR");
    @Autowired public TelegramHttp(CanalesConfig config){
        this(config,HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build(),URI.create("https://api.telegram.org"));
    }
    TelegramHttp(CanalesConfig config,HttpClient http,URI endpoint){this.config=config;this.http=http;this.endpoint=endpoint;state.set(config.telegram()?"INICIANDO":"SIN_CONFIGURAR");}
    public boolean configurado(){return config.telegram();}
    public String usuario(){return config.botUsuario;}
    public String conexion(){return configurado()?state.get():"SIN_CONFIGURAR";}
    private Object call(String method,Map<String,?> params,int timeout){
        if(!configurado())throw new Transportes.Fallo();
        try{
            var request=HttpRequest.newBuilder(URI.create(endpoint.toString()+"/bot"+config.botToken+"/"+method))
                    .timeout(Duration.ofSeconds(timeout)).header("Content-Type","application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(params))).build();
            var response=http.send(request,HttpResponse.BodyHandlers.ofString());
            if(response.statusCode()!=200)throw new Transportes.Fallo();
            var body=json.readValue(response.body(),Map.class);
            if(!Boolean.TRUE.equals(body.get("ok")))throw new Transportes.Fallo();
            return body.get("result");
        }catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new Transportes.Fallo();}
    }
    public boolean verificar(){
        try{
            var me=map(call("getMe",Map.of(),4));
            if(!config.botUsuario.equalsIgnoreCase(string(me.get("username")))){state.set("USUARIO_INCORRECTO");return false;}
            var webhook=map(call("getWebhookInfo",Map.of(),4));
            if(!string(webhook.get("url")).isBlank()){state.set("WEBHOOK_ACTIVO");return false;}
            state.set("CONECTADO");return true;
        }catch(RuntimeException e){state.set("NO_DISPONIBLE");return false;}
    }
    public void enviar(String chat,String texto){
        call("sendMessage",Map.of("chat_id",chat,"text",texto,"link_preview_options",Map.of("is_disabled",true)),4);
    }
    public List<Transportes.Actualizacion> recibir(Long offset){
        var args=new HashMap<String,Object>();args.put("timeout",20);args.put("limit",100);args.put("allowed_updates",List.of("message"));if(offset!=null)args.put("offset",offset);
        Object raw;try{raw=call("getUpdates",args,24);}catch(RuntimeException e){state.set("NO_DISPONIBLE");throw e;}var result=new ArrayList<Transportes.Actualizacion>();
        if(!(raw instanceof List<?> list))throw new Transportes.Fallo();
        for(Object value:list){
            var update=map(value);var message=map(update.get("message"));var chat=map(message.get("chat"));var from=map(message.get("from"));
            result.add(new Transportes.Actualizacion(number(update.get("update_id")),string(chat.get("type")),number(chat.get("id")),
                    number(from.get("id")),!Boolean.FALSE.equals(from.get("is_bot")),string(message.get("text"))));
        }state.set("CONECTADO");return result;
    }
    private static Map<?,?> map(Object o){return o instanceof Map<?,?> m?m:Map.of();}
    private static String string(Object o){return o instanceof String s?s:"";}
    private static long number(Object o){return o instanceof Number n?n.longValue():0;}
}
