package com.bbb.exercise.agentdemo.mediaservice;

import com.bbb.exercise.agentdemo.api.AuthInternalApi;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.client.discovery.EnableDiscoveryClient;
import org.springframework.cloud.openfeign.EnableFeignClients;

@SpringBootApplication
@EnableDiscoveryClient
@EnableFeignClients(clients = AuthInternalApi.class)
public class MediaServiceApplication {
    public static void main(String[] args) { SpringApplication.run(MediaServiceApplication.class, args); }
}
