import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.okhttp.OkHttpAsyncHttpClientBuilder;
import com.azure.core.util.ConfigurationBuilder;
import okhttp3.ConnectionPool;
import okhttp3.Dispatcher;
import okhttp3.OkHttpClient;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.azure.core.util.Configuration.*;

/**
 * Azure OpenAI High Concurrency Call Reproduction Test Tool
 * 
 * @author Allamss
 */
public class AzureOpenAIConcurrencyTest {

    // ---- ! Please set your environment variables before running the sample. ! ----
    public static final String ENDPOINT = null;
    public static final String API_KEY = null;
    public static final String DEPLOYMENT_NAME = null;

    private static final int CONCURRENT_REQUESTS = 5000;
    private static final int THREAD_POOL_SIZE = 2000;
    private static final int CONNECTION_POOL_SIZE = 3000;
    private static final int TIMEOUT_SECONDS = 500;

    private static final AtomicInteger successCount = new AtomicInteger(0);
    private static final AtomicInteger failureCount = new AtomicInteger(0);
    private static final AtomicInteger connectionResetCount = new AtomicInteger(0);
    
    public static void main(String[] args) {
        if (ENDPOINT == null || API_KEY == null || DEPLOYMENT_NAME == null) {
            System.err.println("Please set your environment variables before running the sample:");
            System.err.println("AZURE_OPENAI_ENDPOINT=https://your-resource.openai.azure.com/");
            System.err.println("AZURE_OPENAI_KEY=your-api-key");
            System.exit(1);
        }
        
        System.out.println("=== Azure OpenAI High Concurrency Call Testing ===");
        System.out.println("NUMBER OF CONCURRENT REQUESTS: " + CONCURRENT_REQUESTS);
        System.out.println("THREAD POOL SIZE: " + THREAD_POOL_SIZE);
        System.out.println("CONNECTION POOL SIZE: " + CONNECTION_POOL_SIZE);
        System.out.println("TIMEOUT: " + TIMEOUT_SECONDS + "s");
        System.out.println("TEST START TIME: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        System.out.println("========================================");

        OpenAIClient client = createAzureOpenAIClient(ENDPOINT, API_KEY);

        ExecutorService executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE);

        long startTime = System.currentTimeMillis();

        CompletableFuture[] futures = new CompletableFuture[CONCURRENT_REQUESTS];
        for (int i = 0; i < CONCURRENT_REQUESTS; i++) {
            final int requestId = i + 1;
            futures[i] = CompletableFuture.runAsync(() -> {
                makeOpenAICall(client, requestId, DEPLOYMENT_NAME);
            }, executor);
        }

        try {
            CompletableFuture.allOf(futures).get(10, TimeUnit.MINUTES);
        } catch (Exception e) {
            System.err.println("An exception occurred while waiting for the task to complete: " + e.getMessage());
        }

        long endTime = System.currentTimeMillis();
        long totalTime = endTime - startTime;

        printResults(totalTime);

        executor.shutdown();
        try {
            if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    private static OpenAIClient createAzureOpenAIClient(String endpoint, String apiKey) {
        ConnectionPool connectionPool = new ConnectionPool(CONNECTION_POOL_SIZE, 2000, TimeUnit.SECONDS);

        OkHttpClient httpClient = new OkHttpClient.Builder()
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .connectTimeout(Duration.ofSeconds(30))
            .dispatcher(new Dispatcher())
            .connectionPool(connectionPool)
            .build();

        var configuration = new ConfigurationBuilder()
            .putProperty(PROPERTY_AZURE_REQUEST_RESPONSE_TIMEOUT, String.valueOf(TIMEOUT_SECONDS * 1000))
            .putProperty(PROPERTY_AZURE_REQUEST_READ_TIMEOUT, String.valueOf(TIMEOUT_SECONDS * 1000))
            .putProperty(PROPERTY_AZURE_REQUEST_WRITE_TIMEOUT, String.valueOf(TIMEOUT_SECONDS * 1000))
            .putProperty(PROPERTY_AZURE_REQUEST_CONNECT_TIMEOUT, String.valueOf(30 * 1000))
            .build();

        var azureHttpClient = new OkHttpAsyncHttpClientBuilder(httpClient).build();

        return new OpenAIClientBuilder()
            .credential(new AzureKeyCredential(apiKey))
            .endpoint(endpoint)
            .httpClient(azureHttpClient)
            .configuration(configuration)
            .buildClient();
    }

    private static void makeOpenAICall(OpenAIClient client, int requestId, String deploymentName) {
        try {
            List<ChatRequestMessage> messages = List.of(
                new ChatRequestUserMessage("Hello, this is test request #" + requestId + ". Please respond with a short message.")
            );
            
            ChatCompletionsOptions options = new ChatCompletionsOptions(messages);
            options.setTemperature(0D);
            options.setN(1);
            options.setMaxTokens(50); // limit response length to speed up testing

            var chatCompletions = client.getChatCompletions(deploymentName, options);

            if (chatCompletions != null && 
                chatCompletions.getChoices() != null && 
                !chatCompletions.getChoices().isEmpty()) {
                
                successCount.incrementAndGet();
                if (requestId % 100 == 0) {
                    System.out.println("Request #" + requestId + " success");
                }
            } else {
                failureCount.incrementAndGet();
                System.err.println("Request #" + requestId + " response empty");
            }
            
        } catch (HttpResponseException e) {
            failureCount.incrementAndGet();
            String errorMessage = e.getMessage();

            if (errorMessage != null && 
                (errorMessage.toLowerCase().contains("connection reset") ||
                 errorMessage.toLowerCase().contains("connection was reset") ||
                 errorMessage.toLowerCase().contains("connection closed") ||
                 errorMessage.toLowerCase().contains("unexpected end of stream"))) {
                
                connectionResetCount.incrementAndGet();
                System.err.println("Request #" + requestId + " CONNECTION RESET OCCURRED: " + errorMessage);
                e.printStackTrace();
            } else {
                System.err.println("Request #" + requestId + " http exception: " + errorMessage);
                e.printStackTrace();
            }
            
        } catch (Exception e) {
            failureCount.incrementAndGet();
            System.err.println("Request #" + requestId + " other exceptions: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }
    
    /**
     * 打印测试结果
     */
    private static void printResults(long totalTimeMs) {
        System.out.println("\n=== TEST RESULTS STATISTICS ===");
        System.out.println("TOTAL NUMBER OF REQUESTS: " + CONCURRENT_REQUESTS);
        System.out.println("NUMBER OF SUCCESSFUL REQUESTS: " + successCount.get());
        System.out.println("NUMBER OF FAILED REQUESTS: " + failureCount.get());
        System.out.println("NUMBER OF CONNECTION RESET ERRORS: " + connectionResetCount.get());
        System.out.println("TOTAL TIME CONSUMPTION: " + (totalTimeMs / 1000.0) + " 秒");
        System.out.println("AVERAGE QPS: " + String.format("%.2f", (CONCURRENT_REQUESTS * 1000.0) / totalTimeMs));
        System.out.println("SUCCESS RATE: " + String.format("%.2f%%", (successCount.get() * 100.0) / CONCURRENT_REQUESTS));
        System.out.println("CONNECTION RESET RATE: " + String.format("%.2f%%", (connectionResetCount.get() * 100.0) / CONCURRENT_REQUESTS));
        System.out.println("TEST END TIME: " + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
        
        if (connectionResetCount.get() > 0) {
            System.out.println("\n⚠️  Detected " + connectionResetCount.get() + " connection reset error！");
            System.out.println("This may be the cause of the problem you are having。");
        }
        
        System.out.println("========================================");
    }
} 