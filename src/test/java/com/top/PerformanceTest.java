package com.top;

import org.apache.jmeter.protocol.http.util.HTTPConstants;
import org.junit.jupiter.api.Test;
import us.abstracta.jmeter.javadsl.core.TestPlanStats;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static us.abstracta.jmeter.javadsl.JmeterDsl.*;

public class PerformanceTest {

    private static final String SEARCH_JOBS_ENDPOINT = "/services/search/jobs";
    private static final String RESULTS_FORMAT = "/results?output_mode=json";

    @Test
    public void test() throws Exception {
        String correlationId = UUID.randomUUID().toString();
        String baseUrl = System.getProperty("app.url", "http://localhost:8080");
        String splunkToken = System.getProperty("splunk.token", "eyJraWQiOiJzcGx1bmsuc2VjcmV0IiwiYWxnIjoiSFM1MTIiLCJ2ZXIiOiJ2MiIsInR0eXAiOiJzdGF0aWMifQ.eyJpc3MiOiJhZG1pbiBmcm9tIDc0NTY2YjliYWI3MyIsInN1YiI6ImFkbWluIiwiYXVkIjoiYXBpIiwiaWRwIjoiU3BsdW5rIiwianRpIjoiMmZhNjgwZjliYTkzYWI3NGY1ZDMyY2E1NmVlOTViMjI3MDgwYmIwNGQ4MjNmMjFiNTg4M2JiMGQ3Zjc5OTI4ZiIsImlhdCI6MTc1MjM5MTY1NiwiZXhwIjoxNzU0OTgzNjU2LCJuYnIiOjE3NTIzOTE2NTZ9.bVief2liJAP71kJbdlcT9vxW5lcTTwxc62RyvOUNDHJlVe4N7dW3Lcu4KdY1f_DS8CorxaDexpXdy104o_EvRQ");
        String splunkHost = System.getProperty("splunk.host", "https://localhost:9089");
        String splunkIndex = System.getProperty("splunk.index", "stopdetails-api");
        System.out.println("splunkToken: "+splunkToken);

        if (splunkToken == null || splunkToken.isEmpty()) {
            throw new IllegalArgumentException("Splunk token must be provided as a system property.");
        }

        TestPlanStats stats = testPlan()
                .tearDownOnlyAfterMainThreadsDone()
                .children(
                        httpCache().disable(),
                        httpCookies().disable(),

                        threadGroup("test-flow", 1, 1,
                                constantTimer(Duration.ofSeconds(15)),
                                httpSampler("TOP-API-Call", baseUrl + "/api/processTopCSVRequest")
                                        .method(HTTPConstants.POST)
                                        .header("X-Correlation-ID", correlationId)
                                        .header("Content-Type", "application/json")
                                        .body("{" +
                                                "\"producer\": \"CPUDWV\"," +
                                                "\"uuid\": \"6662f95f-f5c9-34b2-900a-cb31ac266543\"," +
                                                "\"uuidType\": \"WVID\"," +
                                                "\"naturalId\": \"${__UUID()}\"," +
                                                "\"naturalIdType\": \"confirmationNumber\"," +
                                                "\"source\": \"PD\"," +
                                                "\"correlationId\": \"" + correlationId + "\"," +
                                                "\"destinationFacility\": \"4034\"," +
                                                "\"destinationFacilityOpco\": \"FXG\"," +
                                                "\"address\": {\"recipientName\": \"B TESTING COMPANY\",\"contactName\": \"B\"," +
                                                "\"addressLine1\": \"140 SOUTH RD\",\"city\": \"WILMINGTON\",\"state\": \"MA\"," +
                                                "\"postalCode\": \"01880\",\"countryCode\": \"US\"}," +
                                                "\"auditFlag\": \"L\",\"dataSource\": \"L\",\"recievedDate\": \"2025-06-11T16:28:30.125Z\"," +
                                                "\"tenderOpDate\": \"2025-06-11\",\"tenderOpDateSource\": \"pickupDate\"," +
                                                "\"requestDeliveryTimeWindows\": \"N\"}"),

                                jsr223Sampler("store-correlation-id", "vars.put(\"correlationId\", \"" + correlationId + "\");"),

                                jsr223Sampler("read-splunk-log", vars -> {
                                    disableSslVerification();

                                    String cid = vars.vars.get("correlationId");
                                    String searchQuery = "search index=" + splunkIndex + " \"Request processed successfully\"";
                                    String encodedQuery = URLEncoder.encode(searchQuery, StandardCharsets.UTF_8);

                                    URL url = new URL(splunkHost + SEARCH_JOBS_ENDPOINT);
                                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                                    conn.setConnectTimeout(5000);
                                    conn.setReadTimeout(10000);
                                    conn.setRequestMethod("POST");
                                    conn.setRequestProperty("Authorization", "Bearer " + splunkToken);
                                    conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                                    conn.setDoOutput(true);

                                    try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                                        writer.write("search=" + encodedQuery);
                                    }

                                    String jobId;
                                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                                        String responseStr = reader.lines().reduce("", String::concat);
                                        int sidStart = responseStr.indexOf("<sid>");
                                        int sidEnd = responseStr.indexOf("</sid>");
                                        jobId = (sidStart > 0 && sidEnd > sidStart)
                                                ? responseStr.substring(sidStart + 5, sidEnd)
                                                : null;
                                    }

                                    if (jobId == null) {
                                        throw new RuntimeException("Failed to extract Splunk job ID.");
                                    }

                                    boolean found = false;
                                    for (int i = 0; i < 6; i++) {
                                        Thread.sleep(5000);
                                        URL resultsUrl = new URL(splunkHost + SEARCH_JOBS_ENDPOINT + "/" + jobId + RESULTS_FORMAT);
                                        HttpURLConnection resultsConn = (HttpURLConnection) resultsUrl.openConnection();
                                        resultsConn.setConnectTimeout(5000);
                                        resultsConn.setReadTimeout(10000);
                                        resultsConn.setRequestMethod("GET");
                                        resultsConn.setRequestProperty("Authorization", "Bearer " + splunkToken);

                                        try (BufferedReader resultsReader = new BufferedReader(new InputStreamReader(resultsConn.getInputStream(), StandardCharsets.UTF_8))) {
                                            String resultsStr = resultsReader.lines().reduce("", String::concat);
                                            System.out.println(resultsStr);
                                            if (resultsStr.contains("Request processed successfully")) {
                                                found = true;
                                                break;
                                            }
                                        }
                                    }

                                    // Delete the Splunk job after completion
                                    URL deleteUrl = new URL(splunkHost + SEARCH_JOBS_ENDPOINT + "/" + jobId);
                                    HttpURLConnection deleteConn = (HttpURLConnection) deleteUrl.openConnection();
                                    deleteConn.setConnectTimeout(5000);
                                    deleteConn.setReadTimeout(5000);
                                    deleteConn.setRequestMethod("DELETE");
                                    deleteConn.setRequestProperty("Authorization", "Bearer " + splunkToken);
                                    int deleteResponseCode = deleteConn.getResponseCode();
                                    if (deleteResponseCode != HttpURLConnection.HTTP_OK && deleteResponseCode != HttpURLConnection.HTTP_NO_CONTENT) {
                                        System.err.println("Failed to delete Splunk job, response code: " + deleteResponseCode);
                                    }

                                    if (!found) {
                                        throw new RuntimeException("Splunk log not found or status not SUCCESS for correlationId: " + cid);
                                    }
                                }).language("java"),

                                resultsTreeVisualizer()
                        )
                ).run();

        assertThat(stats.overall().errorsCount()).isEqualTo(0);
    }

    private static void disableSslVerification() {
        try {
            TrustManager[] trustAllCerts = new TrustManager[]{
                    new X509TrustManager() {
                        public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                        public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                        public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) { }
                    }
            };

            SSLContext sc = SSLContext.getInstance("SSL");
            sc.init(null, trustAllCerts, new java.security.SecureRandom());
            HttpsURLConnection.setDefaultSSLSocketFactory(sc.getSocketFactory());

            HttpsURLConnection.setDefaultHostnameVerifier((hostname, session) -> true);
        } catch (Exception e) {
            throw new RuntimeException("Failed to disable SSL validation", e);
        }
    }

//    public static void main(String[] args) {
//        try {
//            PerformanceTest test = new PerformanceTest();
//            test.test();  // Run the test logic
//            System.exit(0);  //  Success
//        } catch (Exception e) {
//            e.printStackTrace();
//            System.exit(1);  //  Failure
//        }
//    }
}
