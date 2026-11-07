package ar.com.taller;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;
import static org.junit.jupiter.api.Assertions.*;

@Testcontainers
class TallerOrdenesApplicationTests {
    @Container
    static final MySQLContainer mysql = new MySQLContainer(
            DockerImageName.parse("mysql@sha256:a81d043930e51745522bd5c430961cf8286f0a23f0a9a2a6e0108f7ea71b0902").asCompatibleSubstituteFor("mysql"))
            .withDatabaseName("taller_test").withUsername("taller_test").withPassword("test-only");
    private Connection connection;
    private long client, otherClient, staff, vehicle, order;

    @BeforeAll static void migrate() {
        var flyway = Flyway.configure().dataSource(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword()).load();
        assertEquals(2, flyway.migrate().migrationsExecuted);
        assertEquals(0, flyway.migrate().migrationsExecuted);
        flyway.validate();
    }

    @BeforeEach void fixtures() throws SQLException {
        connection = DriverManager.getConnection(mysql.getJdbcUrl(), mysql.getUsername(), mysql.getPassword());
        connection.setAutoCommit(false);
        client = user("CLIENTE", "cliente");
        otherClient = user("CLIENTE", "otro");
        staff = user("PERSONAL", "personal");
        vehicle = insert("INSERT INTO vehiculo(cliente_id,dominio,marca,modelo) VALUES ("+client+",'AB123CD','Marca','Modelo')");
        order = newOrder(client, vehicle);
    }

    @AfterEach void rollback() throws SQLException {
        if (connection != null) { connection.rollback(); connection.close(); }
    }

    private long insert(String sql) throws SQLException {
        try (var statement = connection.createStatement()) {
            statement.executeUpdate(sql, java.sql.Statement.RETURN_GENERATED_KEYS);
            try (var keys = statement.getGeneratedKeys()) { assertTrue(keys.next()); return keys.getLong(1); }
        }
    }

    private void execute(String sql) throws SQLException {
        try (var statement = connection.createStatement()) { statement.executeUpdate(sql); }
    }

    private long user(String profile, String prefix) throws SQLException {
        return insert("INSERT INTO usuario(perfil,nombre,correo,clave_hash) VALUES ('"+profile+"','Prueba','"+prefix+"@taller.example','hash-de-prueba')");
    }

    private long newOrder(long owner, long car) throws SQLException {
        return insert("INSERT INTO orden(cliente_id,vehiculo_id,responsable_id,motivo_ingreso,recibido_en,actualizado_en) VALUES ("+
                owner+","+car+","+staff+",'Revisión',UTC_TIMESTAMP(3),UTC_TIMESTAMP(3))");
    }

    private long budget(long targetOrder, int version) throws SQLException {
        return insert("INSERT INTO presupuesto(orden_id,version,autor_id,publicado_en,vence_en,total) VALUES ("+
                targetOrder+","+version+","+staff+",UTC_TIMESTAMP(3),DATE_ADD(UTC_TIMESTAMP(3),INTERVAL 1 DAY),70000)");
    }

    @Test void hasExactlyFiveDomainTables() throws SQLException {
        try (var query = connection.createStatement(); var rows = query.executeQuery(
                "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name <> 'flyway_schema_history'")) {
            assertTrue(rows.next()); assertEquals(5, rows.getInt(1));
        }
    }

    @Test void rejectsWrongVehicleOwner() {
        assertThrows(SQLException.class, () -> newOrder(otherClient, vehicle));
    }

    @Test void rejectsDuplicateOrUnnormalizedPlate() {
        assertThrows(SQLException.class, () -> insert("INSERT INTO vehiculo(cliente_id,dominio,marca,modelo) VALUES ("+client+",'AB123CD','M','M')"));
        assertThrows(SQLException.class, () -> insert("INSERT INTO vehiculo(cliente_id,dominio,marca,modelo) VALUES ("+client+",'ab-456-ef','M','M')"));
    }

    @Test void enforcesSingleAdministratorAndAdminOnlyTariff() throws SQLException {
        user("ADMINISTRACION","admin");
        assertThrows(SQLException.class, () -> user("ADMINISTRACION","admin2"));
        assertThrows(SQLException.class, () -> execute("UPDATE usuario SET tarifa_hora_vigente=6000 WHERE id="+staff));
    }

    @Test void rejectsAuthorizationFromAnotherOrder() throws SQLException {
        long otherOrder = newOrder(client, vehicle);
        long wrongBudget = budget(otherOrder, 1);
        assertThrows(SQLException.class, () -> execute("UPDATE orden SET presupuesto_autorizado_id="+wrongBudget+" WHERE id="+order));
    }

    @Test void rejectsRepeatedVersionAndInvalidResponse() throws SQLException {
        long id = budget(order,1);
        assertThrows(SQLException.class, () -> budget(order,1));
        assertThrows(SQLException.class, () -> execute("UPDATE presupuesto SET decision='ACEPTADO' WHERE id="+id));
    }

    @Test void rejectsInvalidLineAndRepeatedNumber() throws SQLException {
        long id = budget(order,1);
        String prefix = "INSERT INTO renglon(presupuesto_id,numero,tipo,descripcion,cantidad,precio_unitario) VALUES (";
        assertThrows(SQLException.class, () -> insert(prefix+id+",1,'REPUESTO','Filtro',0,10)"));
        insert(prefix+id+",1,'REPUESTO','Filtro',1,10)");
        assertThrows(SQLException.class, () -> insert(prefix+id+",1,'MANO_OBRA','Trabajo',1,10)"));
        assertThrows(SQLException.class, () -> insert(prefix+id+",2,'REPUESTO','Filtro',1,-1)"));
    }

    @Test void rejectsIncompleteTokenAndClosedOrder() {
        assertThrows(SQLException.class, () -> execute("UPDATE usuario SET token_canal_hash='hash' WHERE id="+client));
        assertThrows(SQLException.class, () -> execute("UPDATE orden SET estado='FINALIZADO' WHERE id="+order));
        assertThrows(SQLException.class, () -> execute("UPDATE orden SET estado='CANCELADO' WHERE id="+order));
    }

    @Test void rejectsMoneyOverflowAndReferencedDeletion() throws SQLException {
        long id = budget(order,1);
        assertThrows(SQLException.class, () -> execute("UPDATE presupuesto SET total=10000000000.00 WHERE id="+id));
        assertThrows(SQLException.class, () -> execute("DELETE FROM usuario WHERE id="+client));
    }
}
