package ru.safeai.gateway.common.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequestIdFilterTest {

    private final RequestIdFilter filter =
            new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void serverAlwaysGeneratesOwnRequestId() throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        "client-123"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        String serverRequestId =
                requireServerRequestId(
                        request,
                        response
                );

        assertThat(serverRequestId)
                .isNotEqualTo(
                        "client-123"
                );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isEqualTo(
                "client-123"
        );
    }

    @Test
    void correlationUsesServerIdAndRestoresNestedWorkerScope() throws Exception {
        UUID forged = UUID.randomUUID();
        UUID prior = UUID.randomUUID();
        UUID worker = UUID.randomUUID();
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, prior.toString());
        MockHttpServletRequest request = requestWithId(forged.toString());
        request.addHeader("X-Correlation-Id", forged.toString());
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, (req, res) -> {
            UUID server = CorrelationContext.current();
            assertThat(server).isNotNull().isNotEqualTo(forged).isNotEqualTo(prior);
            assertThat(server.toString()).isEqualTo(response.getHeader("X-Request-Id"));
            assertThatThrownBy(() -> {
                try (var ignored = CorrelationContext.open(worker)) {
                    assertThat(CorrelationContext.current()).isEqualTo(worker);
                    assertThat(MDC.get(RequestIdFilter.CLIENT_REQUEST_ID_MDC_KEY)).isNull();
                    throw new IllegalStateException("worker failed");
                }
            }).isInstanceOf(IllegalStateException.class);
            assertThat(CorrelationContext.current()).isEqualTo(server);
            assertThat(MDC.get(RequestIdFilter.CLIENT_REQUEST_ID_MDC_KEY)).isEqualTo(forged.toString());
        });
        assertThat(CorrelationContext.current()).isEqualTo(prior);
    }

    @Test
    void independentWorkersDoNotInheritOrLeakCorrelation() throws Exception {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        try (var executor = Executors.newFixedThreadPool(2)) {
            CountDownLatch ready = new CountDownLatch(2);
            java.util.concurrent.Callable<UUID> firstJob = () -> workerCorrelation(first, ready);
            java.util.concurrent.Callable<UUID> secondJob = () -> workerCorrelation(second, ready);
            var a = executor.submit(firstJob);
            var b = executor.submit(secondJob);
            assertThat(a.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(b.get(5, TimeUnit.SECONDS)).isEqualTo(second);
        }
        assertThat(CorrelationContext.current()).isNull();
    }

    private UUID workerCorrelation(UUID id, CountDownLatch ready) throws InterruptedException {
        UUID observed;
        assertThat(CorrelationContext.current()).isNull();
        try (var ignored = CorrelationContext.open(id)) {
            ready.countDown();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            observed = CorrelationContext.current();
        }
        assertThat(CorrelationContext.current()).isNull();
        return observed;
    }

    @Test
    void invalidAndMissingMdcCannotBecomeTrustedCorrelation() {
        assertThat(CorrelationContext.current()).isNull();
        assertThat(CorrelationContext.currentOrNew()).isNotNull();
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "1-1-1-1-1");
        assertThat(CorrelationContext.current()).isNull();
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, "x".repeat(36));
        assertThat(CorrelationContext.current()).isNull();
        UUID original = UUID.fromString("aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa");
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, original.toString().toUpperCase(java.util.Locale.ROOT));
        assertThat(CorrelationContext.current()).isNull();
        MDC.put(RequestIdFilter.REQUEST_ID_MDC_KEY, original.toString());
        assertThat(CorrelationContext.currentOrNew()).isEqualTo(original);
        try (var ignored = CorrelationContext.open(null)) {
            assertThat(CorrelationContext.current()).isNull();
            assertThat(CorrelationContext.currentOrNew()).isNotEqualTo(original);
        }
        assertThat(CorrelationContext.current()).isEqualTo(original);
    }

    @Test
    void validClientRequestIdIsTrimmedAndPreservedOnlyAsMetadata()
            throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        "  client-123  "
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        String serverRequestId =
                requireServerRequestId(
                        request,
                        response
                );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isEqualTo(
                "client-123"
        );

        assertThat(serverRequestId)
                .isNotEqualTo(
                        "client-123"
                );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "client-123",
            "client_123",
            "client.123",
            "CLIENT123",
            "1234567890"
    })
    void allowedClientRequestIdCharactersAreAccepted(
            String clientRequestId
    ) throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        clientRequestId
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isEqualTo(
                clientRequestId
        );

        assertThat(
                response.getHeader(
                        RequestIdFilter
                                .REQUEST_ID_HEADER
                )
        ).isNotEqualTo(
                clientRequestId
        );
    }

    @Test
    void clientRequestIdWithExactly128CharactersIsAccepted()
            throws Exception {
        String clientRequestId =
                "a".repeat(128);

        MockHttpServletRequest request =
                requestWithId(
                        clientRequestId
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isEqualTo(
                clientRequestId
        );

        requireServerRequestId(
                request,
                response
        );
    }

    @Test
    void missingClientRequestIdStillGetsServerUuid()
            throws Exception {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        requireServerRequestId(
                request,
                response
        );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "",
            " ",
            "\t",
            "bad id",
            "bad!",
            "client/123",
            "client:123",
            "client@123"
    })
    void invalidIncomingRequestIdIsRejectedAsClientMetadata(
            String incomingId
    ) throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        incomingId
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isNull();

        String serverId =
                requireServerRequestId(
                        request,
                        response
                );

        assertThat(serverId)
                .isNotEqualTo(
                        incomingId.trim()
                );
    }

    @Test
    void clientRequestIdLongerThan128CharactersIsRejected()
            throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        "a".repeat(129)
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                request.getAttribute(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_ATTRIBUTE
                )
        ).isNull();

        requireServerRequestId(
                request,
                response
        );
    }

    @Test
    void requestAndClientIdsAreAvailableInMdcDuringFilterChain()
            throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        "client-123"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        AtomicBoolean chainInvoked =
                new AtomicBoolean(false);

        FilterChain chain =
                (servletRequest, servletResponse) -> {
                    chainInvoked.set(true);

                    String serverRequestId =
                            (String)
                                    servletRequest
                                            .getAttribute(
                                                    RequestIdFilter
                                                            .REQUEST_ID_ATTRIBUTE
                                            );

                    assertThat(
                            MDC.get(
                                    RequestIdFilter
                                            .REQUEST_ID_MDC_KEY
                            )
                    ).isEqualTo(
                            serverRequestId
                    );

                    assertThat(
                            MDC.get(
                                    RequestIdFilter
                                            .CLIENT_REQUEST_ID_MDC_KEY
                            )
                    ).isEqualTo(
                            "client-123"
                    );
                };

        filter.doFilter(
                request,
                response,
                chain
        );

        assertThat(
                chainInvoked
        ).isTrue();
    }

    @Test
    void mdcIsCleanedAfterRequest()
            throws Exception {
        MockHttpServletRequest request =
                requestWithId(
                        "client-123"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .REQUEST_ID_MDC_KEY
                )
        ).isNull();

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_MDC_KEY
                )
        ).isNull();
    }

    @Test
    void previousMdcValuesAreRestoredAfterRequest()
            throws Exception {
        MDC.put(
                RequestIdFilter
                        .REQUEST_ID_MDC_KEY,
                "outer-server-id"
        );

        MDC.put(
                RequestIdFilter
                        .CLIENT_REQUEST_ID_MDC_KEY,
                "outer-client-id"
        );

        MockHttpServletRequest request =
                requestWithId(
                        "new-client-id"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        execute(
                request,
                response
        );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .REQUEST_ID_MDC_KEY
                )
        ).isEqualTo(
                "outer-server-id"
        );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_MDC_KEY
                )
        ).isEqualTo(
                "outer-client-id"
        );
    }

    @Test
    void previousMdcValuesAreRestoredWhenFilterChainThrows() {
        MDC.put(
                RequestIdFilter
                        .REQUEST_ID_MDC_KEY,
                "outer-server-id"
        );

        MDC.put(
                RequestIdFilter
                        .CLIENT_REQUEST_ID_MDC_KEY,
                "outer-client-id"
        );

        MockHttpServletRequest request =
                requestWithId(
                        "client-123"
                );

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        FilterChain failingChain =
                (servletRequest, servletResponse) -> {
                    assertThat(
                            MDC.get(
                                    RequestIdFilter
                                            .CLIENT_REQUEST_ID_MDC_KEY
                            )
                    ).isEqualTo(
                            "client-123"
                    );

                    throw new ServletException(
                            "chain failure"
                    );
                };

        assertThatThrownBy(() ->
                filter.doFilter(
                        request,
                        response,
                        failingChain
                )
        )
                .isInstanceOf(
                        ServletException.class
                )
                .hasMessage(
                        "chain failure"
                );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .REQUEST_ID_MDC_KEY
                )
        ).isEqualTo(
                "outer-server-id"
        );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_MDC_KEY
                )
        ).isEqualTo(
                "outer-client-id"
        );
    }

    @Test
    void sequentialRequestsAlwaysReceiveDifferentServerIds()
            throws Exception {
        MockHttpServletRequest firstRequest =
                requestWithId(
                        "same-client-id"
                );

        MockHttpServletResponse firstResponse =
                new MockHttpServletResponse();

        execute(
                firstRequest,
                firstResponse
        );

        MockHttpServletRequest secondRequest =
                requestWithId(
                        "same-client-id"
                );

        MockHttpServletResponse secondResponse =
                new MockHttpServletResponse();

        execute(
                secondRequest,
                secondResponse
        );

        assertThat(
                firstResponse.getHeader(
                        RequestIdFilter
                                .REQUEST_ID_HEADER
                )
        ).isNotEqualTo(
                secondResponse.getHeader(
                        RequestIdFilter
                                .REQUEST_ID_HEADER
                )
        );
    }

    @Test
    void concurrentRequestsDoNotMixMdc()
            throws Exception {
        CountDownLatch bothInsideChain =
                new CountDownLatch(2);

        CountDownLatch release =
                new CountDownLatch(1);

        try (ExecutorService executor =
                     Executors.newFixedThreadPool(2)) {

            Future<RequestContextSnapshot> first =
                    executor.submit(() ->
                            executeConcurrentRequest(
                                    "client-one",
                                    bothInsideChain,
                                    release
                            )
                    );

            Future<RequestContextSnapshot> second =
                    executor.submit(() ->
                            executeConcurrentRequest(
                                    "client-two",
                                    bothInsideChain,
                                    release
                            )
                    );

            try {
                assertThat(
                        bothInsideChain.await(
                                5L,
                                TimeUnit.SECONDS
                        )
                ).isTrue();
            } finally {
                release.countDown();
            }

            RequestContextSnapshot firstResult =
                    first.get(
                            5L,
                            TimeUnit.SECONDS
                    );

            RequestContextSnapshot secondResult =
                    second.get(
                            5L,
                            TimeUnit.SECONDS
                    );

            assertThat(
                    firstResult.clientRequestId()
            ).isEqualTo(
                    "client-one"
            );

            assertThat(
                    secondResult.clientRequestId()
            ).isEqualTo(
                    "client-two"
            );

            assertThat(
                    firstResult.serverRequestId()
            ).isNotEqualTo(
                    secondResult.serverRequestId()
            );

            assertThat(
                    UUID.fromString(
                            firstResult
                                    .serverRequestId()
                    )
            ).isNotNull();

            assertThat(
                    UUID.fromString(
                            secondResult
                                    .serverRequestId()
                    )
            ).isNotNull();
        }
    }

    private RequestContextSnapshot
    executeConcurrentRequest(
            String clientId,
            CountDownLatch bothInsideChain,
            CountDownLatch release
    ) throws Exception {
        MockHttpServletRequest request =
                requestWithId(clientId);

        MockHttpServletResponse response =
                new MockHttpServletResponse();

        AtomicReference<RequestContextSnapshot>
                snapshot =
                new AtomicReference<>();

        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    String requestId =
                            MDC.get(
                                    RequestIdFilter
                                            .REQUEST_ID_MDC_KEY
                            );

                    String clientRequestId =
                            MDC.get(
                                    RequestIdFilter
                                            .CLIENT_REQUEST_ID_MDC_KEY
                            );

                    snapshot.set(
                            new RequestContextSnapshot(
                                    requestId,
                                    clientRequestId
                            )
                    );

                    bothInsideChain.countDown();

                    try {
                        if (!release.await(
                                5L,
                                TimeUnit.SECONDS
                        )) {
                            throw new ServletException(
                                    "Concurrent test timeout"
                            );
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread()
                                .interrupt();

                        throw new ServletException(
                                exception
                        );
                    }

                    assertThat(
                            MDC.get(
                                    RequestIdFilter
                                            .REQUEST_ID_MDC_KEY
                            )
                    ).isEqualTo(
                            requestId
                    );

                    assertThat(
                            MDC.get(
                                    RequestIdFilter
                                            .CLIENT_REQUEST_ID_MDC_KEY
                            )
                    ).isEqualTo(
                            clientRequestId
                    );
                }
        );

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .REQUEST_ID_MDC_KEY
                )
        ).isNull();

        assertThat(
                MDC.get(
                        RequestIdFilter
                                .CLIENT_REQUEST_ID_MDC_KEY
                )
        ).isNull();

        return snapshot.get();
    }

    private void execute(
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) throws ServletException, IOException {
        filter.doFilter(
                request,
                response,
                (servletRequest, servletResponse) -> {
                    // success
                }
        );
    }

    private MockHttpServletRequest requestWithId(
            String requestId
    ) {
        MockHttpServletRequest request =
                new MockHttpServletRequest();

        request.addHeader(
                RequestIdFilter
                        .REQUEST_ID_HEADER,
                requestId
        );

        return request;
    }

    private String requireServerRequestId(
            MockHttpServletRequest request,
            MockHttpServletResponse response
    ) {
        Object attribute =
                request.getAttribute(
                        RequestIdFilter
                                .REQUEST_ID_ATTRIBUTE
                );

        assertThat(attribute)
                .isInstanceOf(
                        String.class
                );

        String requestId =
                (String) attribute;

        assertThat(requestId)
                .isNotBlank();

        assertThat(
                response.getHeader(
                        RequestIdFilter
                                .REQUEST_ID_HEADER
                )
        ).isEqualTo(
                requestId
        );

        assertThat(
                UUID.fromString(
                        requestId
                )
        ).isNotNull();

        return requestId;
    }

    private record RequestContextSnapshot(
            String serverRequestId,
            String clientRequestId
    ) {
    }
}
