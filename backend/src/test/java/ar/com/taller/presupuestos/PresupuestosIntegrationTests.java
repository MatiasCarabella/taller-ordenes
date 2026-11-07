package ar.com.taller.presupuestos;

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
        classes={TallerOrdenesApplication.class,PresupuestosIntegrationTests.Config.class})
class PresupuestosIntegrationTests {
    @Container static final MySQLContainer mysql=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("presupuestos_test").withUsername("test_user").withPassword("test-only");
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
    void diagnose()throws Exception{assertEquals(200,staff.post(base()+"/diagnostico","{\"diagnostico\":\"Revisar frenos y filtro\"}").statusCode());}
    String payload(int version,Long auth,String deadline,String rows){
        return "{\"ultimaVersionEsperada\":"+version+",\"presupuestoAutorizadoEsperadoId\":"+auth+",\"venceEn\":\""+deadline+"\",\"renglones\":"+rows+"}";
    }
    String payload(int version,Long auth){return payload(version,auth,clock.instant().plusSeconds(3600).toString(),ROWS);}
    long publish(int version,Long auth)throws Exception{
        var response=staff.post(base()+"/presupuestos",payload(version,auth));assertEquals(201,response.statusCode(),response.body());return budgetId(response.body());
    }
    static long budgetId(String body){var m=Pattern.compile("\"id\":(\\d+)").matcher(body);assertTrue(m.find(),body);return Long.parseLong(m.group(1));}
    HttpResponse<String> decide(Browser b,long id,String action)throws Exception{return b.post(base()+"/presupuestos/"+id+"/decision","{\"decision\":\""+action+"\"}");}
    long budgets(){return jdbc.sql("SELECT COUNT(*) FROM presupuesto WHERE orden_id=:id").param("id",orderId).query(Long.class).single();}
    Long authorized(){Object value=jdbc.sql("SELECT presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",orderId).query().singleRow().get("presupuesto_autorizado_id");return value==null?null:((Number)value).longValue();}
    String state(){return jdbc.sql("SELECT estado FROM orden WHERE id=:id").param("id",orderId).query(String.class).single();}

    @Test void diagnosisCanBeCorrectedOnlyBeforeFirstPublication()throws Exception{
        var empty=staff.post(base()+"/diagnostico","{\"diagnostico\":\" \"}");assertEquals(400,empty.statusCode());
        diagnose();assertEquals("DIAGNOSTICADO",state());
        var second=browser("personal2");clock.advance(Duration.ofMillis(1));
        var correction=second.post(base()+"/diagnostico","{\"diagnostico\":\"Diagnóstico corregido\"}");assertEquals(200,correction.statusCode(),correction.body());
        assertTrue(correction.body().contains("\"diagnosticoAutor\":\"personal2\""));
        publish(0,null);
        assertEquals(409,staff.post(base()+"/diagnostico","{\"diagnostico\":\"Cambio posterior\"}").statusCode());
        assertEquals("Diagnóstico corregido",jdbc.sql("SELECT diagnostico FROM orden WHERE id=:id").param("id",orderId).query(String.class).single());
    }

    @Test void publicationRequiresDiagnosisAndPreviewDoesNotPersist()throws Exception{
        assertEquals(409,staff.post(base()+"/presupuestos",payload(0,null)).statusCode());assertEquals(0,budgets());
        diagnose();var preview=staff.post(base()+"/presupuestos/previsualizacion",payload(0,null));
        assertEquals(200,preview.statusCode(),preview.body());assertTrue(preview.body().contains("\"total\":\"100.00\""));
        assertEquals(0,budgets());assertEquals("DIAGNOSTICADO",state());
    }

    @Test void roundsEachLineHalfUpBeforeSummingAndIgnoresClientTotal()throws Exception{
        diagnose();String rows="[{\"tipo\":\"REPUESTO\",\"descripcion\":\"A\",\"cantidad\":\"0.01\",\"precioUnitario\":\"0.50\"},{\"tipo\":\"MANO_OBRA\",\"descripcion\":\"B\",\"cantidad\":\"0.01\",\"precioUnitario\":\"0.50\"}]";
        String body=payload(0,null,clock.instant().plusSeconds(3600).toString(),rows);
        body=body.substring(0,body.length()-1)+",\"total\":\"9999.99\",\"version\":50}";
        var response=staff.post(base()+"/presupuestos",body);assertEquals(201,response.statusCode(),response.body());
        assertTrue(response.body().contains("\"total\":\"0.02\""));assertTrue(response.body().contains("\"version\":1"));
        assertEquals("PENDIENTE_APROBACION",state());assertNull(authorized());
    }

    @Test void rejectsInvalidRowsPrecisionAndOverflowWithoutPartialWrites()throws Exception{
        diagnose();String deadline=clock.instant().plusSeconds(3600).toString();
        for(String rows:List.of("[]","[null]","[{\"tipo\":\"REPUESTO\",\"descripcion\":\"A\",\"cantidad\":\"0\",\"precioUnitario\":\"1\"}]",
                "[{\"tipo\":\"REPUESTO\",\"descripcion\":\"A\",\"cantidad\":\"1.001\",\"precioUnitario\":\"1\"}]",
                "[{\"tipo\":\"OTRO\",\"descripcion\":\"A\",\"cantidad\":\"1\",\"precioUnitario\":\"1\"}]",
                "[{\"tipo\":\"REPUESTO\",\"descripcion\":\"A\",\"cantidad\":\"1\",\"precioUnitario\":\"-1\"}]",
                "[{\"tipo\":\"REPUESTO\",\"descripcion\":\"A\",\"cantidad\":\"2\",\"precioUnitario\":\"9999999999.99\"}]")){
            var result=staff.post(base()+"/presupuestos",payload(0,null,deadline,rows));assertEquals(400,result.statusCode(),result.body());
        }
        assertEquals(0,budgets());assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM renglon").query(Long.class).single());
    }

    @Test void requiresFutureDeadlineAtStoredMillisecondPrecision()throws Exception{
        diagnose();
        assertEquals(400,staff.post(base()+"/presupuestos",payload(0,null,clock.instant().toString(),ROWS)).statusCode());
        assertEquals(400,staff.post(base()+"/presupuestos",payload(0,null,"+10000-01-01T00:00:00Z",ROWS)).statusCode());
        assertEquals(0,budgets());
    }

    @Test void completeVersionsAreConsecutiveAndOlderPendingCannotBeAnswered()throws Exception{
        diagnose();long first=publish(0,null),second=publish(1,null);
        assertNotEquals(first,second);assertEquals(2,budgets());
        assertEquals(409,decide(client,first,"ACEPTADO").statusCode());
        assertEquals(409,staff.post(base()+"/presupuestos",payload(1,null)).statusCode());
        assertEquals(200,decide(client,second,"ACEPTADO").statusCode());assertEquals(second,authorized());
    }

    @Test void initialAcceptanceRecordsOwnerAndTimeAndCannotBeRepeated()throws Exception{
        diagnose();long budget=publish(0,null);var result=decide(client,budget,"ACEPTADO");
        assertEquals(200,result.statusCode(),result.body());assertEquals("APROBADO",state());assertEquals(budget,authorized());
        assertEquals(clientId,jdbc.sql("SELECT decisor_id FROM presupuesto WHERE id=:id").param("id",budget).query(Long.class).single());
        assertNotNull(jdbc.sql("SELECT decidido_en FROM presupuesto WHERE id=:id").param("id",budget).query().singleValue());
        assertEquals(409,decide(client,budget,"RECHAZADO").statusCode());
    }

    @Test void initialRejectionClosesWithoutEconomicDataAndBlocksNewPublication()throws Exception{
        diagnose();long budget=publish(0,null);
        assertEquals(200,decide(client,budget,"RECHAZADO").statusCode());assertEquals("RECHAZADO",state());assertNull(authorized());
        assertEquals(0,jdbc.sql("SELECT COUNT(*) FROM orden WHERE id=:id AND (ingreso_final IS NOT NULL OR costo_repuestos_total IS NOT NULL)").param("id",orderId).query(Long.class).single());
        assertEquals(409,staff.post(base()+"/presupuestos",payload(1,null)).statusCode());
    }

    @Test void initialRevisionAllowsASeparateCompleteVersion()throws Exception{
        diagnose();long first=publish(0,null);
        assertEquals(200,decide(client,first,"REVISION").statusCode());assertEquals("PENDIENTE_APROBACION",state());assertNull(authorized());
        long second=publish(1,null);assertNotEquals(first,second);assertEquals(2,budgets());
        assertEquals("REVISION",jdbc.sql("SELECT decision FROM presupuesto WHERE id=:id").param("id",first).query(String.class).single());
    }

    @Test void acceptanceRemainsOperativeAfterOriginalDeadlineAndAdditionalRejectionOrRevision()throws Exception{
        diagnose();long first=publish(0,null);assertEquals(200,decide(client,first,"ACEPTADO").statusCode());
        clock.advance(Duration.ofHours(2));staff=browser("personal");client=browser("cliente");
        var original=client.get(base()+"/presupuestos/"+first);assertEquals(200,original.statusCode());
        assertTrue(original.body().contains("\"autorizado\":true"));assertTrue(original.body().contains("\"respuestaVencida\":false"));
        long second=publish(1,first);assertEquals("APROBADO",state());assertEquals(first,authorized());
        assertEquals(200,decide(client,second,"RECHAZADO").statusCode());assertEquals(first,authorized());assertEquals("APROBADO",state());
        long third=publish(2,first);assertEquals(200,decide(client,third,"REVISION").statusCode());assertEquals(first,authorized());
        long fourth=publish(3,first);assertEquals(200,decide(client,fourth,"ACEPTADO").statusCode());assertEquals(fourth,authorized());
    }

    @Test void additionalDecisionDoesNotDowngradeRepairState()throws Exception{
        diagnose();long first=publish(0,null);assertEquals(200,decide(client,first,"ACEPTADO").statusCode());
        jdbc.sql("UPDATE orden SET estado='EN_REPARACION',iniciado_en=:fecha WHERE id=:id")
                .param("fecha",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)).param("id",orderId).update();
        long second=publish(1,first);assertEquals(200,decide(client,second,"REVISION").statusCode());
        assertEquals("EN_REPARACION",state());assertEquals(first,authorized());
    }

    @Test void expirationIsExclusiveAndDoesNotAuthorizeAnAdditional()throws Exception{
        diagnose();long first=publish(0,null);assertEquals(200,decide(client,first,"ACEPTADO").statusCode());
        var response=staff.post(base()+"/presupuestos",payload(1,first,clock.instant().plusSeconds(1).toString(),ROWS));
        assertEquals(201,response.statusCode(),response.body());long second=budgetId(response.body());
        clock.advance(Duration.ofSeconds(1));
        var expired=decide(client,second,"ACEPTADO");assertEquals(409,expired.statusCode());assertTrue(expired.body().contains("PRESUPUESTO_VENCIDO"));
        assertEquals(first,authorized());assertEquals("APROBADO",state());
        var context=client.get(base()+"/presupuestos/"+second);
        assertTrue(context.body().contains("\"respuestaVencida\":true"));assertTrue(context.body().contains("\"puedeResponder\":false"));
    }

    @Test void roleOwnershipAndNestedBudgetScopeAreEnforced()throws Exception{
        diagnose();long budget=publish(0,null);var other=browser("cliente2");var admin=browser("admin");
        assertEquals(404,other.get(base()+"/presupuestos").statusCode());
        assertEquals(404,decide(other,budget,"ACEPTADO").statusCode());
        assertEquals(404,client.get("/api/ordenes/"+otherOrderId+"/presupuestos/"+budget).statusCode());
        assertEquals(404,client.get(base()+"/presupuestos/999999").statusCode());
        for(var caller:List.of(client,admin)){
            assertEquals(403,caller.post(base()+"/diagnostico","{\"diagnostico\":\"Otro\"}").statusCode());
            assertEquals(403,caller.post(base()+"/presupuestos",payload(1,null)).statusCode());
            assertEquals(403,caller.post(base()+"/presupuestos/previsualizacion",payload(1,null)).statusCode());
        }
        assertEquals(403,decide(staff,budget,"ACEPTADO").statusCode());
        assertEquals(403,decide(admin,budget,"ACEPTADO").statusCode());
    }

    @Test void publishedContentHasNoUpdateRouteAndRemainsUnchanged()throws Exception{
        diagnose();long budget=publish(0,null);
        assertEquals(403,staff.raw("PATCH",base()+"/presupuestos/"+budget,"{\"total\":\"0.00\"}",true).statusCode());
        assertEquals(403,staff.raw("PUT",base()+"/presupuestos/"+budget,"{}",true).statusCode());
        assertEquals("100.00",jdbc.sql("SELECT total FROM presupuesto WHERE id=:id").param("id",budget).query(java.math.BigDecimal.class).single().toPlainString());
        assertEquals(403,staff.raw("POST",base()+"/presupuestos",payload(1,null),false).statusCode());assertEquals(1,budgets());
    }

    @Test void cancelledOrderDoesNotAcceptDiagnosisOrPublication()throws Exception{
        jdbc.sql("UPDATE orden SET estado='CANCELADO',cancelado_en=:fecha,motivo_cancelacion='Solicitud cancelada' WHERE id=:id")
                .param("fecha",LocalDateTime.ofInstant(clock.instant(),ZoneOffset.UTC)).param("id",orderId).update();
        assertEquals(409,staff.post(base()+"/diagnostico","{\"diagnostico\":\"Otro\"}").statusCode());
        assertEquals(409,staff.post(base()+"/presupuestos",payload(0,null)).statusCode());assertEquals(0,budgets());
    }

    @Test void concurrentPublicationsProduceOneVersionAndOneConflict()throws Exception{
        diagnose();var second=browser("personal2");staff.csrf();second.csrf();String body=payload(0,null);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->{start.await();return staff.post(base()+"/presupuestos",body);});
            var b=executor.submit(()->{start.await();return second.post(base()+"/presupuestos",body);});start.countDown();
            var responses=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(List.of(201,409),responses.stream().map(HttpResponse::statusCode).sorted().toList(),responses.toString());assertEquals(1,budgets());
        }
    }

    @Test void concurrentDecisionsRecordOnlyOneResponse()throws Exception{
        diagnose();long budget=publish(0,null);var second=browser("cliente");client.csrf();second.csrf();var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->{start.await();return decide(client,budget,"ACEPTADO");});
            var b=executor.submit(()->{start.await();return decide(second,budget,"RECHAZADO");});start.countDown();
            var responses=List.of(a.get(20,TimeUnit.SECONDS),b.get(20,TimeUnit.SECONDS));
            assertEquals(List.of(200,409),responses.stream().map(HttpResponse::statusCode).sorted().toList());
            String decision=jdbc.sql("SELECT decision FROM presupuesto WHERE id=:id").param("id",budget).query(String.class).single();
            assertTrue(Set.of("ACEPTADO","RECHAZADO").contains(decision));assertEquals(decision.equals("ACEPTADO")?budget:null,authorized());
        }
    }

    @Test void publicationRacingAcceptanceCannotSilentlyUseAnOldContext()throws Exception{
        diagnose();long first=publish(0,null);staff.csrf();client.csrf();String body=payload(1,null);var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()){
            var a=executor.submit(()->{start.await();return staff.post(base()+"/presupuestos",body);});
            var b=executor.submit(()->{start.await();return decide(client,first,"ACEPTADO");});start.countDown();
            var publication=a.get(20,TimeUnit.SECONDS);var decision=b.get(20,TimeUnit.SECONDS);
            assertTrue((publication.statusCode()==201&&decision.statusCode()==409)||(publication.statusCode()==409&&decision.statusCode()==200),
                    publication.body()+decision.body());
            if(authorized()!=null)assertEquals("ACEPTADO",jdbc.sql("SELECT decision FROM presupuesto WHERE id=:id").param("id",authorized()).query(String.class).single());
        }
    }
}
