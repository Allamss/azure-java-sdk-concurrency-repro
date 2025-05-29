# azure-java-sdk-concurrency-repro
Minimal reproduction repository for 'stream was reset: REFUSED_STREAM' errors when making concurrent requests to Azure AI OpenAI (azure-ai-openai).

## Environment
- Java 21.0.7 https://adoptium.net/zh-CN/temurin/releases/?os=any&arch=any&version=21
- Maven 3.9.9

## Reproduction Steps
1. Clone this repository.
2. set the azure endpoint/key/deploymentName in AzureOpenAIConcurrencyTest.java
3. run main()

## Behavior
UncheckedIOException: okhttp3.internal.http2.StreamResetException: stream was reset: REFUSED_STREAM
INFO com.azure.core.http.policy.RetryPolicy - {"az.sdk.message":"Retry attempts have been exhausted.","tryCount":3}
=== TEST RESULTS STATISTICS ===
TOTAL NUMBER OF REQUESTS: 5000
NUMBER OF SUCCESSFUL REQUESTS: 3556
NUMBER OF FAILED REQUESTS: 1444
NUMBER OF CONNECTION RESET ERRORS: 0
TOTAL TIME CONSUMPTION: 44.011 秒
AVERAGE QPS: 113.61
SUCCESS RATE: 71.12%
CONNECTION RESET RATE: 0.00%
TEST END TIME: 2025-05-29 11:58:50
========================================
