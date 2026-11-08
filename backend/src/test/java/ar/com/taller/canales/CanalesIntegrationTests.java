package ar.com.taller.canales;

import ar.com.taller.TallerOrdenesApplication;
import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(properties="taller.canales.polling=false",webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TallerOrdenesApplication.class,CanalesIntegrationTests.Config.class})
class CanalesIntegrationTests {
    @Container static final MySQLContainer mysql=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("canales_test").withUsername("test_user").withPassword("test-only");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",mysql::getJdbcUrl);p.add("spring.datasource.username",mysql::getUsername);
        p.add("db_app_password",mysql::getPassword);p.add("spring.flyway.user",mysql::getUsername);p.add("db_migration_password",mysql::getPassword);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Config {@Bean @Primary TestClock testClock(){return new TestClock();} @Bean @Primary CorreoPrueba correoPrueba(){return new CorreoPrueba();} @Bean @Primary TelegramPrueba telegramPrueba(){return new TelegramPrueba();}}
    static class TestClock extends Clock {
        final AtomicReference<Instant> value=new AtomicReference<>(Instant.parse("2026-11-07T12:00:00.123456789Z"));
        public Instant instant(){return value.get();} public ZoneId getZone(){return ZoneOffset.UTC;} public Clock withZone(ZoneId zone){return this;}
        void advance(Duration d){value.updateAndGet(i->i.plus(d));}
    }
    @Autowired JdbcClient jdbc;@Autowired PasswordEncoder encoder;@Autowired Environment environment;@Autowired TestClock clock;
    static final String PASSWORD="frase privada para probar presupuestos";
    static final String ROWS="[{\"tipo\":\"REPUESTO\",\"descripcion\":\"Filtro\",\"cantidad\":\"1.00\",\"precioUnitario\":\"100.00\"}]";
    long staffId,clientId,otherClientId,orderId,otherOrderId;
    Browser staff,client;
    @BeforeEach void fixtures() throws Exception {
        clock.advance(Duration.ofDays(1));
        jdbc.sql("""
                UPDATE orden SET estado='RECIBIDO',presupuesto_autorizado_id=NULL,iniciado_en=NULL,finalizado_en=NULL,entregado_en=NULL,
                cancelado_en=NULL,motivo_cancelacion=NULL,ingreso_final=NULL,costo_repuestos_total=NULL,horas_mano_obra_total=NULL,tarifa_hora_aplicada=NULL
                """).update();
        for(String table:List.of("renglon","presupuesto","orden","vehiculo","usuario"))jdbc.sql("DELETE FROM "+table).update();
        String hash=encoder.encode(PASSWORD);user("ADMINISTRACION","admin",hash);staffId=user("PERSONAL","personal",hash);
        user("PERSONAL","personal2",hash);clientId=user("CLIENTE","cliente",hash);otherClientId=user("CLIENTE","cliente2",hash);
        orderId=order(clientId,"AB123CD");otherOrderId=order(otherClientId,"AC456EF");
        staff=browser("personal");client=browser("cliente");
    }
    long insert(String sql,Map<String,?> values){var keys=new GeneratedKeyHolder();jdbc.sql(sql).params(values).update(keys,"id");return Objects.requireNonNull(keys.getKey()).longValue();}
    long user(String profile,String name,String hash){return insert("INSERT INTO usuario(perfil,nombre,correo,clave_hash,activo) VALUES (:p,:n,:c,:h,TRUE)",
            Map.of("p",profile,"n",name,"c",name+"@taller.example","h",hash));}
    long order(long owner,String plate){
        long vehicle=insert("INSERT INTO vehiculo(cliente_id,dominio,marca,modelo) VALUES (:c,:d,'Marca','Modelo')",Map.of("c",owner,"d",plate));
        return insert("INSERT INTO orden(cliente_id,vehiculo_id,responsable_id,motivo_ingreso,recibido_en,actualizado_en) VALUES (:c,:v,:r,'Revisión',:f,:f)",
                Map.of("c",owner,"v",vehicle,"r",staffId,"f",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)));
    }
    Browser browser(String profile)throws Exception{var b=new Browser();b.login(profile);return b;}
    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).build();String token;
        URI uri(String path){return URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+path);}
        HttpResponse<String> get(String path)throws Exception{return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString());}
        String csrf()throws Exception{
            var response=get("/api/auth/csrf");assertEquals(200,response.statusCode(),response.body());
            var m=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());assertTrue(m.find());return token=m.group(1);
        }
        HttpResponse<String> raw(String method,String path,String json,boolean csrf)throws Exception{
            var request=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
            if(csrf)request.header("X-CSRF-TOKEN",token==null?csrf():token);
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(json)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path,String json)throws Exception{return raw("POST",path,json,true);}
        void login(String name)throws Exception{
            var result=post("/api/auth/login","{\"correo\":\""+name+"@taller.example\",\"contrasena\":\""+PASSWORD+"\"}");assertEquals(200,result.statusCode(),result.body());token=null;
        }
    }

    String base(){return "/api/ordenes/"+orderId;}
    String text(String json,String key){var m=Pattern.compile("\""+key+"\":\"([^\"]*)\"").matcher(json);assertTrue(m.find(),json);return m.group(1);}
    String date(){return clock.instant().atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalDate().toString();}
    String report(){return "/api/administracion/resultados?desde="+date()+"&hasta="+date();}
    Browser admin()throws Exception{return browser("admin");}
    Long authorized(){Object v=jdbc.sql("SELECT presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",orderId).query().singleRow().get("presupuesto_autorizado_id");return v==null?null:((Number)v).longValue();}
    String state(){return jdbc.sql("SELECT estado FROM orden WHERE id=:id").param("id",orderId).query(String.class).single();}
    String payload(int version,Long auth,String price,Instant deadline){
        return "{\"ultimaVersionEsperada\":"+version+",\"presupuestoAutorizadoEsperadoId\":"+auth+",\"venceEn\":\""+deadline+"\",\"renglones\":[{\"tipo\":\"REPUESTO\",\"descripcion\":\"Servicio completo\",\"cantidad\":\"1.00\",\"precioUnitario\":\""+price+"\"}]}";
    }
    long approve(long id,String price)throws Exception{
        String path="/api/ordenes/"+id;
        assertEquals(200,staff.post(path+"/diagnostico","{\"diagnostico\":\"Diagnostico\"}").statusCode());
        var publication=staff.post(path+"/presupuestos",payload(0,null,price,clock.instant().plusSeconds(3600)));assertEquals(201,publication.statusCode(),publication.body());
        var m=Pattern.compile("\"id\":(\\d+)").matcher(publication.body());assertTrue(m.find());long b=Long.parseLong(m.group(1));
        var accepted=client.post(path+"/presupuestos/"+b+"/decision","{\"decision\":\"ACEPTADO\"}");assertEquals(200,accepted.statusCode(),accepted.body());return b;
    }
    void repair()throws Exception{
        jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=6000 WHERE perfil='ADMINISTRACION'").update();
        approve(orderId,"70000.00");assertEquals(200,staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}").statusCode());
    }
    String data(String parts,String hours){return "{\"trabajoRealizado\":\"Trabajo final autorizado\",\"presupuestoAutorizadoId\":"+authorized()+",\"alcanceConfirmado\":true,\"costoRepuestosTotal\":\""+parts+"\",\"horasManoObraTotal\":\""+hours+"\"}";}
    String work(String description,Long auth,boolean checked){return "{\"trabajoRealizado\":\""+description+"\",\"presupuestoAutorizadoId\":"+auth+",\"alcanceConfirmado\":"+checked+"}";}
    String preview(String data)throws Exception{var r=staff.post(base()+"/cierre/previsualizacion",data);assertEquals(200,r.statusCode(),r.body());return text(r.body(),"confirmacion");}
    String confirm(String data,String token){return "{\"datos\":"+data+",\"confirmacion\":\""+token+"\"}";}
    HttpResponse<String> close(String data)throws Exception{return staff.post(base()+"/cierre",confirm(data,preview(data)));}
    long additional(String price,Instant deadline)throws Exception{
        int version=jdbc.sql("SELECT MAX(version) FROM presupuesto WHERE orden_id=:id").param("id",orderId).query(Integer.class).single();
        var r=staff.post(base()+"/presupuestos",payload(version,authorized(),price,deadline));assertEquals(201,r.statusCode(),r.body());
        var m=Pattern.compile("\"id\":(\\d+)").matcher(r.body());assertTrue(m.find());return Long.parseLong(m.group(1));
    }


    record Carta(String para,String asunto,String texto){}
    static class CorreoPrueba implements Transportes.Correo {
        volatile boolean habilitado=true,fallar=false,incierto=false;volatile long demora=0;volatile Runnable observar;
        final java.util.concurrent.ConcurrentLinkedQueue<Carta> cartas=new java.util.concurrent.ConcurrentLinkedQueue<>();
        public boolean configurado(){return habilitado;}
        public void enviar(String to,String subject,String text){
            try{if(demora>0)Thread.sleep(demora);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new Transportes.Fallo();}
            if(observar!=null)observar.run();
            if(fallar&&!incierto)throw new Transportes.Fallo();cartas.add(new Carta(to,subject,text));if(fallar)throw new Transportes.Fallo();
        }
        void reset(){habilitado=true;fallar=false;incierto=false;demora=0;observar=null;cartas.clear();}
    }
    static class TelegramPrueba implements Transportes.Telegram {
        volatile String conexionActual="CONECTADO";volatile boolean habilitado=true,fallar=false,listo=true;volatile int consultas=0;volatile Long ultimoOffset;
        final java.util.concurrent.ConcurrentLinkedQueue<Carta> mensajes=new java.util.concurrent.ConcurrentLinkedQueue<>();
        final java.util.concurrent.CopyOnWriteArrayList<Transportes.Actualizacion> actualizaciones=new java.util.concurrent.CopyOnWriteArrayList<>();
        public boolean configurado(){return habilitado;}public String usuario(){return "TallerPruebaBot";}public String conexion(){return habilitado?conexionActual:"SIN_CONFIGURAR";}
        public boolean verificar(){return listo;}
        public List<Transportes.Actualizacion> recibir(Long offset){consultas++;ultimoOffset=offset;return actualizaciones.stream().filter(a->offset==null||a.id()>=offset).toList();}
        public void enviar(String chat,String text){if(fallar)throw new Transportes.Fallo();mensajes.add(new Carta(chat,"",text));}
        void reset(){conexionActual="CONECTADO";habilitado=true;fallar=false;listo=true;consultas=0;ultimoOffset=null;mensajes.clear();actualizaciones.clear();}
    }
    @Autowired CanalesService canales;@Autowired CorreoPrueba correo;@Autowired TelegramPrueba telegram;
    static final tools.jackson.databind.json.JsonMapper JSON=tools.jackson.databind.json.JsonMapper.builder().build();
    @BeforeEach void resetTransportes(){correo.reset();telegram.reset();}
    Object field(String name){return jdbc.sql("SELECT "+name+" FROM usuario WHERE id=:id").param("id",clientId).query().singleRow().get(name);}
    String codigo()throws Exception{
        var r=client.post("/api/canales/correo/codigo","{}");assertEquals(200,r.statusCode(),r.body());
        String code=correo.cartas.stream().reduce((one,two)->two).orElseThrow().texto().lines().filter(s->s.matches("[A-Za-z0-9_-]{22}")).findFirst().orElseThrow();
        assertFalse(r.body().contains(code));return code;
    }
    String enlace(Browser browser)throws Exception{
        var r=browser.post("/api/canales/telegram/enlace","{}");assertEquals(200,r.statusCode(),r.body());
        var m=Pattern.compile("start=([A-Za-z0-9_-]{43})").matcher(r.body());assertTrue(m.find(),r.body());return m.group(1);
    }
    HttpResponse<String> confirmar(String code)throws Exception{return client.post("/api/canales/correo/confirmacion","{\"codigo\":\""+code+"\"}");}
    Transportes.Actualizacion mensaje(long id,long chat,String token){return new Transportes.Actualizacion(id,"private",chat,chat,false,"/start "+token);}
    void confirmados(){jdbc.sql("UPDATE usuario SET correo_confirmado=TRUE,chat_telegram='123456' WHERE id=:id").param("id",clientId).update();}
    String canal(String body,String name)throws Exception{
        var root=JSON.readValue(body,Map.class);var report=(Map<?,?>)root.get("avisos");
        assertNotNull(report,body);
        for(Object item:(List<?>)report.get("canales")){var row=(Map<?,?>)item;if(name.equals(row.get("canal")))return (String)row.get("estado");}
        throw new AssertionError(body);
    }

    @Test void statusAndActionsRequireClientSessionAndCsrf()throws Exception{
        assertEquals(401,new Browser().get("/api/canales").statusCode());
        for(Browser b:List.of(staff,admin())){
            assertEquals(403,b.get("/api/canales").statusCode());
            for(String p:List.of("/correo","/correo/codigo","/correo/confirmacion","/telegram/enlace","/telegram/desvincular"))assertEquals(403,b.post("/api/canales"+p,"{}").statusCode());
        }
        assertEquals(403,client.raw("POST","/api/canales/telegram/enlace","{}",false).statusCode());
        assertEquals(403,client.post("/api/canales/telegram/vincular","{\"chat\":123}").statusCode());
    }
    @Test void statusExposesOwnStateWithoutHashChatIdentifierOrRawToken()throws Exception{
        String token=enlace(client),digest=(String)field("token_canal_hash");var r=client.get("/api/canales");assertEquals(200,r.statusCode());
        assertTrue(r.body().contains("cliente@taller.example"));assertFalse(r.body().contains("cliente2@taller.example"));
        for(String s:List.of(token,digest,"token_canal_hash","chat_telegram","clave_hash"))assertFalse(r.body().contains(s),r.body());
        assertTrue(r.body().contains("\"tokenPendiente\":\"TELEGRAM\""));
    }
    @Test void emailCodeIsRandomHashedAndSentAfterCommitOnlyToOwnAddress()throws Exception{
        correo.observar=()->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals("CORREO",field("token_canal_tipo"));assertNotNull(field("token_canal_hash"));};
        String first=codigo(),digest=(String)field("token_canal_hash");assertEquals(64,digest.length());assertEquals(CanalesService.hash(first),digest);assertNotEquals(first,digest);
        assertEquals("cliente@taller.example",correo.cartas.stream().reduce((one,two)->two).orElseThrow().para());clock.advance(Duration.ofSeconds(61));String second=codigo();assertNotEquals(first,second);
    }
    @Test void codeConfirmsOnceAndClearsAllTokenFields()throws Exception{
        String code=codigo();assertEquals(200,confirmar(code).statusCode());assertEquals(true,field("correo_confirmado"));
        for(String f:List.of("token_canal_hash","token_canal_tipo","token_canal_vence"))assertNull(field(f));
        assertEquals(400,confirmar(code).statusCode());assertEquals(409,client.post("/api/canales/correo/codigo","{}").statusCode());
    }
    @Test void expiredAndWrongEmailCodesAreRejectedAtExactDeadline()throws Exception{
        String code=codigo();assertEquals(400,confirmar("x".repeat(22)).statusCode());assertEquals(false,field("correo_confirmado"));
        clock.advance(Duration.ofMinutes(10));assertEquals(400,confirmar(code).statusCode());assertEquals(false,field("correo_confirmado"));
    }
    @Test void newTokenInvalidatesPreviousOneAcrossBothPurposes()throws Exception{
        String email=codigo(),bot=enlace(client);assertEquals(400,confirmar(email).statusCode());
        clock.advance(Duration.ofSeconds(61));String next=codigo();assertFalse(canales.vincular(bot,mensaje(1,100,bot)).aceptada());assertEquals(200,confirmar(next).statusCode());
    }
    @Test void wrongPurposeCannotConsumeEvenMatchingTokenHash()throws Exception{
        String token=enlace(client);var actor=new ar.com.taller.acceso.CuentaPrincipal(clientId,"Cliente","cliente@taller.example","CLIENTE","test-only",true);
        assertThrows(ar.com.taller.operacion.NegocioException.class,()->canales.confirmarCorreo(actor,new ModelosCanales.ConfirmarCorreo(token)));
        assertNotNull(field("token_canal_hash"));assertTrue(canales.vincular(token,mensaje(1,100,token)).aceptada());
    }
    @Test void emailChangeMaintainsUniqueLoginInvalidatesConfirmationAndPendingToken()throws Exception{
        String old=codigo();
        var duplicate=client.post("/api/canales/correo","{\"correo\":\"cliente2@taller.example\"}");assertEquals(409,duplicate.statusCode());assertEquals(CanalesService.hash(old),field("token_canal_hash"));
        assertEquals(200,confirmar(old).statusCode());String link=enlace(client);
        var changed=client.post("/api/canales/correo","{\"correo\":\" Nuevo@Example.com \",\"usuarioId\":"+otherClientId+"}");assertEquals(200,changed.statusCode(),changed.body());
        assertEquals("nuevo@example.com",field("correo"));assertEquals(false,field("correo_confirmado"));assertNull(field("token_canal_hash"));assertFalse(canales.vincular(link,mensaje(1,100,link)).aceptada());
        assertTrue(client.get("/api/auth/me").body().contains("nuevo@example.com"));
        var next=new Browser();var login=next.post("/api/auth/login","{\"correo\":\"nuevo@example.com\",\"contrasena\":\""+PASSWORD+"\"}");assertEquals(200,login.statusCode());
        assertEquals(401,new Browser().post("/api/auth/login","{\"correo\":\"cliente@taller.example\",\"contrasena\":\""+PASSWORD+"\"}").statusCode());
        assertEquals("cliente2@taller.example",jdbc.sql("SELECT correo FROM usuario WHERE id=:id").param("id",otherClientId).query(String.class).single());
    }
    @Test void sameEmailKeepsConfirmationAndInvalidEmailDoesNotChangeAccount()throws Exception{
        confirmar(codigo());String pending=enlace(client);
        assertEquals(200,client.post("/api/canales/correo","{\"correo\":\"CLIENTE@TALLER.EXAMPLE\"}").statusCode());
        assertEquals(true,field("correo_confirmado"));assertEquals(CanalesService.hash(pending),field("token_canal_hash"));
        for(String value:List.of("incorrecto","a\\r\\nBcc:otro@example.com",""))assertEquals(400,client.post("/api/canales/correo","{\"correo\":\""+value+"\"}").statusCode());
        assertEquals("cliente@taller.example",field("correo"));
    }
    @Test void mailCooldownDoesNotReplaceCodeAndAllowsLaterRequest()throws Exception{
        String first=codigo();assertEquals(429,client.post("/api/canales/correo/codigo","{}").statusCode());assertEquals(CanalesService.hash(first),field("token_canal_hash"));
        clock.advance(Duration.ofSeconds(60));String second=codigo();assertNotEquals(first,second);assertEquals(400,confirmar(first).statusCode());assertEquals(200,confirmar(second).statusCode());
    }
    @Test void unconfiguredProvidersDoNotIssueOrInvalidateTokens()throws Exception{
        String pending=enlace(client);correo.habilitado=false;assertEquals(503,client.post("/api/canales/correo/codigo","{}").statusCode());assertEquals(CanalesService.hash(pending),field("token_canal_hash"));
        telegram.habilitado=false;assertEquals(503,client.post("/api/canales/telegram/enlace","{}").statusCode());assertEquals(CanalesService.hash(pending),field("token_canal_hash"));assertTrue(correo.cartas.isEmpty());
    }
    @Test void mailFailureReportsUncertaintyWithoutFalseConfirmationOrCodeInResponse()throws Exception{
        correo.fallar=true;correo.incierto=true;String code=codigo();assertEquals(false,field("correo_confirmado"));
        assertEquals(200,confirmar(code).statusCode());assertEquals(true,field("correo_confirmado"));
    }
    @Test void telegramLinkIsSingleUseAndTenMinutesAtStoredPrecision()throws Exception{
        String token=enlace(client);assertEquals(43,token.length());assertEquals(CanalesService.hash(token),field("token_canal_hash"));
        assertTrue(canales.vincular(token,mensaje(1,100,token)).aceptada());assertEquals("100",field("chat_telegram"));assertNull(field("token_canal_hash"));
        assertFalse(canales.vincular(token,mensaje(2,200,token)).aceptada());assertEquals("100",field("chat_telegram"));
        String next=enlace(client);clock.advance(Duration.ofMinutes(10));assertFalse(canales.vincular(next,mensaje(3,200,next)).aceptada());assertEquals("100",field("chat_telegram"));
    }
    @Test void groupBotAndMismatchedSenderCannotConsumePrivateChatLink()throws Exception{
        String token=enlace(client);
        for(var input:List.of(new Transportes.Actualizacion(1,"group",-10,100,false,"/start "+token),
                new Transportes.Actualizacion(2,"private",100,200,false,"/start "+token),new Transportes.Actualizacion(3,"private",100,100,true,"/start "+token))){
            assertFalse(canales.vincular(token,input).aceptada());assertEquals(CanalesService.hash(token),field("token_canal_hash"));
        }
        assertTrue(canales.vincular(token,mensaje(4,100,token)).aceptada());
    }
    @Test void occupiedChatRejectsBindingWithoutConsumingLosingToken()throws Exception{
        String first=enlace(client);assertTrue(canales.vincular(first,mensaje(1,100,first)).aceptada());
        String other=enlace(browser("cliente2"));assertFalse(canales.vincular(other,mensaje(2,100,other)).aceptada());
        assertEquals(CanalesService.hash(other),jdbc.sql("SELECT token_canal_hash FROM usuario WHERE id=:id").param("id",otherClientId).query(String.class).single());
        assertTrue(canales.vincular(other,mensaje(3,200,other)).aceptada());
    }
    @Test void simultaneousChatBindingsHaveOneWinnerAndKeepOtherToken()throws Exception{
        String first=enlace(client),other=enlace(browser("cliente2"));var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{gate.await();return canales.vincular(first,mensaje(1,100,first)).aceptada();});
            var b=pool.submit(()->{gate.await();return canales.vincular(other,mensaje(2,100,other)).aceptada();});gate.countDown();
            assertNotEquals(a.get(),b.get());
        }
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM usuario WHERE chat_telegram='100'").query(Integer.class).single());
        assertEquals(1,jdbc.sql("SELECT COUNT(*) FROM usuario WHERE id IN (:one,:two) AND token_canal_hash IS NOT NULL").param("one",clientId).param("two",otherClientId).query(Integer.class).single());
    }
    @Test void simultaneousEmailConfirmationsConsumeOnlyOnce()throws Exception{
        String code=codigo();var second=browser("cliente");var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->{gate.await();return confirmar(code).statusCode();});
            var b=pool.submit(()->{gate.await();return second.post("/api/canales/correo/confirmacion","{\"codigo\":\""+code+"\"}").statusCode();});gate.countDown();
            assertEquals(Set.of(200,400),Set.of(a.get(),b.get()));
        }
    }
    @Test void unlinkRevokesPendingLinkAndDoesNotAffectOtherAccount()throws Exception{
        String first=enlace(client);canales.vincular(first,mensaje(1,100,first));String pending=enlace(client);
        jdbc.sql("UPDATE usuario SET chat_telegram='200' WHERE id=:id").param("id",otherClientId).update();
        assertEquals(200,client.post("/api/canales/telegram/desvincular","{\"usuarioId\":"+otherClientId+"}").statusCode());
        assertNull(field("chat_telegram"));assertNull(field("token_canal_hash"));assertFalse(canales.vincular(pending,mensaje(2,100,pending)).aceptada());
        assertEquals("200",jdbc.sql("SELECT chat_telegram FROM usuario WHERE id=:id").param("id",otherClientId).query(String.class).single());
    }
    @Test void inactiveAccountCannotBindAndPollingAcknowledgesEachUpdateOnce()throws Exception{
        String first=enlace(client);jdbc.sql("UPDATE usuario SET activo=FALSE WHERE id=:id").param("id",clientId).update();
        assertFalse(canales.vincular(first,mensaje(1,100,first)).aceptada());jdbc.sql("UPDATE usuario SET activo=TRUE WHERE id=:id").param("id",clientId).update();
        telegram.actualizaciones.add(mensaje(100,100,first));var polling=new TelegramPolling(telegram,canales,clock);polling.poll();assertEquals("100",field("chat_telegram"));assertEquals(1,telegram.mensajes.size());
        polling.poll();assertEquals(101L,telegram.ultimoOffset);assertEquals(1,telegram.mensajes.size());
    }
    @Test void pollingIgnoresGroupsAndOtherBotCommandsAndDoesNotPollWhenVerificationFails()throws Exception{
        String first=enlace(client);
        telegram.actualizaciones.add(new Transportes.Actualizacion(1,"group",-10,100,false,"/start "+first));
        telegram.actualizaciones.add(new Transportes.Actualizacion(2,"private",100,100,false,"/start@OtroBot "+first));
        var polling=new TelegramPolling(telegram,canales,clock);telegram.listo=false;polling.poll();assertEquals(0,telegram.consultas);
        clock.advance(Duration.ofSeconds(61));telegram.listo=true;polling.poll();assertNull(field("chat_telegram"));assertTrue(telegram.mensajes.isEmpty());
    }
    @Test void noChannelsAreReportedWithoutProviderCalls()throws Exception{
        var p=staff.post(base()+"/diagnostico","{\"diagnostico\":\"Revisar\"}");assertEquals(200,p.statusCode());
        var r=staff.post(base()+"/presupuestos",payload(0,null,"70000",clock.instant().plusSeconds(3600)));assertEquals(201,r.statusCode());
        assertEquals("SIN_CANAL",canal(r.body(),"CORREO"));assertEquals("SIN_CANAL",canal(r.body(),"TELEGRAM"));assertTrue(correo.cartas.isEmpty());assertTrue(telegram.mensajes.isEmpty());
    }
    @Test void confirmedChannelsReceiveMinimalMessagesAfterCommit()throws Exception{
        approve(orderId,"70000");confirmados();
        correo.observar=()->{assertFalse(org.springframework.transaction.support.TransactionSynchronizationManager.isActualTransactionActive());assertEquals("EN_REPARACION",state());};
        var r=staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}");assertEquals(200,r.statusCode());
        assertEquals("ENVIADO",canal(r.body(),"CORREO"));assertEquals("ENVIADO",canal(r.body(),"TELEGRAM"));assertEquals(1,correo.cartas.size());assertEquals(1,telegram.mensajes.size());
        String content=correo.cartas.stream().reduce((one,two)->two).orElseThrow().texto();assertTrue(content.contains("/ordenes/"+orderId));
        for(String forbidden:List.of("70000","25000","6000","margen","tarifa","token","clave","Revisar frenos"))assertFalse(content.contains(forbidden),content);
    }
    @Test void oneProviderFailureAndBothFailuresNeverRollBackBusiness()throws Exception{
        approve(orderId,"70000");confirmados();correo.fallar=true;
        var start=staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}");assertEquals(200,start.statusCode());assertEquals("EN_REPARACION",state());
        assertEquals("FALLO",canal(start.body(),"CORREO"));assertEquals("ENVIADO",canal(start.body(),"TELEGRAM"));
        telegram.fallar=true;jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=6000 WHERE perfil='ADMINISTRACION'").update();
        var closed=close(data("25000","2"));assertEquals(200,closed.statusCode());assertEquals("FINALIZADO",state());
        assertEquals("FALLO",canal(closed.body(),"CORREO"));assertEquals("FALLO",canal(closed.body(),"TELEGRAM"));
    }
    @Test void missingProviderIsDifferentFromMissingConfirmedChannel()throws Exception{
        approve(orderId,"70000");confirmados();correo.habilitado=false;telegram.habilitado=false;
        var r=staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}");assertEquals(200,r.statusCode());
        assertEquals("NO_CONFIGURADO",canal(r.body(),"CORREO"));assertEquals("NO_CONFIGURADO",canal(r.body(),"TELEGRAM"));
    }
    @Test void rollbackAndNonEventActionsNeverSendNotices()throws Exception{
        approve(orderId,"70000");confirmados();int before=correo.cartas.size();
        assertEquals(409,staff.post(base()+"/presupuestos",payload(0,null,"90000",clock.instant().plusSeconds(3600))).statusCode());
        assertEquals(before,correo.cartas.size());assertTrue(telegram.mensajes.isEmpty());
        staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}");before=correo.cartas.size();
        assertEquals(200,staff.post(base()+"/trabajo",work("Trabajo autorizado",authorized(),true)).statusCode());assertEquals(before,correo.cartas.size());
        assertEquals(409,staff.post(base()+"/cierre","{\"datos\":"+data("0","0")+",\"confirmacion\":\"invalida\"}").statusCode());assertEquals(before,correo.cartas.size());
    }
    @Test void allRequiredEventsAndRepeatedDeliveryHaveExpectedNoticeCounts()throws Exception{
        confirmados();repair();assertEquals(3,correo.cartas.size()); // publication, approval, start
        assertEquals(200,close(data("25000","2")).statusCode());assertEquals(4,correo.cartas.size());
        staff.post(base()+"/entrega","{}");assertEquals(5,correo.cartas.size());staff.post(base()+"/entrega","{}");assertEquals(5,correo.cartas.size());
        long extra=order(clientId,"NOTIFICAR");String path="/api/ordenes/"+extra;
        staff.post(path+"/diagnostico","{\"diagnostico\":\"Otro\"}");var p=staff.post(path+"/presupuestos",payload(0,null,"100",clock.instant().plusSeconds(3600)));
        var m=Pattern.compile("\"id\":(\\d+)").matcher(p.body());assertTrue(m.find());client.post(path+"/presupuestos/"+m.group(1)+"/decision","{\"decision\":\"RECHAZADO\"}");
        assertEquals(7,correo.cartas.size());long cancel=order(clientId,"CANCELAR");admin().post("/api/ordenes/"+cancel+"/cancelacion","{\"motivo\":\"No continúa\"}");
        assertEquals(8,correo.cartas.size());assertEquals(8,telegram.mensajes.size());
    }
    @Test void revisionAndAdditionalResponsesDoNotInventStateChangeEvents()throws Exception{
        confirmados();repair();long next=additional("80000",clock.instant().plusSeconds(3600));int before=correo.cartas.size();
        var rejected=client.post(base()+"/presupuestos/"+next+"/decision","{\"decision\":\"REVISION\"}");assertEquals(200,rejected.statusCode());assertEquals(before,correo.cartas.size());assertTrue(rejected.body().contains("\"avisos\":null"));
        next=additional("90000",clock.instant().plusSeconds(3600));before=correo.cartas.size();
        client.post(base()+"/presupuestos/"+next+"/decision","{\"decision\":\"ACEPTADO\"}");assertEquals(before,correo.cartas.size());assertEquals("EN_REPARACION",state());
    }
    @Test void coordinatorDeadlineReturnsPerChannelWithoutUndoingCommittedState()throws Exception{
        repair();confirmados();correo.demora=180;
        var config=new CanalesConfig(new org.springframework.mock.env.MockEnvironment());
        var coordinator=new AvisosCoordinator(jdbc,correo,telegram,config,Duration.ofMillis(40));
        try{
            var r=coordinator.enviar(new ModelosCanales.Evento(orderId,clientId,"EN_REPARACION"));
            assertEquals("FALLO",r.canales().get(0).estado());assertEquals("ENVIADO",r.canales().get(1).estado());assertEquals("EN_REPARACION",state());
            Thread.sleep(240); // Let the bounded in-flight simulator finish before fixture cleanup.
        }finally{coordinator.close();}
    }

    @Test void aSingleConfirmedChannelDoesNotEnableTheUnconfirmedOne()throws Exception{
        jdbc.sql("UPDATE usuario SET chat_telegram='123456',correo_confirmado=FALSE WHERE id=:id").param("id",clientId).update();
        staff.post(base()+"/diagnostico","{\"diagnostico\":\"Revisar\"}");
        var r=staff.post(base()+"/presupuestos",payload(0,null,"70000",clock.instant().plusSeconds(3600)));
        assertEquals(201,r.statusCode());assertEquals("SIN_CANAL",canal(r.body(),"CORREO"));assertEquals("ENVIADO",canal(r.body(),"TELEGRAM"));
        assertTrue(correo.cartas.isEmpty());assertEquals(1,telegram.mensajes.size());
    }

    @Test void cannotIssueLinkBeforeBotIdentityAndPollingModeAreVerified()throws Exception{
        String previous=codigo();
        for(String connection:List.of("INICIANDO","USUARIO_INCORRECTO","WEBHOOK_ACTIVO","NO_DISPONIBLE")){
            telegram.conexionActual=connection;assertEquals(503,client.post("/api/canales/telegram/enlace","{}").statusCode());
            assertEquals(CanalesService.hash(previous),field("token_canal_hash"));
        }
        telegram.conexionActual="CONECTADO";assertNotNull(enlace(client));
    }
}
