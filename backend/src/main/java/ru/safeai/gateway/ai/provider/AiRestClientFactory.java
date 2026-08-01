package ru.safeai.gateway.ai.provider;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpRequest;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Objects;

@NullMarked
public final class AiRestClientFactory {

    private static final long DEFAULT_MAX_RESPONSE_BODY_BYTES =
            2L * 1024L * 1024L;

    private AiRestClientFactory() {
    }

    public static RestClient create(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        return create(
                baseUrl,
                connectTimeout,
                readTimeout,
                DEFAULT_MAX_RESPONSE_BODY_BYTES
        );
    }

    public static RestClient create(
            String baseUrl,
            Duration connectTimeout,
            Duration readTimeout,
            long maxResponseBodyBytes
    ) {
        String validatedBaseUrl =
                Objects.requireNonNull(
                        baseUrl,
                        "baseUrl не должен быть null"
                );

        Duration validatedConnectTimeout =
                requirePositiveDuration(
                        connectTimeout,
                        "connectTimeout"
                );

        Duration validatedReadTimeout =
                requirePositiveDuration(
                        readTimeout,
                        "readTimeout"
                );

        if (maxResponseBodyBytes <= 0) {
            throw new IllegalArgumentException(
                    "maxResponseBodyBytes должен быть положительным"
            );
        }

        HttpClient httpClient =
                HttpClient.newBuilder()
                        .connectTimeout(validatedConnectTimeout)
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .version(HttpClient.Version.HTTP_2)
                        .build();

        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);

        requestFactory.setReadTimeout(
                validatedReadTimeout
        );

        return RestClient.builder()
                .baseUrl(validatedBaseUrl)
                .requestFactory(requestFactory)
                .requestInterceptor(
                        new ResponseSizeLimitInterceptor(
                                maxResponseBodyBytes
                        )
                )
                .build();
    }

    private static Duration requirePositiveDuration(
            Duration duration,
            String propertyName
    ) {
        Duration validatedDuration =
                Objects.requireNonNull(
                        duration,
                        propertyName + " не должен быть null"
                );

        if (validatedDuration.isZero()
                || validatedDuration.isNegative()) {
            throw new IllegalArgumentException(
                    propertyName + " должен быть положительным"
            );
        }

        return validatedDuration;
    }

    private record ResponseSizeLimitInterceptor(
            long maxBytes
    ) implements ClientHttpRequestInterceptor {

        private ResponseSizeLimitInterceptor {
            if (maxBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxBytes должен быть положительным"
                );
            }
        }

        @Override
        public ClientHttpResponse intercept(
                HttpRequest request,
                byte[] body,
                ClientHttpRequestExecution execution
        ) throws IOException {
            Objects.requireNonNull(
                    request,
                    "request не должен быть null"
            );

            Objects.requireNonNull(
                    body,
                    "body не должен быть null"
            );

            Objects.requireNonNull(
                    execution,
                    "execution не должен быть null"
            );

            ClientHttpResponse response =
                    execution.execute(
                            request,
                            body
                    );

            return new LimitedClientHttpResponse(
                    response,
                    maxBytes
            );
        }
    }

    private static final class LimitedClientHttpResponse
            implements ClientHttpResponse {

        private final ClientHttpResponse delegate;
        private final long maxBytes;

        private @Nullable InputStream limitedBody;

        private LimitedClientHttpResponse(
                ClientHttpResponse delegate,
                long maxBytes
        ) {
            this.delegate =
                    Objects.requireNonNull(
                            delegate,
                            "delegate не должен быть null"
                    );

            if (maxBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxBytes должен быть положительным"
                );
            }

            this.maxBytes = maxBytes;
        }

        @Override
        public HttpStatusCode getStatusCode()
                throws IOException {
            return delegate.getStatusCode();
        }

        @Override
        public String getStatusText()
                throws IOException {
            return delegate.getStatusText();
        }

        @Override
        public HttpHeaders getHeaders() {
            return delegate.getHeaders();
        }

        @Override
        public synchronized InputStream getBody()
                throws IOException {
            validateDeclaredContentLength();

            InputStream currentBody =
                    limitedBody;

            if (currentBody == null) {
                currentBody =
                        new LimitedInputStream(
                                delegate.getBody(),
                                maxBytes
                        );

                limitedBody =
                        currentBody;
            }

            return currentBody;
        }

        @Override
        public void close() {
            delegate.close();
        }

        private void validateDeclaredContentLength()
                throws IOException {
            long contentLength =
                    delegate.getHeaders()
                            .getContentLength();

            if (contentLength > maxBytes) {
                throw new AiResponseTooLargeIOException(
                        maxBytes
                );
            }
        }
    }

    private static final class LimitedInputStream
            extends FilterInputStream {

        private final long maxBytes;

        private long consumedBytes;

        private LimitedInputStream(
                InputStream delegate,
                long maxBytes
        ) {
            super(
                    Objects.requireNonNull(
                            delegate,
                            "delegate не должен быть null"
                    )
            );

            if (maxBytes <= 0) {
                throw new IllegalArgumentException(
                        "maxBytes должен быть положительным"
                );
            }

            this.maxBytes = maxBytes;
        }

        @Override
        public int read()
                throws IOException {
            int value =
                    super.read();

            if (value >= 0) {
                registerReadBytes(1);
            }

            return value;
        }

        @Override
        public int read(
                byte[] buffer,
                int offset,
                int length
        ) throws IOException {
            Objects.requireNonNull(
                    buffer,
                    "buffer не должен быть null"
            );

            int readBytes =
                    super.read(
                            buffer,
                            offset,
                            length
                    );

            if (readBytes > 0) {
                registerReadBytes(
                        readBytes
                );
            }

            return readBytes;
        }

        private void registerReadBytes(
                long amount
        ) throws IOException {
            if (amount <= 0) {
                return;
            }

            if (amount > maxBytes
                    || consumedBytes > maxBytes - amount) {
                throw new AiResponseTooLargeIOException(
                        maxBytes
                );
            }

            consumedBytes += amount;
        }
    }
}