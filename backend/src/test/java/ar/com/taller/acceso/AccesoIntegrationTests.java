package ar.com.taller.acceso;

import java.net.*;
import java.net.http.*;
import java.time.*;
import java.util.List;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import ar.com.taller.TallerOrdenesApplication;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes={TallerOrdenesApplication.class,AccesoIntegrationTests.Config.class})
class AccesoIntegrationTests {
    @Container static final MySQLContainer mysql = new MySQLContainer(DockerImageName.parse(
            "mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("acceso_test").withUsername("test_user").withPassword("test-only");

    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url",mysql::getJdbcUrl);
        properties.add("spring.datasource.username",mysql::getUsername);
        properties.add("db_app_password",mysql::getPassword);
        properties.add("spring.flyway.user",mysql::getUsername);
        properties.add("db_migration_password",mysql::getPassword);
    }

    @TestConfiguration(proxyBeanMethods=false) static class Config {
        @Bean @Primary TestClock testClock() { return new TestClock(); }
    }

    static class TestClock extends Clock {
        final AtomicReference<Instant> now=new AtomicReference<>(Instant.parse("2026-11-07T12:00:00Z"));
        void advance(Duration duration) { now.updateAndGet(i->i.plus(duration)); }
        public Instant instant() { return now.get(); }
        public ZoneId getZone() { return ZoneOffset.UTC; }
        public Clock withZone(ZoneId zone) { return this; }
    }

    @Autowired JdbcClient jdbc;
    @Autowired PasswordEncoder encoder;
    @Autowired Environment environment;
    @Autowired TestClock clock;
    private static final String PASSWORD="frase privada para estas pruebas";
    private Browser browser;

    @BeforeEach void fixtures() {
        clock.advance(Duration.ofDays(1)); // Expire only prior test counters without a production reset hook.
        jdbc.sql("DELETE FROM usuario").update();
        String hash=encoder.encode(PASSWORD);
        for(String profile:List.of("ADMINISTRACION","PERSONAL","CLIENTE")) {
            jdbc.sql("INSERT INTO usuario(perfil,nombre,correo,clave_hash,activo) VALUES (:perfil,:nombre,:correo,:hash,TRUE)")
                    .param("perfil",profile).param("nombre","Prueba "+profile)
                    .param("correo",profile.toLowerCase()+"@taller.example").param("hash",hash).update();
        }
        jdbc.sql("INSERT INTO usuario(perfil,nombre,correo,clave_hash,activo) VALUES ('CLIENTE','Inactiva','inactiva@taller.example',:hash,FALSE)")
                .param("hash",hash).update();
        browser=new Browser();
    }

    class Browser {
        final CookieManager cookies=new CookieManager(null,CookiePolicy.ACCEPT_ALL);
        final HttpClient client=HttpClient.newBuilder().cookieHandler(cookies).build();
        String token;
        URI uri(String path) { return URI.create("http://localhost:"+environment.getRequiredProperty("local.server.port")+path); }
        HttpResponse<String> get(String path) throws Exception {
            return client.send(HttpRequest.newBuilder(uri(path)).GET().build(),HttpResponse.BodyHandlers.ofString());
        }
        String csrf() throws Exception {
            var response=get("/api/auth/csrf");
            assertEquals(200,response.statusCode(),response.body());
            var matcher=Pattern.compile("\"token\":\"([^\"]+)\"").matcher(response.body());
            assertTrue(matcher.find(),response.body());
            return token=matcher.group(1);
        }
        HttpResponse<String> post(String path,String body,String csrfToken) throws Exception {
            var request=HttpRequest.newBuilder(uri(path)).header("Content-Type","application/json");
            if(csrfToken!=null) request.header("X-CSRF-TOKEN",csrfToken);
            return client.send(request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
        }
        HttpResponse<String> login(String email,String password) throws Exception {
            if(token==null) csrf();
            return post("/api/auth/login","{\"correo\":\""+email+"\",\"contrasena\":\""+password+"\"}",token);
        }
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(c->c.getName().equals("JSESSIONID"))
                    .map(HttpCookie::getValue).findFirst().orElse("");
        }
    }

    @Test void requiresAuthenticationAndCsrfEvenOnLogin() throws Exception {
        assertEquals(401,browser.get("/api/auth/me").statusCode());
        var response=browser.post("/api/auth/login","{\"correo\":\"cliente@taller.example\",\"contrasena\":\""+PASSWORD+"\"}",null);
        assertEquals(403,response.statusCode());
        assertTrue(response.body().contains("CSRF_INVALIDO"));
    }

    @Test void rotatesSessionPersistsIdentityAndDoesNotExposeSecrets() throws Exception {
        browser.csrf();
        String initial=browser.sessionId();
        var login=browser.login("CLIENTE@taller.example",PASSWORD);
        assertEquals(200,login.statusCode(),login.body());
        assertFalse(initial.isEmpty());
        assertNotEquals(initial,browser.sessionId());
        var me=browser.get("/api/auth/me");
        assertEquals(200,me.statusCode());
        assertTrue(me.body().contains("\"perfil\":\"CLIENTE\""));
        for(String secret:List.of("clave_hash","claveHash","contrasena","argon2","tarifa","margen","costo","token_canal")) {
            assertFalse(me.body().contains(secret),me.body());
        }
        String cookies=String.join(";",login.headers().allValues("Set-Cookie"));
        assertTrue(cookies.contains("HttpOnly"));
        assertTrue(cookies.contains("SameSite=Lax"));
        assertTrue(login.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
    }

    @Test void providesSameErrorForUnknownInactiveAndWrongPassword() throws Exception {
        var unknown=browser.login("desconocida@taller.example",PASSWORD);
        var inactive=browser.login("inactiva@taller.example",PASSWORD);
        var wrong=browser.login("cliente@taller.example","incorrecta");
        assertEquals(401,unknown.statusCode());
        assertEquals(401,inactive.statusCode());
        assertEquals(401,wrong.statusCode());
        assertEquals(unknown.body(),inactive.body());
        assertEquals(unknown.body(),wrong.body());
        assertEquals(401,browser.get("/api/auth/me").statusCode());
    }

    @Test void validatesAllThreeRolesDirectlyInApi() throws Exception {
        for(String profile:List.of("ADMINISTRACION","PERSONAL","CLIENTE")) {
            var caller=new Browser();
            assertEquals(200,caller.login(profile.toLowerCase()+"@taller.example",PASSWORD).statusCode());
            for(String area:List.of("administracion","personal","cliente")) {
                var response=caller.get("/api/"+area+"/inicio");
                assertEquals(area.equals(profile.toLowerCase())?200:403,response.statusCode(),response.body());
            }
        }
    }

    @Test void clientCannotChooseAdministratorRoleInJson() throws Exception {
        var response=browser.post("/api/auth/login",
                "{\"correo\":\"cliente@taller.example\",\"contrasena\":\""+PASSWORD+"\",\"perfil\":\"ADMINISTRACION\"}",browser.csrf());
        if(response.statusCode()==400) {
            assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        } else {
            assertEquals(200,response.statusCode(),response.body());
            assertTrue(response.body().contains("\"perfil\":\"CLIENTE\""));
        }
        assertEquals(403,browser.get("/api/administracion/inicio").statusCode());
    }

    @Test void invalidatesOldCsrfAfterLoginAndSessionAfterLogout() throws Exception {
        String oldToken=browser.csrf();
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        String authenticatedId=browser.sessionId();
        assertEquals(403,browser.post("/api/auth/logout","",oldToken).statusCode());
        assertEquals(204,browser.post("/api/auth/logout","",browser.csrf()).statusCode());
        assertEquals(401,browser.get("/api/auth/me").statusCode());
        var replay=HttpRequest.newBuilder(browser.uri("/api/auth/me")).header("Cookie","JSESSIONID="+authenticatedId).GET().build();
        assertEquals(401,HttpClient.newHttpClient().send(replay,HttpResponse.BodyHandlers.ofString()).statusCode());
        assertNotNull(browser.csrf());
    }

    @Test void expiresAtTwentyMinutesOfInactivity() throws Exception {
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        clock.advance(Duration.ofMinutes(20));
        var response=browser.get("/api/auth/me");
        assertEquals(401,response.statusCode());
        assertTrue(response.body().contains("SESION_VENCIDA"));
    }

    @Test void expiresAtEightHoursEvenWithRegularActivity() throws Exception {
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        for(int i=0;i<25;i++) {
            clock.advance(Duration.ofMinutes(19));
            assertEquals(200,browser.get("/api/auth/me").statusCode());
        }
        clock.advance(Duration.ofMinutes(5));
        assertEquals(401,browser.get("/api/auth/me").statusCode());
    }

    @Test void revokesSessionWhenAccountIsDisabledOrRoleChanges() throws Exception {
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        jdbc.sql("UPDATE usuario SET activo=FALSE WHERE correo='cliente@taller.example'").update();
        assertEquals(401,browser.get("/api/auth/me").statusCode());
        jdbc.sql("UPDATE usuario SET activo=TRUE WHERE correo='cliente@taller.example'").update();
        browser=new Browser();
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        jdbc.sql("UPDATE usuario SET perfil='PERSONAL' WHERE correo='cliente@taller.example'").update();
        assertEquals(401,browser.get("/api/personal/inicio").statusCode());
    }

    @Test void revokesPreviousSessionWhenPasswordIsReplaced() throws Exception {
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
        jdbc.sql("UPDATE usuario SET clave_hash=:hash WHERE correo='cliente@taller.example'")
                .param("hash",encoder.encode("otra frase privada para estas pruebas")).update();
        assertEquals(401,browser.get("/api/auth/me").statusCode());
        browser=new Browser();
        assertEquals(401,browser.login("cliente@taller.example",PASSWORD).statusCode());
        assertEquals(200,browser.login("cliente@taller.example","otra frase privada para estas pruebas").statusCode());
    }

    @Test void blocksAfterFiveFailuresAndAcceptsAfterTheWaitingPeriod() throws Exception {
        for(int i=0;i<5;i++) assertEquals(401,browser.login("cliente@taller.example","incorrecta").statusCode());
        var blocked=browser.login("cliente@taller.example",PASSWORD);
        assertEquals(429,blocked.statusCode());
        assertTrue(blocked.headers().firstValue("Retry-After").isPresent());
        clock.advance(Duration.ofMinutes(15));
        assertEquals(200,browser.login("cliente@taller.example",PASSWORD).statusCode());
    }
}
