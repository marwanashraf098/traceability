package com.traceability;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.model.Info;
import com.github.dockerjava.core.DefaultDockerClientConfig;
import com.github.dockerjava.core.DockerClientImpl;
import com.github.dockerjava.zerodep.ZerodepDockerHttpClient;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** Standalone test - no Testcontainers, just raw docker-java to the socket. */
class DockerSocketTest {

    @Test
    void dockerJavaCanConnectWithApiVersion141() {
        URI socket = URI.create("unix:///var/run/docker.sock");

        ZerodepDockerHttpClient httpClient = new ZerodepDockerHttpClient.Builder()
                .dockerHost(socket)
                .connectionTimeout(Duration.ofSeconds(5))
                .responseTimeout(Duration.ofSeconds(10))
                .build();

        DefaultDockerClientConfig config = DefaultDockerClientConfig
                .createDefaultConfigBuilder()
                .withDockerHost(socket.toString())
                .withApiVersion("1.41")
                .build();

        System.out.println("Configured API version: " + config.getApiVersion());

        DockerClient client = DockerClientImpl.getInstance(config, httpClient);
        Info info = client.infoCmd().exec();

        System.out.println("Docker server version: " + info.getServerVersion());
        assertThat(info.getServerVersion())
                .as("Should get real Docker server version")
                .isNotEmpty();
    }
}
