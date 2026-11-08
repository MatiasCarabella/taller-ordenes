package ar.com.taller.canales;

import static org.junit.jupiter.api.Assertions.*;
import java.net.*;
import java.net.http.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.mock.env.MockEnvironment;

class TransportesTests {
    CanalesConfig config(){
        return new CanalesConfig(new MockEnvironment().withProperty("taller.canales.habilitados","true")
                .withProperty("taller.canales.telegram.usuario","TallerPruebaBot").withProperty("taller.canales.telegram.token","123:token_de_prueba")
                .withProperty("taller.canales.smtp.host","127.0.0.1").withProperty("taller.canales.smtp.puerto","2525")
                .withProperty("taller.canales.smtp.usuario","usuario_prueba").withProperty("taller.canales.smtp.clave","clave_prueba")
                .withProperty("taller.canales.smtp.remitente","taller@example.com"));
    }
    final tools.jackson.databind.json.JsonMapper json=tools.jackson.databind.json.JsonMapper.builder().build();
    record Request(String method,String path,Map<?,?> body){}
    HttpServer server;final List<Request> requests=new CopyOnWriteArrayList<>();
    volatile java.util.function.Function<Request,String> reply;
    @BeforeEach void start()throws Exception{
        server=HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"),0),0);
        server.createContext("/",exchange->{
            try{
                var body=json.readValue(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8),Map.class);
                var r=new Request(exchange.getRequestMethod(),exchange.getRequestURI().getPath(),body);requests.add(r);
                byte[] response=reply.apply(r).getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
                exchange.sendResponseHeaders(200,response.length);exchange.getResponseBody().write(response);
            }finally{exchange.close();}
        });server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());server.start();
        reply=r->r.path().endsWith("/getMe")?"{\"ok\":true,\"result\":{\"username\":\"TallerPruebaBot\"}}":"{\"ok\":true,\"result\":{\"url\":\"\"}}";
    }
    @AfterEach void stop(){server.stop(0);}
    TelegramHttp telegram(){return new TelegramHttp(config(),HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(1)).build(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()));}
    @Test void verifiesBotIdentityAndRefusesExistingWebhookWithoutDeletingIt(){
        var bot=telegram();assertTrue(bot.verificar());assertEquals("CONECTADO",bot.conexion());
        assertTrue(requests.stream().allMatch(r->r.method().equals("POST")));assertTrue(requests.stream().noneMatch(r->r.path().contains("deleteWebhook")));
        reply=r->r.path().endsWith("/getMe")?"{\"ok\":true,\"result\":{\"username\":\"TallerPruebaBot\"}}":"{\"ok\":true,\"result\":{\"url\":\"https://example.com/webhook\"}}";
        assertFalse(bot.verificar());assertEquals("WEBHOOK_ACTIVO",bot.conexion());
    }
    @Test void mismatchAndProviderFailureExposeNoTokenOrRawError(){
        var bot=telegram();reply=r->"{\"ok\":true,\"result\":{\"username\":\"OtroBot\"}}";assertFalse(bot.verificar());assertEquals("USUARIO_INCORRECTO",bot.conexion());
        reply=r->"{\"ok\":false,\"description\":\"secreto token_de_prueba\"}";
        var error=assertThrows(Transportes.Fallo.class,()->bot.enviar("123","Aviso de prueba"));assertFalse(error.toString().contains("token_de_prueba"));assertNull(error.getCause());
        assertFalse(bot.verificar());assertEquals("NO_DISPONIBLE",bot.conexion());
    }
    @Test void longPollingUsesOffsetAndParsesPrivateSenderAndText(){
        var bot=telegram();reply=r->"{\"ok\":true,\"result\":[{\"update_id\":42,\"message\":{\"chat\":{\"id\":123,\"type\":\"private\"},\"from\":{\"id\":123,\"is_bot\":false},\"text\":\"/start abc\"}}]}";
        var received=bot.recibir(40L);assertEquals(1,received.size());assertEquals(42,received.getFirst().id());assertEquals("private",received.getFirst().tipoChat());
        assertEquals(123,received.getFirst().emisorId());assertFalse(received.getFirst().bot());assertEquals("/start abc",received.getFirst().texto());
        var req=requests.getLast();assertEquals(40,((Number)req.body().get("offset")).intValue());assertEquals(20,((Number)req.body().get("timeout")).intValue());assertEquals(List.of("message"),req.body().get("allowed_updates"));
    }
    @Test void messagesUsePlainTextAndDisabledLinkPreview(){
        var bot=telegram();reply=r->"{\"ok\":true,\"result\":{\"message_id\":1}}";bot.enviar("123","Orden #7: servicio finalizado.");
        var body=requests.getLast().body();assertEquals("123",body.get("chat_id"));assertEquals("Orden #7: servicio finalizado.",body.get("text"));assertFalse(body.containsKey("parse_mode"));
        assertEquals(true,((Map<?,?>)body.get("link_preview_options")).get("is_disabled"));
    }
    @Test void malformedResponsesAndMissingSenderFailClosed(){
        var bot=telegram();reply=r->"invalid";assertThrows(Transportes.Fallo.class,()->bot.recibir(null));
        reply=r->"{\"ok\":true,\"result\":[{\"update_id\":42,\"message\":{\"chat\":{\"id\":123,\"type\":\"private\"},\"text\":\"/start abc\"}}]}";
        assertTrue(bot.recibir(null).getFirst().bot());assertEquals(0,bot.recibir(null).getFirst().emisorId());
    }
    @Test void smtpDoesNotDowngradeToPlainTextOrTransmitCredentialsWithoutTls()throws Exception{
        try(var socket=new ServerSocket(2525,1,InetAddress.getByName("127.0.0.1"));var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var received=new CopyOnWriteArrayList<String>();socket.setSoTimeout(5000);
            var peer=pool.submit(()->{
                try(var connection=socket.accept()){
                    connection.setSoTimeout(5000);var output=new PrintWriter(new OutputStreamWriter(connection.getOutputStream(),StandardCharsets.US_ASCII),true);
                    var input=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.US_ASCII));output.println("220 localhost SMTP prueba");
                    received.add(input.readLine());output.println("250 localhost sin STARTTLS");String more=input.readLine();if(more!=null)received.add(more);
                }catch(IOException e){throw new RuntimeException(e);}
            });
            var adapter=new CorreoSmtp(config());assertTrue(adapter.configurado());assertThrows(Transportes.Fallo.class,()->adapter.enviar("cliente@example.com","Prueba","Mensaje"));
            peer.get(6,TimeUnit.SECONDS);assertTrue(received.getFirst().startsWith("EHLO"));assertTrue(received.stream().noneMatch(s->s.contains("AUTH")||s.contains("usuario_prueba")||s.contains("clave_prueba")));
        }
    }
    @Test void credentialsAreRedactedAndRemotePortalRequiresHttps(){
        assertFalse(config().toString().contains("token_de_prueba"));assertFalse(config().toString().contains("clave_prueba"));
        assertThrows(IllegalArgumentException.class,()->new CanalesConfig(new MockEnvironment().withProperty("taller.canales.habilitados","true").withProperty("taller.canales.portal","http://example.com")));
        assertThrows(IllegalArgumentException.class,()->new CanalesConfig(new MockEnvironment().withProperty("taller.canales.habilitados","true").withProperty("taller.canales.portal","https://user:clave@example.com")));
    }
}
