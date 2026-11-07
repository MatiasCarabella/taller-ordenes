package ar.com.taller.ejecucion;

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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TallerOrdenesApplication.class,EjecucionIntegrationTests.Config.class})
class EjecucionIntegrationTests {
    @Container static final MySQLContainer mysql=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("ejecucion_test").withUsername("test_user").withPassword("test-only");
    @DynamicPropertySource static void db(DynamicPropertyRegistry p){
        p.add("spring.datasource.url",mysql::getJdbcUrl);p.add("spring.datasource.username",mysql::getUsername);
        p.add("db_app_password",mysql::getPassword);p.add("spring.flyway.user",mysql::getUsername);p.add("db_migration_password",mysql::getPassword);
    }
    @TestConfiguration(proxyBeanMethods=false) static class Config {@Bean @Primary TestClock testClock(){return new TestClock();}}
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

    @Test void startRequiresApprovalAndDoesNotRequireLatestUnacceptedProposal()throws Exception{
        assertEquals(409,staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":1}").statusCode());
        long accepted=approve(orderId,"100");additional("200",clock.instant().plusSeconds(3600));
        assertEquals(409,staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":9999}").statusCode());
        assertEquals(200,staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+accepted+"}").statusCode());assertEquals("EN_REPARACION",state());
        assertEquals(409,staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+accepted+"}").statusCode());
    }
    @Test void workRequiresDeclarationAndCurrentAuthorizationAndIsVisibleToOwner()throws Exception{
        repair();
        assertEquals(400,staff.post(base()+"/trabajo",work("Servicio",authorized(),false)).statusCode());
        assertEquals(400,staff.post(base()+"/trabajo",work(" ",authorized(),true)).statusCode());
        assertEquals(409,staff.post(base()+"/trabajo",work("Servicio",9999L,true)).statusCode());
        assertEquals(200,staff.post(base()+"/trabajo",work("Reemplazo y ajuste",authorized(),true)).statusCode());
        var own=client.get(base()+"/ejecucion");assertEquals(200,own.statusCode());assertTrue(own.body().contains("Reemplazo y ajuste"));
        long next=additional("80000",clock.instant().plusSeconds(3600));Long old=authorized();
        assertEquals(200,client.post(base()+"/presupuestos/"+next+"/decision","{\"decision\":\"ACEPTADO\"}").statusCode());
        assertEquals(409,staff.post(base()+"/trabajo",work("Servicio",old,true)).statusCode());
    }
    @Test void previewCalculatesServerValuesWithoutPersistingClosure()throws Exception{
        repair();var r=staff.post(base()+"/cierre/previsualizacion",data("25000","2"));assertEquals(200,r.statusCode(),r.body());
        for(String expected:List.of("\"ingresoFinal\":\"70000.00\"","\"costoManoObra\":\"12000.00\"","\"tarifaHoraAplicada\":\"6000.00\""))assertTrue(r.body().contains(expected),r.body());
        assertFalse(r.body().contains("margen"));assertEquals("EN_REPARACION",state());
        assertNull(jdbc.sql("SELECT finalizado_en FROM orden WHERE id=:id").param("id",orderId).query().singleRow().get("finalizado_en"));
    }
    @Test void finalizationCopiesSnapshotAndComputesKnownMarginOnlyForAdministration()throws Exception{
        repair();var r=close(data("25000","2"));assertEquals(200,r.statusCode(),r.body());assertEquals("FINALIZADO",state());
        var result=admin().get(report());assertEquals(200,result.statusCode(),result.body());
        for(String expected:List.of("\"margenDirecto\":\"33000.00\"","\"margenPorcentual\":\"47.14\"","\"costoManoObra\":\"12000.00\""))assertTrue(result.body().contains(expected),result.body());
        assertFalse(r.body().contains("costoRepuestos"));assertFalse(r.body().contains("tarifa"));assertFalse(r.body().contains("margen"));
    }
    @Test void zeroIncomeHasNoPercentageAndNegativeMarginRemainsSigned()throws Exception{
        jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=6000 WHERE perfil='ADMINISTRACION'").update();approve(orderId,"0");
        staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+authorized()+"}");assertEquals(200,close(data("1","0")).statusCode());
        var result=admin().get(report());assertTrue(result.body().contains("\"margenDirecto\":\"-1.00\""),result.body());assertTrue(result.body().contains("\"margenPorcentual\":null"),result.body());
    }
    @Test void validatesInputPrecisionAndAllowsZeroCosts()throws Exception{
        repair();String valid=data("0","0");
        for(String invalid:List.of(valid.replace("\"0\"","\"-1\""),data("0.001","1"),data("1","1000000"),valid.replace("true","false"),valid.replace("Trabajo final autorizado"," ")))
            assertEquals(400,staff.post(base()+"/cierre/previsualizacion",invalid).statusCode(),invalid);
        assertEquals(200,close(valid).statusCode());
    }
    @Test void laborRoundingIsHalfUpAndLargeDerivedAmountsRemainExact()throws Exception{
        repair();jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=0.50 WHERE perfil='ADMINISTRACION'").update();
        var r=staff.post(base()+"/cierre/previsualizacion",data("0","0.01"));assertTrue(r.body().contains("\"costoManoObra\":\"0.01\""),r.body());
        jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=9999999999.99 WHERE perfil='ADMINISTRACION'").update();
        r=staff.post(base()+"/cierre/previsualizacion",data("0","999999.99"));assertEquals(200,r.statusCode(),r.body());
        assertTrue(r.body().contains("\"costoManoObra\":\"9999999899990000.00\""),r.body());
    }
    @Test void tariffIsPositiveExclusiveToAdministrationAndNotRetroactive()throws Exception{
        repair();assertEquals(200,close(data("25000","2")).statusCode());
        var a=admin();for(String invalid:List.of("0","-1","0.001","10000000000"))assertEquals(400,a.post("/api/administracion/tarifa","{\"tarifaHora\":\""+invalid+"\"}").statusCode());
        assertEquals(200,a.post("/api/administracion/tarifa","{\"tarifaHora\":\"8000.50\"}").statusCode());
        var report=a.get(report());assertTrue(report.body().contains("\"tarifaHoraAplicada\":\"6000.00\""));
        assertTrue(a.get("/api/administracion/tarifa").body().contains("8000.50"));
        for(Browser b:List.of(staff,client)){assertEquals(403,b.get("/api/administracion/tarifa").statusCode());assertEquals(403,b.post("/api/administracion/tarifa","{\"tarifaHora\":\"1\"}").statusCode());}
    }
    @Test void absentTariffBlocksPreviewAndFinalization()throws Exception{
        repair();String input=data("0","1"),token=preview(input);
        jdbc.sql("UPDATE usuario SET tarifa_hora_vigente=NULL WHERE perfil='ADMINISTRACION'").update();
        assertEquals(409,staff.post(base()+"/cierre/previsualizacion",input).statusCode());
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,token)).statusCode());assertEquals("EN_REPARACION",state());
    }
    @Test void changedTariffInvalidatesReviewedSnapshot()throws Exception{
        repair();String input=data("25000","2"),token=preview(input);
        admin().post("/api/administracion/tarifa","{\"tarifaHora\":\"7000\"}");
        var result=staff.post(base()+"/cierre",confirm(input,token));assertEquals(409,result.statusCode(),result.body());
        assertTrue(result.body().contains("VISTA_PREVIA_DESACTUALIZADA"));assertEquals(200,close(input).statusCode());
        assertTrue(admin().get(report()).body().contains("\"tarifaHoraAplicada\":\"7000.00\""));
    }
    @Test void changedWorkOrInputInvalidatesReviewedSnapshot()throws Exception{
        repair();String input=data("25000","2"),token=preview(input);
        assertEquals(409,staff.post(base()+"/cierre",confirm(data("24000","2"),token)).statusCode());
        staff.post(base()+"/trabajo",work("Trabajo modificado",authorized(),true));
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,token)).statusCode());assertEquals(200,close(input).statusCode());
    }
    @Test void changedAuthorizationAndNewExpiredVersionInvalidatePreview()throws Exception{
        repair();String input=data("0","0"),token=preview(input);
        long add=additional("80000",clock.instant().plusMillis(1));clock.advance(Duration.ofMillis(1));
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,token)).statusCode());
        token=preview(input);add=additional("90000",clock.instant().plusSeconds(3600));
        client.post(base()+"/presupuestos/"+add+"/decision","{\"decision\":\"ACEPTADO\"}");
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,token)).statusCode());assertEquals(200,close(data("0","0")).statusCode());
    }
    @Test void confirmationCannotBeForgedReusedByAnotherActorOrUsedAtExpiration()throws Exception{
        repair();String input=data("0","0"),token=preview(input);
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,"invalid")).statusCode());
        assertEquals(409,browser("personal2").post(base()+"/cierre",confirm(input,token)).statusCode());
        clock.advance(Duration.ofMinutes(10));
        assertEquals(409,staff.post(base()+"/cierre",confirm(input,token)).statusCode());assertEquals(200,close(input).statusCode());
    }
    @Test void everyPendingLiveProposalBlocksCloseEvenIfSuperseded()throws Exception{
        repair();long old=additional("80000",clock.instant().plusSeconds(60));long newest=additional("90000",clock.instant().plusSeconds(120));
        client.post(base()+"/presupuestos/"+newest+"/decision","{\"decision\":\"RECHAZADO\"}");
        var r=staff.post(base()+"/cierre/previsualizacion",data("0","0"));assertEquals(409,r.statusCode());assertTrue(r.body().contains("PROPUESTA_PENDIENTE"));
        clock.advance(Duration.ofSeconds(60));assertEquals(200,close(data("0","0")).statusCode());
    }
    @Test void deliveryIsIdempotentAndClosureCannotBeEdited()throws Exception{
        repair();String input=data("0","0"),token=preview(input);assertEquals(200,staff.post(base()+"/cierre",confirm(input,token)).statusCode());
        var snapshot=jdbc.sql("SELECT ingreso_final,costo_repuestos_total,horas_mano_obra_total,tarifa_hora_aplicada,finalizado_en,trabajo_realizado,presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",orderId).query().singleRow();
        for(String path:List.of("/trabajo","/cierre/previsualizacion","/cierre"))assertEquals(409,staff.post(base()+path,path.equals("/trabajo")?work("Cambio",authorized(),true):path.equals("/cierre")?confirm(input,token):input).statusCode());
        assertEquals(409,admin().post(base()+"/cancelacion","{\"motivo\":\"Cambio\"}").statusCode());
        var first=staff.post(base()+"/entrega","{}");assertEquals(200,first.statusCode());clock.advance(Duration.ofSeconds(1));
        var second=staff.post(base()+"/entrega","{}");assertEquals(first.body(),second.body());
        assertEquals(snapshot,jdbc.sql("SELECT ingreso_final,costo_repuestos_total,horas_mano_obra_total,tarifa_hora_aplicada,finalizado_en,trabajo_realizado,presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",orderId).query().singleRow());
        assertTrue(admin().get(report()).body().contains("\"finalizadas\":1"));
    }
    @Test void deliveryBeforeClosureIsRejected()throws Exception{
        assertEquals(409,staff.post(base()+"/entrega","{}").statusCode());repair();assertEquals(409,staff.post(base()+"/entrega","{}").statusCode());
    }
    @Test void cancellationPermitsAllPreRepairStatesButNoEconomics()throws Exception{
        var a=admin();
        for(String state:List.of("RECIBIDO","DIAGNOSTICADO","PENDIENTE_APROBACION","APROBADO")){
            long id=order(clientId,"P"+state.length()+state.substring(0,2));
            if(!state.equals("RECIBIDO")){
                staff.post("/api/ordenes/"+id+"/diagnostico","{\"diagnostico\":\"Diagnostico\"}");
                if(!state.equals("DIAGNOSTICADO")){
                    var p=staff.post("/api/ordenes/"+id+"/presupuestos",payload(0,null,"100",clock.instant().plusSeconds(60)));
                    if(state.equals("APROBADO")){var m=Pattern.compile("\"id\":(\\d+)").matcher(p.body());assertTrue(m.find());client.post("/api/ordenes/"+id+"/presupuestos/"+m.group(1)+"/decision","{\"decision\":\"ACEPTADO\"}");}
                }
            }
            var r=a.post("/api/ordenes/"+id+"/cancelacion","{\"motivo\":\"No continuará\"}");assertEquals(200,r.statusCode(),r.body());
            assertTrue(r.body().contains("\"estado\":\"CANCELADO\""));assertTrue(r.body().contains("\"ingresoFinal\":null"));
            assertEquals(409,a.post("/api/ordenes/"+id+"/cancelacion","{\"motivo\":\"Otra\"}").statusCode());
        }
        assertTrue(a.get(report()).body().contains("\"canceladas\":4"));
    }
    @Test void cancellationRequiresAdminReasonAndCannotCancelRepairOrRejected()throws Exception{
        var a=admin();assertEquals(400,a.post(base()+"/cancelacion","{\"motivo\":\" \"}").statusCode());
        for(Browser b:List.of(staff,client))assertEquals(403,b.post(base()+"/cancelacion","{\"motivo\":\"No\"}").statusCode());
        repair();assertEquals(409,a.post(base()+"/cancelacion","{\"motivo\":\"No\"}").statusCode());
        jdbc.sql("UPDATE orden SET estado='RECHAZADO',iniciado_en=NULL,presupuesto_autorizado_id=NULL WHERE id=:id").param("id",orderId).update();
        assertEquals(409,a.post(base()+"/cancelacion","{\"motivo\":\"No\"}").statusCode());assertTrue(a.get(report()).body().contains("\"rechazadas\":1"));
    }
    @Test void concurrentClosuresHaveOneWinner()throws Exception{
        repair();String input=data("25000","2"),body=confirm(input,preview(input));var second=browser("personal");var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return staff.post(base()+"/cierre",body).statusCode();});
            var two=pool.submit(()->{gate.await();return second.post(base()+"/cierre",body).statusCode();});gate.countDown();
            assertEquals(Set.of(200,409),Set.of(one.get(),two.get()));
        }
        assertEquals("FINALIZADO",state());
    }
    @Test void publicationRacingClosureCannotSlipIntoFinalizedOrder()throws Exception{
        repair();String input=data("0","0"),body=confirm(input,preview(input));Long auth=authorized();var second=browser("personal");var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return staff.post(base()+"/cierre",body).statusCode();});
            var two=pool.submit(()->{gate.await();return second.post(base()+"/presupuestos",payload(1,auth,"80000",clock.instant().plusSeconds(3600))).statusCode();});gate.countDown();
            int c=one.get(),p=two.get();assertTrue((c==200&&p==409)||(c==409&&p==201),"cierre="+c+" publicacion="+p);
        }
    }
    @Test void tariffRacingClosureNeverProducesMixedValues()throws Exception{
        repair();String input=data("0","2"),body=confirm(input,preview(input));var a=admin();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return staff.post(base()+"/cierre",body).statusCode();});
            var two=pool.submit(()->{gate.await();return a.post("/api/administracion/tarifa","{\"tarifaHora\":\"7000\"}").statusCode();});gate.countDown();
            int c=one.get();assertEquals(200,two.get());assertTrue(c==200||c==409);
            if(c==200)assertEquals(new java.math.BigDecimal("6000.00"),jdbc.sql("SELECT tarifa_hora_aplicada FROM orden WHERE id=:id").param("id",orderId).query(java.math.BigDecimal.class).single());
            else assertEquals("EN_REPARACION",state());
        }
    }
    @Test void reportUsesLocalSemiOpenBoundsOriginalFinalizationAndWeightedPercentage()throws Exception{
        repair();assertEquals(200,close(data("25000","2")).statusCode());var day=clock.instant().atZone(ZoneId.of("America/Argentina/Buenos_Aires")).toLocalDate();
        Instant start=day.atStartOfDay(ZoneId.of("America/Argentina/Buenos_Aires")).toInstant();
        jdbc.sql("UPDATE orden SET finalizado_en=:f WHERE id=:id").param("id",orderId).param("f",LocalDateTime.ofInstant(start,ZoneOffset.UTC)).update();
        long second=order(clientId,"PERIODO2");approve(second,"30000");
        staff.post("/api/ordenes/"+second+"/iniciar","{\"presupuestoAutorizadoId\":"+jdbc.sql("SELECT presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",second).query(Long.class).single()+"}");
        long original=orderId;orderId=second;assertEquals(200,close(data("10000","0")).statusCode());orderId=original;
        var a=admin();var report=a.get(report());assertEquals(200,report.statusCode(),report.body());assertTrue(report.body().contains("\"margenPorcentual\":\"53.00\""),report.body());
        assertTrue(report.body().contains("\"inicioUtc\":\""+start+"\""),report.body());assertTrue(report.body().contains("\"finalizadas\":2"));
        jdbc.sql("UPDATE orden SET finalizado_en=:f WHERE id=:id").param("id",second).param("f",LocalDateTime.ofInstant(start.plus(Duration.ofDays(1)),ZoneOffset.UTC)).update();
        assertTrue(a.get(report()).body().contains("\"finalizadas\":1"));
        staff.post(base()+"/entrega","{}");clock.advance(Duration.ofDays(1));a=admin();
        var previous=a.get("/api/administracion/resultados?desde="+day+"&hasta="+day);assertTrue(previous.body().contains("\"finalizadas\":1"));assertTrue(previous.body().contains("\"estado\":\"ENTREGADO\""));
    }
    @Test void reportValidatesFiltersPaginationAndNoCrossProfileAccess()throws Exception{
        repair();close(data("0","0"));var a=admin();
        for(String suffix:List.of("&page=-1","&size=51","&size=0"))assertEquals(400,a.get(report()+suffix).statusCode());
        assertEquals(400,a.get("/api/administracion/resultados?desde=2026-02-30&hasta=2026-11-07").statusCode());
        assertEquals(400,a.get("/api/administracion/resultados?desde=2026-12-01&hasta=2026-01-01").statusCode());
        var empty=a.get(report()+"&page=1&size=1");assertTrue(empty.body().contains("\"items\":[]"));assertTrue(empty.body().contains("\"finalizadas\":1"));
        for(Browser b:List.of(staff,client))assertEquals(403,b.get(report()).statusCode());
    }
    @Test void executionViewDoesNotLeakInternalFieldsAndActionsRequireRoleOwnerAndCsrf()throws Exception{
        repair();close(data("25000","2"));var own=client.get(base()+"/ejecucion");assertEquals(200,own.statusCode());
        for(String forbidden:List.of("tarifa","margen","costo","horasMano"))assertFalse(own.body().contains(forbidden),own.body());
        assertEquals(404,browser("cliente2").get(base()+"/ejecucion").statusCode());
        for(String path:List.of("/iniciar","/trabajo","/cierre/previsualizacion","/cierre","/entrega")){
            assertEquals(403,client.post(base()+path,"{}").statusCode());
            assertEquals(403,admin().post(base()+path,"{}").statusCode());
        }
        assertEquals(403,staff.raw("POST",base()+"/entrega","{}",false).statusCode());
        assertEquals(401,new Browser().get(base()+"/ejecucion").statusCode());
    }

    @Test void cancellationRacingStartHasOneWinner()throws Exception{
        long auth=approve(orderId,"100");var a=admin();var gate=new CountDownLatch(1);
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var one=pool.submit(()->{gate.await();return staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+auth+"}").statusCode();});
            var two=pool.submit(()->{gate.await();return a.post(base()+"/cancelacion","{\"motivo\":\"No continuará\"}").statusCode();});gate.countDown();
            int start=one.get(),cancel=two.get();assertTrue((start==200&&cancel==409)||(start==409&&cancel==200));
            assertEquals(start==200?"EN_REPARACION":"CANCELADO",state());
        }
    }
}
