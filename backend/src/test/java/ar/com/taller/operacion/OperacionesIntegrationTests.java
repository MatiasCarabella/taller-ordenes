package ar.com.taller.operacion;

import ar.com.taller.TallerOrdenesApplication;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,classes=TallerOrdenesApplication.class)
class OperacionesIntegrationTests {
    @Container static final MySQLContainer mysql=new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("operacion_test").withUsername("test_user").withPassword("test-only");
    @DynamicPropertySource static void database(DynamicPropertyRegistry p) {
        p.add("spring.datasource.url",mysql::getJdbcUrl);p.add("spring.datasource.username",mysql::getUsername);
        p.add("db_app_password",mysql::getPassword);p.add("spring.flyway.user",mysql::getUsername);p.add("db_migration_password",mysql::getPassword);
    }
    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired Environment environment;
    static final String PASSWORD="frase privada para probar operaciones";
    long admin,staff,otherStaff,inactiveStaff,client,otherClient,inactiveClient;
    Browser personal;

    @BeforeEach void fixtures() throws Exception {
        jdbc.sql("DELETE FROM orden").update();jdbc.sql("DELETE FROM vehiculo").update();jdbc.sql("DELETE FROM usuario").update();
        String hash=encoder.encode(PASSWORD);
        admin=user("ADMINISTRACION","admin",true,hash);staff=user("PERSONAL","personal",true,hash);
        otherStaff=user("PERSONAL","personal2",true,hash);inactiveStaff=user("PERSONAL","inactivo",false,hash);
        client=user("CLIENTE","cliente",true,hash);otherClient=user("CLIENTE","cliente2",true,hash);
        inactiveClient=user("CLIENTE","cliente-inactivo",false,hash);
        personal=new Browser();personal.login("personal");
    }
    long user(String perfil,String prefix,boolean active,String hash) {
        var keys=new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO usuario(perfil,nombre,correo,clave_hash,activo) VALUES (:perfil,:nombre,:correo,:hash,:activo)")
            .param("perfil",perfil).param("nombre",prefix).param("correo",prefix+"@taller.example").param("hash",hash).param("activo",active).update(keys,"id");
        return Objects.requireNonNull(keys.getKey()).longValue();
    }
    long count(String table) {return jdbc.sql("SELECT COUNT(*) FROM "+table).query(Long.class).single();}
    static long id(String json) {
        var match=Pattern.compile("\"id\":(\\d+)").matcher(json);assertTrue(match.find(),json);return Long.parseLong(match.group(1));
    }
    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient http=HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        URI uri(String path) {return URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+path);}
        HttpResponse<String> get(String path) throws Exception {
            return http.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception {
            var response=get("/api/auth/csrf");
            var match=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());assertTrue(match.find());return token=match.group(1);
        }
        HttpResponse<String> raw(String method,String path,String body,boolean withCsrf) throws Exception {
            var request=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
            if(withCsrf)request.header("X-CSRF-TOKEN",token==null?csrf():token);
            return http.send(request.method(method,HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> post(String path,String body) throws Exception {return raw("POST",path,body,true);}
        void login(String prefix) throws Exception {
            var response=post("/api/auth/login","{\"correo\":\""+prefix+"@taller.example\",\"contrasena\":\""+PASSWORD+"\"}");
            assertEquals(200,response.statusCode(),response.body());token=null;
        }
        HttpResponse<String> vehicle(long owner,String plate) throws Exception {
            return post("/api/vehiculos","{\"clienteId\":"+owner+",\"dominio\":\""+plate+"\",\"marca\":\"Citroën\",\"modelo\":\"C3\"}");
        }
        HttpResponse<String> order(long vehicle,long responsible,String extra) throws Exception {
            return post("/api/ordenes","{\"vehiculoId\":"+vehicle+",\"responsableId\":"+responsible+
                    ",\"motivoIngreso\":\"Revisar frenos\""+extra+"}");
        }
    }
    long car(long owner,String plate) throws Exception {
        var response=personal.vehicle(owner,plate);assertEquals(201,response.statusCode(),response.body());return id(response.body());
    }

    @Test void normalizesPlateAndReturnsExistingVehicleWithoutChangingOwner() throws Exception {
        var first=personal.vehicle(client,"ab-123 cd");assertEquals(201,first.statusCode(),first.body());
        assertTrue(first.body().contains("\"dominio\":\"AB123CD\""));
        var duplicate=personal.vehicle(otherClient,"AB123CD");assertEquals(200,duplicate.statusCode(),duplicate.body());
        assertFalse(duplicate.body().contains("\"creado\":true"));
        assertEquals(id(first.body()),id(duplicate.body()));assertEquals(1,count("vehiculo"));
        assertTrue(duplicate.body().contains("\"clienteId\":"+client));
    }

    @Test void rejectsInvalidVehicleFieldsAndInactiveOrWrongProfileClient() throws Exception {
        assertEquals(400,personal.vehicle(inactiveClient,"AB123CD").statusCode());
        assertEquals(400,personal.vehicle(staff,"AB123CD").statusCode());
        assertEquals(400,personal.vehicle(999999,"AB123CD").statusCode());
        assertEquals(400,personal.vehicle(client,"???").statusCode());
        assertEquals(400,personal.post("/api/vehiculos","{\"clienteId\":"+client+",\"dominio\":\"AB123CD\",\"marca\":\" \",\"modelo\":\"C3\"}").statusCode());
        assertEquals(0,count("vehiculo"));
    }

    @Test void concurrentDuplicateRegistrationCreatesExactlyOneVehicle() throws Exception {
        var second=new Browser();second.login("personal2");
        // Obtain tokens before the barrier so the race is in registration, not session creation.
        personal.csrf();second.csrf();
        var start=new CountDownLatch(1);
        try(var executor=Executors.newVirtualThreadPerTaskExecutor()) {
            var a=executor.submit(()->{start.await();return personal.vehicle(client,"ab-123 cd");});
            var b=executor.submit(()->{start.await();return second.vehicle(client,"AB123CD");});
            start.countDown();
            var first=a.get(20,TimeUnit.SECONDS);var other=b.get(20,TimeUnit.SECONDS);
            assertEquals(Set.of(200,201),Set.of(first.statusCode(),other.statusCode()),first.body()+other.body());
            assertEquals(id(first.body()),id(other.body()));assertEquals(1,count("vehiculo"));
        }
    }

    @Test void opensReceivedOrderWithDerivedOwnerAndNewServiceForSameVehicle() throws Exception {
        long vehicle=car(client,"AB123CD");
        var response=personal.order(vehicle,otherStaff,",\"estado\":\"FINALIZADO\",\"ingresoFinal\":\"999999\"");
        assertEquals(201,response.statusCode(),response.body());assertTrue(response.body().contains("\"estado\":\"RECIBIDO\""));
        assertTrue(response.body().contains("\"clienteId\":"+client));assertTrue(response.body().contains("\"responsableId\":"+otherStaff));
        long order=id(response.body());
        var row=jdbc.sql("SELECT estado,ingreso_final,finalizado_en,presupuesto_autorizado_id FROM orden WHERE id=:id").param("id",order).query().singleRow();
        assertEquals("RECIBIDO",row.get("estado"));assertNull(row.get("ingreso_final"));assertNull(row.get("finalizado_en"));
        assertNull(row.get("presupuesto_autorizado_id"));
        var again=personal.order(vehicle,staff,"");assertEquals(201,again.statusCode());
        assertNotEquals(order,id(again.body()));assertEquals(1,count("vehiculo"));assertEquals(2,count("orden"));
        assertTrue(response.headers().firstValue("Location").orElse("").endsWith("/api/ordenes/"+order));
    }

    @Test void rejectsForgedOwnerAndInvalidResponsibleWithoutSavingOrder() throws Exception {
        long vehicle=car(client,"AB123CD");
        assertEquals(409,personal.order(vehicle,staff,",\"clienteId\":"+otherClient).statusCode());
        assertEquals(400,personal.order(vehicle,inactiveStaff,"").statusCode());
        assertEquals(400,personal.order(vehicle,admin,"").statusCode());
        assertEquals(400,personal.order(vehicle,999999,"").statusCode());
        assertEquals(400,personal.post("/api/ordenes","{\"vehiculoId\":"+vehicle+",\"responsableId\":"+staff+",\"motivoIngreso\":\" \"}").statusCode());
        assertEquals(404,personal.order(999999,staff,"").statusCode());assertEquals(0,count("orden"));
    }

    @Test void inactiveVehicleOwnerBlocksOpening() throws Exception {
        long vehicle=car(client,"AB123CD");
        jdbc.sql("UPDATE usuario SET activo=FALSE WHERE id=:id").param("id",client).update();
        assertEquals(400,personal.order(vehicle,staff,"").statusCode());assertEquals(0,count("orden"));
    }

    @Test void scopesListsDetailsAndFiltersToAuthenticatedClient() throws Exception {
        long mine=car(client,"AB123CD"),foreign=car(otherClient,"AC456EF");
        long ownOrder=id(personal.order(mine,staff,"").body()),foreignOrder=id(personal.order(foreign,staff,"").body());
        var customer=new Browser();customer.login("cliente");
        var vehicles=customer.get("/api/vehiculos");assertTrue(vehicles.body().contains("\"total\":1"));assertFalse(vehicles.body().contains("AC456EF"));
        var orders=customer.get("/api/ordenes");assertTrue(orders.body().contains("\"total\":1"));assertFalse(orders.body().contains("AC456EF"));
        assertEquals(200,customer.get("/api/vehiculos/"+mine).statusCode());assertEquals(200,customer.get("/api/ordenes/"+ownOrder).statusCode());
        assertEquals(404,customer.get("/api/vehiculos/"+foreign).statusCode());assertEquals(404,customer.get("/api/ordenes/"+foreignOrder).statusCode());
        assertEquals(403,customer.get("/api/ordenes?clienteId="+otherClient).statusCode());
        assertEquals(403,customer.get("/api/vehiculos?clienteId="+otherClient).statusCode());
        assertEquals(404,customer.get("/api/ordenes?vehiculoId="+foreign).statusCode());
        for(String field:List.of("clave","correo","tarifa","margen","costo","token","credencial"))assertFalse(orders.body().contains(field),orders.body());
    }

    @Test void administrationReadsAndOnlyStaffCreatesWithNoOwnershipEditRoute() throws Exception {
        long vehicle=car(client,"AB123CD");long order=id(personal.order(vehicle,staff,"").body());
        for(String account:List.of("admin","cliente")) {
            var caller=new Browser();caller.login(account);
            assertEquals(200,caller.get("/api/vehiculos/"+vehicle).statusCode());
            assertEquals(200,caller.get("/api/ordenes/"+order).statusCode());
            assertEquals(403,caller.vehicle(client,"AC456EF").statusCode());
            assertEquals(403,caller.order(vehicle,staff,"").statusCode());
        }
        assertEquals(403,personal.raw("PATCH","/api/vehiculos/"+vehicle,"{\"clienteId\":"+otherClient+"}",true).statusCode());
        assertEquals(403,personal.raw("DELETE","/api/vehiculos/"+vehicle,"{}",true).statusCode());
        assertEquals(1,count("vehiculo"));assertEquals(1,count("orden"));
    }

    @Test void cataloguesContainOnlyActiveAccountsOfTheRequestedProfile() throws Exception {
        var clients=personal.get("/api/catalogos/clientes");assertEquals(200,clients.statusCode());
        assertTrue(clients.body().contains("cliente@taller.example"));assertTrue(clients.body().contains("cliente2@taller.example"));
        assertFalse(clients.body().contains("cliente-inactivo"));assertFalse(clients.body().contains("personal@"));
        var people=personal.get("/api/catalogos/personal");assertFalse(people.body().contains("inactivo"));assertFalse(people.body().contains("admin@"));
        var customer=new Browser();customer.login("cliente");assertEquals(403,customer.get("/api/catalogos/clientes").statusCode());
    }

    @Test void filtersPaginationAndLiteralSearchDoNotBroadenResults() throws Exception {
        long a=car(client,"AB123CD"),b=car(otherClient,"AC456EF");
        personal.order(a,staff,"");personal.order(a,staff,"");personal.order(b,staff,"");
        var page=personal.get("/api/ordenes?size=1&page=1");
        assertEquals(200,page.statusCode());assertTrue(page.body().contains("\"total\":3"));
        assertEquals(1,Pattern.compile("\"motivoIngreso\"").matcher(page.body()).results().count());
        assertTrue(personal.get("/api/vehiculos?q=ab-123%20cd").body().contains("\"total\":1"));
        assertTrue(personal.get("/api/vehiculos?q=%25").body().contains("\"total\":0"));
        assertTrue(personal.get("/api/ordenes?q="+URLEncoder.encode("' OR 1=1 --",java.nio.charset.StandardCharsets.UTF_8)).body().contains("\"total\":0"));
        assertTrue(personal.get("/api/ordenes?vehiculoId="+a).body().contains("\"total\":2"));
        for(String path:List.of("/api/vehiculos?page=-1","/api/ordenes?size=51","/api/ordenes?estado=INVENTADO","/api/vehiculos?clienteId=texto"))
            assertEquals(400,personal.get(path).statusCode(),path);
    }

    @Test void requiresSessionAndCsrfForWrites() throws Exception {
        var anonymous=new Browser();assertEquals(401,anonymous.get("/api/vehiculos").statusCode());
        assertEquals(401,anonymous.get("/api/ordenes").statusCode());
        assertEquals(403,personal.raw("POST","/api/vehiculos","{\"clienteId\":"+client+",\"dominio\":\"AB123CD\",\"marca\":\"M\",\"modelo\":\"M\"}",false).statusCode());
        assertEquals(0,count("vehiculo"));
    }
}
