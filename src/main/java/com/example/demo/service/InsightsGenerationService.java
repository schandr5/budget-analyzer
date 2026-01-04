package com.example.demo.service;

import com.example.demo.constants.Constants;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import java.time.Duration;

@Service
@Slf4j
public class InsightsGenerationService {
    private final ResourceLoader resourceLoader;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String deepseekModel;


        @Autowired
        public InsightsGenerationService(
            ResourceLoader resourceLoader,
            ObjectMapper objectMapper,
            @Value("${ollama.deepseek.model:deepseek-coder:6.7b-instruct}") String deepseekModel
        ) {
                    this.resourceLoader = resourceLoader;
                    this.objectMapper = objectMapper;
                    this.deepseekModel = deepseekModel;

                    SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
                    requestFactory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    requestFactory.setReadTimeout((int) Duration.ofSeconds(60).toMillis());
                    this.restTemplate = new RestTemplate(requestFactory);
        }


}