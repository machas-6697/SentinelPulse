package com.sentinelpulse.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.handler.timeout.WriteTimeoutHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * WebClient configuration for SentinelPulse.
 *
 * Two WebClient instances are exposed:
 *
 *   1. proxyWebClient  — Used by ProxyController for transparent HTTP forwarding.
 *                        Shorter timeouts to respect client-facing SLAs.
 *
 *   2. webhookWebClient — Used by WebhookDispatchService for outbound POST delivery.
 *                         Slightly longer timeout to tolerate slow subscriber endpoints.
 *
 * Both clients are backed by Reactor Netty with explicit connect/read/write timeouts
 * to prevent threads from hanging indefinitely on unresponsive targets.
 */
@Configuration
public class WebClientConfig {

    /**
     * WebClient for transparent proxy forwarding.
     * Connect: 3s | Read: 10s | Write: 10s
     */
    @Bean(name = "proxyWebClient")
    public WebClient proxyWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
                .responseTimeout(Duration.ofSeconds(10))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(10, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }

    /**
     * WebClient for webhook dispatch to subscriber endpoints.
     * Connect: 5s | Read: 15s | Write: 15s — tolerates slower subscriber servers.
     */
    @Bean(name = "webhookWebClient")
    public WebClient webhookWebClient() {
        HttpClient httpClient = HttpClient.create()
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .responseTimeout(Duration.ofSeconds(15))
                .doOnConnected(conn -> conn
                        .addHandlerLast(new ReadTimeoutHandler(15, TimeUnit.SECONDS))
                        .addHandlerLast(new WriteTimeoutHandler(15, TimeUnit.SECONDS)));

        return WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                .build();
    }
}
