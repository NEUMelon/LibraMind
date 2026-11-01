package com.libramind.auth;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

/** 在独立 MySQL 测试库验证真实 HTTP 登录、CSRF、Session 轮换与退出。 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.config.import=file:./config/test-local.properties"})
class FoundationIT {

    @LocalServerPort int port;

    @Value("${app.bootstrap.username}")
    String username;

    @Value("${app.bootstrap.password}")
    String password;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void publicVerificationPageAndAssetsDoNotRequireLogin() throws Exception {
        HttpClient client = HttpClient.newHttpClient();
        assertThat(get(client, "/").statusCode()).isEqualTo(200);
        assertThat(get(client, "/index.html").statusCode()).isEqualTo(200);
        assertThat(get(client, "/assets/app.js").statusCode()).isEqualTo(200);
        assertThat(get(client, "/api/v1/books?page=0").statusCode()).isEqualTo(400);
        assertThat(get(client, "/api/v1/copies?status=made-up").statusCode()).isEqualTo(400);
    }

    @Test
    void loginRequiresCsrfRotatesSessionAndLogoutInvalidatesAuthentication() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        HttpClient client = HttpClient.newBuilder().cookieHandler(cookies).build();
        assertThat(get(client, "/api/v1/auth/me").statusCode()).isEqualTo(401);
        assertThat(get(client, "/actuator/health/readiness").statusCode()).isEqualTo(200);
        String login =
                json.writeValueAsString(
                        java.util.Map.of("username", username, "password", password));
        assertThat(post(client, "/api/v1/auth/login", login, null).statusCode()).isEqualTo(403);

        String csrf = token(client);
        String before =
                cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        HttpResponse<String> invalid =
                post(
                        client,
                        "/api/v1/auth/login",
                        "{\"username\":\"admin\",\"password\":\"wrong\"}",
                        csrf);
        assertThat(invalid.statusCode()).isEqualTo(401);
        assertThat(json.readTree(invalid.body()).path("code").asText())
                .isEqualTo("AUTHENTICATION_FAILED");
        HttpResponse<String> loggedIn = post(client, "/api/v1/auth/login", login, csrf);
        assertThat(loggedIn.statusCode()).isEqualTo(200);
        assertThat(loggedIn.body()).doesNotContain("password", "passwordHash", password);
        String after =
                cookies.getCookieStore().getCookies().stream()
                        .filter(cookie -> cookie.getName().equals("JSESSIONID"))
                        .findFirst()
                        .orElseThrow()
                        .getValue();
        assertThat(after).isNotEqualTo(before);
        assertThat(get(client, "/api/v1/auth/me").statusCode()).isEqualTo(200);
        assertThat(post(client, "/api/v1/auth/logout", "{}", csrf).statusCode()).isEqualTo(403);
        assertThat(post(client, "/api/v1/auth/logout", "{}", token(client)).statusCode())
                .isEqualTo(200);
        assertThat(get(client, "/api/v1/auth/me").statusCode()).isEqualTo(401);
    }

    @Test
    void docsAreRealAndValidationErrorsHaveRequestId() throws Exception {
        HttpClient client =
                HttpClient.newBuilder()
                        .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                        .build();
        JsonNode docs = json.readTree(get(client, "/openapi.json").body());
        assertThat(docs.path("paths").has("/api/v1/auth/login")).isTrue();
        HttpResponse<String> invalid = post(client, "/api/v1/auth/login", "{}", token(client));
        assertThat(invalid.statusCode()).isEqualTo(400);
        JsonNode body = json.readTree(invalid.body());
        assertThat(body.path("details").has("username")).isTrue();
        assertThat(body.path("details").has("password")).isTrue();
        assertThat(body.path("requestId").asText())
                .isEqualTo(invalid.headers().firstValue("X-Request-Id").orElseThrow());
    }

    private String token(HttpClient client) throws Exception {
        return json.readTree(get(client, "/api/v1/auth/csrf").body())
                .path("data")
                .path("token")
                .asText();
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(HttpClient client, String path, String body, String token)
            throws Exception {
        HttpRequest.Builder request =
                HttpRequest.newBuilder(uri(path)).header("Content-Type", "application/json");
        if (token != null) {
            request.header("X-CSRF-TOKEN", token);
        }
        return client.send(
                request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }
}
