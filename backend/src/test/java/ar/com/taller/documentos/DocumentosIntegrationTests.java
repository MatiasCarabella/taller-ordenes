package ar.com.taller.documentos;

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
        classes={TallerOrdenesApplication.class,DocumentosIntegrationTests.Config.class})
class DocumentosIntegrationTests {
    @Container static final MySQLContainer mysql=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("documentos_test").withUsername("test_user").withPassword("test-only");
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


    java.net.http.HttpResponse<byte[]> pdf(Browser b,String path)throws Exception{
        return b.http.send(java.net.http.HttpRequest.newBuilder(b.uri(path)).GET().build(),java.net.http.HttpResponse.BodyHandlers.ofByteArray());
    }
    String budgetPath(long id){return base()+"/presupuestos/"+id+"/pdf";}
    String summaryPath(){return base()+"/resumen/pdf";}
    String text(byte[] bytes)throws Exception{try(var doc=org.apache.pdfbox.Loader.loadPDF(bytes)){return new org.apache.pdfbox.text.PDFTextStripper().getText(doc);}}
    void noInternal(String text){for(String term:List.of("costo","tarifa","margen","clave","token","25000","25.000,00","6.000,00","12.000,00","33.000,00"))assertFalse(text.toLowerCase(Locale.ROOT).contains(term.toLowerCase(Locale.ROOT)),term+" filtrado: "+text);}
    void finalization()throws Exception{repair();assertEquals(200,close(data("25000","2")).statusCode());}

    @Test void budgetIsPdfAttachmentWithPrivateHeadersEmbeddedFontAndReadableText()throws Exception{
        long id=approve(orderId,"70000");var response=pdf(client,budgetPath(id));assertEquals(200,response.statusCode());
        assertEquals("application/pdf",response.headers().firstValue("Content-Type").orElseThrow());
        assertTrue(response.headers().firstValue("Content-Disposition").orElseThrow().contains("presupuesto-orden-"+orderId+"-v1.pdf"));
        assertTrue(response.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("nosniff",response.headers().firstValue("X-Content-Type-Options").orElseThrow());
        assertEquals(response.body().length,Long.parseLong(response.headers().firstValue("Content-Length").orElseThrow()));
        assertTrue(new String(response.body(),0,5,java.nio.charset.StandardCharsets.US_ASCII).startsWith("%PDF-"));
        String text=text(response.body());for(String value:List.of("Presupuesto de servicio","Versión 1","70.000,00","Servicio completo","Aceptado","Buenos Aires","1 / 1"))assertTrue(text.contains(value),text);
        try(var doc=org.apache.pdfbox.Loader.loadPDF(response.body())){
            assertEquals(1,doc.getNumberOfPages());var page=doc.getPage(0);assertEquals(org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth(),page.getMediaBox().getWidth());
            for(var name:page.getResources().getFontNames())assertTrue(page.getResources().getFont(name).isEmbedded());
            assertNull(doc.getDocumentCatalog().getOpenAction());assertNull(doc.getDocumentCatalog().getNames());assertTrue(page.getAnnotations().isEmpty());
        }
    }
    @Test void allProfilesCanDownloadVisibleBudgetWithoutInternalData()throws Exception{
        long id=approve(orderId,"70000");
        for(Browser b:List.of(client,staff,admin())){var response=pdf(b,budgetPath(id));assertEquals(200,response.statusCode());noInternal(text(response.body()));}
    }
    @Test void foreignOrderAndBudgetOfAnotherOrderReturnTheSameNotFound()throws Exception{
        long id=approve(orderId,"70000");var other=browser("cliente2");
        assertEquals(404,pdf(other,budgetPath(id)).statusCode());
        assertEquals(404,pdf(client,"/api/ordenes/"+otherOrderId+"/presupuestos/"+id+"/pdf").statusCode());
        assertEquals(404,pdf(staff,"/api/ordenes/"+otherOrderId+"/presupuestos/"+id+"/pdf").statusCode());
        assertEquals(404,pdf(client,base()+"/presupuestos/999999/pdf").statusCode());
        assertEquals(404,pdf(client,"/api/ordenes/999999/presupuestos/"+id+"/pdf").statusCode());
    }
    @Test void authenticationAndGetOnlyAreEnforced()throws Exception{
        long id=approve(orderId,"70000");
        assertEquals(401,pdf(new Browser(),budgetPath(id)).statusCode());
        assertEquals(401,pdf(new Browser(),summaryPath()).statusCode());
        assertEquals(403,client.post(budgetPath(id),"{}").statusCode());
        assertEquals(403,staff.post(summaryPath(),"{}").statusCode());
        var unknown=pdf(client,base()+"/presupuestos/999999/pdf");assertEquals(404,unknown.statusCode());
        assertTrue(unknown.headers().firstValue("Content-Type").orElseThrow().contains("application/problem+json"));
    }
    @Test void summaryIsUnavailableInEveryStateBeforeFinalization()throws Exception{
        assertEquals(409,pdf(client,summaryPath()).statusCode());
        staff.post(base()+"/diagnostico","{\"diagnostico\":\"Diagnóstico\"}");assertEquals(409,pdf(client,summaryPath()).statusCode());
        var publication=staff.post(base()+"/presupuestos",payload(0,null,"100",clock.instant().plusSeconds(3600)));
        var m=Pattern.compile("\"id\":(\\d+)").matcher(publication.body());assertTrue(m.find());long id=Long.parseLong(m.group(1));
        assertEquals(409,pdf(client,summaryPath()).statusCode());
        client.post(base()+"/presupuestos/"+id+"/decision","{\"decision\":\"ACEPTADO\"}");assertEquals(409,pdf(client,summaryPath()).statusCode());
        staff.post(base()+"/iniciar","{\"presupuestoAutorizadoId\":"+id+"}");assertEquals(409,pdf(client,summaryPath()).statusCode());
    }
    @Test void finalSummaryIncludesPersistedWorkAuthorizedVersionAndSalesOnly()throws Exception{
        finalization();
        for(Browser b:List.of(client,staff,admin())){
            var r=pdf(b,summaryPath());assertEquals(200,r.statusCode());String t=text(r.body());
            for(String value:List.of("Resumen final de servicio","Trabajo final autorizado","Alcance autorizado - versión 1","Importe final acordado","70.000,00","Finalización"))assertTrue(t.contains(value),t);
            noInternal(t);
        }
    }
    @Test void summaryUsesAcceptedScopeWhenLatestAdditionalWasRejected()throws Exception{
        repair();long add=additional("80000",clock.instant().plusSeconds(3600));
        assertEquals(200,client.post(base()+"/presupuestos/"+add+"/decision","{\"decision\":\"RECHAZADO\"}").statusCode());
        assertEquals(200,close(data("25000","2")).statusCode());
        String t=text(pdf(client,summaryPath()).body());assertTrue(t.contains("Alcance autorizado - versión 1"));assertTrue(t.contains("70.000,00"));assertFalse(t.contains("80.000,00"));noInternal(t);
    }
    @Test void deliveryAndNewTariffDoNotChangeSummarySnapshot()throws Exception{
        finalization();String first=text(pdf(client,summaryPath()).body());assertFalse(first.contains("Entrega:"));
        admin().post("/api/administracion/tarifa","{\"tarifaHora\":\"8765.43\"}");clock.advance(Duration.ofSeconds(1));
        staff.post(base()+"/entrega","{}");String delivered=text(pdf(client,summaryPath()).body());
        for(String value:List.of("Trabajo final autorizado","70.000,00","Alcance autorizado - versión 1","Finalización: "+textDate()))assertTrue(delivered.contains(value),delivered);
        assertTrue(delivered.contains("Entrega:"));assertTrue(delivered.contains("Entregado"));assertFalse(delivered.contains("8.765,43"));noInternal(delivered);
    }
    String textDate(){return java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(ZoneId.of("America/Argentina/Buenos_Aires")).format(clock.instant());}
    @Test void historicalBudgetKeepsItsContentAfterLaterAcceptedPublicationAndClose()throws Exception{
        repair();long first=authorized();long next=additional("80000",clock.instant().plusSeconds(3600));
        client.post(base()+"/presupuestos/"+next+"/decision","{\"decision\":\"ACEPTADO\"}");close(data("25000","2"));
        String previous=text(pdf(client,budgetPath(first)).body());assertTrue(previous.contains("70.000,00"));assertFalse(previous.contains("80.000,00"));assertTrue(previous.contains("Versión 2"));
        String latest=text(pdf(client,budgetPath(next)).body());assertTrue(latest.contains("80.000,00"));assertFalse(latest.contains("70.000,00"));
        assertTrue(text(pdf(client,summaryPath()).body()).contains("Alcance autorizado - versión 2"));
    }
    @Test void rejectedAndCancelledHaveNoSummaryButPublishedBudgetsRemainDownloadable()throws Exception{
        staff.post(base()+"/diagnostico","{\"diagnostico\":\"Diagnóstico\"}");
        var published=staff.post(base()+"/presupuestos",payload(0,null,"100",clock.instant().plusSeconds(3600)));
        var m=Pattern.compile("\"id\":(\\d+)").matcher(published.body());assertTrue(m.find());long id=Long.parseLong(m.group(1));
        client.post(base()+"/presupuestos/"+id+"/decision","{\"decision\":\"RECHAZADO\"}");
        assertEquals(409,pdf(client,summaryPath()).statusCode());assertTrue(text(pdf(client,budgetPath(id)).body()).contains("Rechazado"));
        long old=orderId;orderId=otherOrderId;admin().post(base()+"/cancelacion","{\"motivo\":\"No continúa\"}");
        assertEquals(409,pdf(staff,summaryPath()).statusCode());assertEquals(404,pdf(client,summaryPath()).statusCode());orderId=old;
    }
    @Test void paginationPreservesHundredLongRowsAccentsTotalsAndPageBounds()throws Exception{
        String diagnostic=("Revisión de dirección, inyección y suspensión. ").repeat(80);
        assertEquals(200,staff.post(base()+"/diagnostico","{\"diagnostico\":\""+diagnostic+"\"}").statusCode());
        StringBuilder rows=new StringBuilder("[");
        for(int i=1;i<=100;i++){
            if(i>1)rows.append(',');
            String description=String.format(Locale.ROOT,"Renglón %03d - Reparación de dirección, ñandú, ¿qué pieza? ",i)+"X".repeat(150);
            rows.append("{\"tipo\":\"MANO_OBRA\",\"descripcion\":\"").append(description).append("\",\"cantidad\":\"0.01\",\"precioUnitario\":\"0.50\"}");
        }
        rows.append(']');
        String input="{\"ultimaVersionEsperada\":0,\"presupuestoAutorizadoEsperadoId\":null,\"venceEn\":\""+clock.instant().plusSeconds(3600)+"\",\"renglones\":"+rows+"}";
        var p=staff.post(base()+"/presupuestos",input);assertEquals(201,p.statusCode(),p.body());var m=Pattern.compile("\"id\":(\\d+)").matcher(p.body());assertTrue(m.find());
        var r=pdf(client,budgetPath(Long.parseLong(m.group(1))));assertEquals(200,r.statusCode());String all=text(r.body());
        for(int i=1;i<=100;i++)assertTrue(all.contains(String.format(Locale.ROOT,"Renglón %03d",i)),"Falta fila "+i);
        assertTrue(all.contains("100 Mano"),"El número del renglón 100 debe quedar completo");assertTrue(all.contains("ñandú"));assertTrue(all.contains("¿qué pieza?"));assertTrue(all.contains("1,00"));
        try(var doc=org.apache.pdfbox.Loader.loadPDF(r.body())){
            assertTrue(doc.getNumberOfPages()>4);
            var checker=new org.apache.pdfbox.text.PDFTextStripper(){
                @Override protected void processTextPosition(org.apache.pdfbox.text.TextPosition t){
                    assertTrue(t.getXDirAdj()>=41&&t.getXDirAdj()+t.getWidthDirAdj()<=org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getWidth()-41,t.getUnicode()+" fuera del ancho");
                    assertTrue(t.getYDirAdj()>=20&&t.getYDirAdj()<=org.apache.pdfbox.pdmodel.common.PDRectangle.A4.getHeight()-20,t.getUnicode()+" fuera de página");
                    super.processTextPosition(t);
                }
            };checker.getText(doc);
            int tablePages=0;
            for(int i=1;i<=doc.getNumberOfPages();i++){var extract=new org.apache.pdfbox.text.PDFTextStripper();extract.setStartPage(i);extract.setEndPage(i);String page=extract.getText(doc);assertTrue(page.contains(i+" / "+doc.getNumberOfPages()));if(page.contains("Renglón")){assertTrue(page.contains("P. unitario"));tablePages++;}}
            assertTrue(tablePages>3);
            var last=new org.apache.pdfbox.text.PDFTextStripper();last.setStartPage(doc.getNumberOfPages());
            String ending=last.getText(doc);assertTrue(ending.contains("Renglón 100"));assertTrue(ending.contains("Total de esta versión"));
            for(int i=1;i<=doc.getNumberOfPages();i++){var extract=new org.apache.pdfbox.text.PDFTextStripper();extract.setStartPage(i);extract.setEndPage(i);String page=extract.getText(doc);if(page.contains("Detalle completo de la propuesta"))assertTrue(page.contains("Renglón 001"));}

        }
    }
    @Test void unsupportedUnicodeAndControlCharactersDoNotBreakGeneration()throws Exception{
        staff.post(base()+"/diagnostico","{\"diagnostico\":\"Revisión con acentos: áéíóú y ñ.\"}");
        String input=payload(0,null,"100",clock.instant().plusSeconds(3600)).replace("Servicio completo","Caja \\uD83E\\uDDF0 y control \\u0001");
        var p=staff.post(base()+"/presupuestos",input);assertEquals(201,p.statusCode(),p.body());var m=Pattern.compile("\"id\":(\\d+)").matcher(p.body());assertTrue(m.find());
        var r=pdf(client,budgetPath(Long.parseLong(m.group(1))));assertEquals(200,r.statusCode());String t=text(r.body());
        assertTrue(t.contains("[U+1F9F0]"),t);assertTrue(t.contains("áéíóú y ñ"));assertFalse(t.contains("\u0001"));
    }
    @Test void summaryForeignAndNonexistentReturn404BeforeRevealingItsAvailability()throws Exception{
        assertEquals(404,pdf(browser("cliente2"),summaryPath()).statusCode());
        assertEquals(404,pdf(staff,"/api/ordenes/999999/resumen/pdf").statusCode());
        finalization();assertEquals(404,pdf(browser("cliente2"),summaryPath()).statusCode());
    }
    @Test void simultaneousDownloadsHaveIndependentDocuments()throws Exception{
        long first=approve(orderId,"70000");long next=additional("80000",clock.instant().plusSeconds(3600));var second=browser("cliente");
        try(var pool=Executors.newVirtualThreadPerTaskExecutor()){
            var a=pool.submit(()->text(pdf(client,budgetPath(first)).body()));var b=pool.submit(()->text(pdf(second,budgetPath(next)).body()));
            assertTrue(a.get().contains("70.000,00"));assertFalse(a.get().contains("80.000,00"));
            assertTrue(b.get().contains("80.000,00"));assertFalse(b.get().contains("70.000,00"));
        }
    }
}
