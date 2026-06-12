package com.traceability;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.testcontainers.dockerclient.DockerClientProviderStrategy;
import org.testcontainers.dockerclient.InvalidConfigurationException;
import org.testcontainers.dockerclient.TransportConfig;

import java.net.URI;
import java.time.Duration;

/**
 * Custom Testcontainers strategy for Docker Desktop on Mac (M3).
 *
 * Docker Desktop rejects docker-java's default v1.24 version negotiation
 * (used in getClientForConfig) with HTTP 400. This strategy:
 *  1. Overrides test() to use API v1.41 directly — bypassing the negotiation
 *  2. Overrides getClient() so containers also use the same client
 *
 * Required because getClientForConfig() (called by the base test()) creates
 * a new client without version config, whereas getClient() caches ours.
 */
public class DockerDesktopMacStrategy extends DockerClientProviderStrategy {

    private static final URI SOCKET = URI.create("unix:///var/run/docker.sock");
    private static final String API_VERSION = "1.41";

    private DockerClient buildClient() {
        ZerodepDockerHttpClient http = new ZerodepDockerHttpClient.Builder()
                .dockerHost(SOCKET)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(15))
                .build();

        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(SOCKET.toString())
                .withApiVersion(API_VERSION)
                .build();

        return DockerClientImpl.getInstance(config, http);
    }

    @Override
    protected boolean test() {
        System.err.println(">>> DockerDesktopMacStrategy.test() CALLED <<<");
        try {
            DockerClient client = buildClient();
            System.err.println(">>> client built, calling infoCmd <<<");
            com.github.dockerjava.api.model.Info info = client.infoCmd().exec();
            System.err.println(">>> infoCmd succeeded, server=" + info.getServerVersion() + " <<<");
            return true;
        } catch (Exception e) {
            System.err.println(">>> infoCmd FAILED: " + e.getMessage() + " <<<");
            throw new InvalidConfigurationException(
                    "DockerDesktopMacStrategy: " + e.getMessage(), e);
        }
    }

    @Override
    public DockerClient getClient() {
        return buildClient();
    }

    @Override
    public DockerClient getDockerClient() {
        return buildClient();
    }

    @Override
    public TransportConfig getTransportConfig() throws InvalidConfigurationException {
        return TransportConfig.builder()
                .dockerHost(SOCKET)
                .sslConfig(null)
                .build();
    }

    @Override
    public String getDescription() {
        return "DockerDesktopMacStrategy (API v" + API_VERSION + ", " + SOCKET + ")";
    }

    @Override
    protected boolean isPersistable() {
        return true;
    }

    @Override
    protected int getPriority() {
        return 200;
    }
}
