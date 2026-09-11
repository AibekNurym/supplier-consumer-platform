package com.supplierconsumer.contract;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * End-to-end checks of the HTTP contract against a real Postgres.
 *
 * <p>These assert the details that a plain "does it return 200" test misses: whether a value is a
 * JSON string or a number, which keys are present, what order they are in, and which status code a
 * particular branch produces. Those are exactly the things that differ between this service and a
 * naive port, and they are invisible until a client breaks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiContractTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.jwt.secret", () -> "contract-test-secret-at-least-32-bytes!!");
        registry.add("app.jwt.refresh-secret", () -> "contract-test-refresh-secret-32-bytes!!");
        registry.add("app.socketio.enabled", () -> "false");
        registry.add("app.bootstrap.admin.password", () -> "ContractAdmin123!");
        registry.add("spring.data.redis.host", () -> "localhost");
        registry.add("spring.data.redis.port", () -> "1");
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper mapper;

    private MockMvc mvc;

    private MockMvc mvc() {
        if (mvc == null) {
            mvc = MockMvcBuilders.webAppContextSetup(context).build();
        }
        return mvc;
    }

    private static String ownerToken;
    private static String consumerToken;

    private JsonNode json(MvcResult result) throws Exception {
        return mapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String body(MvcResult result) throws Exception {
        return result.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    @Order(1)
    @DisplayName("register returns 201, camelCase with a snake_case company_id, permissions as objects")
    void registerShape() throws Exception {
        MvcResult result = mvc().perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@contract.test","password":"Secret123!",
                                 "firstName":"Ada","lastName":"Lovelace"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);

        JsonNode user = json(result).path("data").path("user");
        assertThat(user.has("firstName")).isTrue();
        assertThat(user.has("company_id")).as("company_id stays snake_case inside a camelCase object")
                .isTrue();
        assertThat(user.path("permissions").get(0).isObject())
                .as("register returns permission objects, unlike login")
                .isTrue();

        ownerToken = json(result).path("data").path("tokens").path("accessToken").asText();
    }

    @Test
    @Order(2)
    @DisplayName("an owner whose company is still pending cannot sign in")
    void pendingCompanyBlocksLogin() throws Exception {
        // Registering as an Owner creates a company with status 'pending', and the login gate
        // refuses Owners until an administrator approves it. The console drives its "waiting for
        // approval" screen off this 403 and the extra keys it carries.
        MvcResult result = mvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@contract.test","password":"Secret123!"}"""))
                .andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(result)).isEqualTo("{\"success\":false,"
                + "\"message\":\"Wait until the admin approves you.\","
                + "\"companyStatus\":\"pending\"}");

        // Approve it so the rest of the suite can sign in.
        MvcResult adminLogin = mvc().perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"admin@platform.com","password":"ContractAdmin123!"}""")).andReturn();
        String adminToken = json(adminLogin).path("data").path("tokens").path("accessToken").asText();

        mvc().perform(put("/api/admin/companies/1/approve")
                .header("Authorization", "Bearer " + adminToken)).andReturn();
    }

    @Test
    @Order(3)
    @DisplayName("login returns permissions as plain strings, unlike register")
    void loginShape() throws Exception {
        // The divergence is real and has a visible consequence: the console's permission check
        // compares strings, so a freshly registered user looks unauthorised until the next login.
        MvcResult result = mvc().perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email":"owner@contract.test","password":"Secret123!"}"""))
                .andReturn();

        JsonNode permissions = json(result).path("data").path("user").path("permissions");
        assertThat(permissions.get(0).isTextual()).isTrue();

        ownerToken = json(result).path("data").path("tokens").path("accessToken").asText();
    }

    @Test
    @Order(4)
    @DisplayName("the profile pair disagrees on casing for the same entity")
    void profileCasingSplit() throws Exception {
        JsonNode read = json(mvc().perform(get("/api/auth/profile")
                .header("Authorization", "Bearer " + ownerToken)).andReturn());
        assertThat(read.path("data").path("user").has("firstName")).isTrue();
        assertThat(read.path("data").path("user").has("first_name")).isFalse();

        JsonNode written = json(mvc().perform(put("/api/auth/profile")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"firstName":"Ada","lastName":"Byron"}""")).andReturn());
        assertThat(written.path("data").has("first_name")).isTrue();
        assertThat(written.path("data").has("firstName")).isFalse();
    }

    @Test
    @Order(5)
    @DisplayName("a price is a JSON string and a quantity is a number")
    void decimalsAreStrings() throws Exception {
        String created = body(mvc().perform(post("/api/products")
                .header("Authorization", "Bearer " + ownerToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"name":"Widget","image":"https://x/i.png","price":1200,
                         "minimum_order_quantity":1,"available_quantity":10}""")).andReturn());

        assertThat(created).contains("\"price\":\"1200.00\"");
        assertThat(created).contains("\"available_quantity\":10");
        assertThat(created).doesNotContain("\"price\":1200");
    }

    @Test
    @Order(6)
    @DisplayName("product create omits message; delete emits data before message")
    void envelopeVariations() throws Exception {
        String list = body(mvc().perform(get("/api/products")
                .header("Authorization", "Bearer " + ownerToken)).andReturn());
        assertThat(list).startsWith("{\"success\":true,\"data\":");
        assertThat(list).doesNotContain("\"message\"");

        String deleted = body(mvc().perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .delete("/api/products/1")
                                .header("Authorization", "Bearer " + ownerToken))
                .andReturn());
        assertThat(deleted).startsWith("{\"success\":true,\"data\":");
        assertThat(deleted).endsWith("\"message\":\"Product deleted successfully\"}");
    }

    @Test
    @Order(7)
    @DisplayName("a bare COUNT(*) is a string while COUNT(*)::INT is a number")
    void countTypesDiffer() throws Exception {
        String roles = body(mvc().perform(get("/api/users/roles")
                .header("Authorization", "Bearer " + ownerToken)).andReturn());
        assertThat(roles).contains("\"user_count\":\"");

        mvc().perform(post("/api/consumer/auth/register")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"buyer@contract.test","password":"Buyer123!",
                         "firstName":"Nina","lastName":"Simone"}"""));

        MvcResult login = mvc().perform(post("/api/consumer/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"buyer@contract.test","password":"Buyer123!"}""")).andReturn();
        consumerToken = json(login).path("data").path("tokens").path("accessToken").asText();

        String feed = body(mvc().perform(get("/api/notifications/consumer")
                .header("Authorization", "Bearer " + consumerToken)).andReturn());
        assertThat(feed).contains("\"unreadCount\":0");
    }

    @Test
    @Order(8)
    @DisplayName("the two refresh endpoints disagree on nesting")
    void refreshShapesDiffer() throws Exception {
        // The company client stores data wholesale as its token object, so nesting it would break
        // every silent re-authentication. The consumer endpoint does nest it.
        MvcResult companyLogin = mvc().perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"owner@contract.test","password":"Secret123!"}""")).andReturn();
        String companyRefresh = json(companyLogin).path("data").path("tokens")
                .path("refreshToken").asText();

        JsonNode company = json(mvc().perform(post("/api/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        java.util.Map.of("refreshToken", companyRefresh)))).andReturn());
        assertThat(company.path("data").has("accessToken")).isTrue();

        MvcResult consumerLogin = mvc().perform(post("/api/consumer/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"email":"buyer@contract.test","password":"Buyer123!"}""")).andReturn();
        String consumerRefresh = json(consumerLogin).path("data").path("tokens")
                .path("refreshToken").asText();

        JsonNode consumer = json(mvc().perform(post("/api/consumer/auth/refresh-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(
                        java.util.Map.of("refreshToken", consumerRefresh)))).andReturn());
        assertThat(consumer.path("data").has("tokens")).isTrue();
        assertThat(consumer.path("data").has("accessToken")).isFalse();
    }

    @Test
    @Order(9)
    @DisplayName("request-access answers 201 for a new row and 200 when it reopens one")
    void requestAccessStatusVariesByBranch() throws Exception {
        MvcResult first = mvc().perform(post("/api/consumer/catalog/companies/1/request-access")
                .header("Authorization", "Bearer " + consumerToken)).andReturn();
        assertThat(first.getResponse().getStatus()).isEqualTo(201);

        MvcResult second = mvc().perform(post("/api/consumer/catalog/companies/1/request-access")
                .header("Authorization", "Bearer " + consumerToken)).andReturn();
        assertThat(second.getResponse().getStatus()).isEqualTo(200);
        assertThat(body(second)).contains("Access request already exists with status: pending");
    }

    @Test
    @Order(10)
    @DisplayName("an unknown route answers with Express's HTML, not Spring's error JSON")
    void unknownRouteReturnsExpressHtml() throws Exception {
        MvcResult result = mvc().perform(get("/api/does-not-exist")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(404);
        assertThat(result.getResponse().getContentType()).startsWith("text/html");
        assertThat(body(result)).contains("Cannot GET /api/does-not-exist");
    }

    @Test
    @Order(11)
    @DisplayName("a missing token is rejected with the exact message the console displays")
    void authMessagesArePreserved() throws Exception {
        MvcResult result = mvc().perform(get("/api/products")).andReturn();

        assertThat(result.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(result)).isEqualTo(
                "{\"success\":false,\"message\":\"Access token required\"}");
    }
}
