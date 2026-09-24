package com.bbb.exercise.agentdemo1_0.auth;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ApiKeyFlowContractTest {

    @Test
    void pageProvidesAKeyInputAndPersistsItThroughTheSettingsEndpoint() throws Exception {
        String html = Files.readString(Path.of("src/main/resources/static/index.html"), StandardCharsets.UTF_8);
        String javascript = Files.readString(Path.of("src/main/resources/static/app.js"), StandardCharsets.UTF_8);

        assertThat(html).contains("id=\"qwen-api-key\"");
        assertThat(javascript).contains("/api/settings/keys");
        assertThat(javascript).contains("qwenApiKey");
        assertThat(javascript).contains("credentials: 'same-origin'");
    }

    @Test
    void globalOpenAiAutoConfigurationIsDisabledBecauseKeysAreUserScoped() throws Exception {
        String yaml = Files.readString(Path.of("src/main/resources/application.yml"), StandardCharsets.UTF_8);

        assertThat(yaml).contains("org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration");
        assertThat(yaml).contains("org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration");
    }
}
