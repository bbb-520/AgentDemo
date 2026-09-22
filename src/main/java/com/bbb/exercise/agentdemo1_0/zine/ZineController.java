package com.bbb.exercise.agentdemo1_0.zine;

import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FilePart;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import reactor.core.publisher.Mono;

/** Upload-and-generate API for the image editing app. */
@RestController
@RequestMapping("/api/zine")
@RequiredArgsConstructor
public class ZineController {

    private final ZineGenerationService generationService;

    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<ZineGenerationService.ZineGenerationResponse>> generate(
            @RequestPart("image") FilePart image,
            @RequestPart(name = "mode", required = false) String mode,
            @RequestPart(name = "language", required = false) String language,
            @RequestPart(name = "text", required = false) String text,
            @RequestPart(name = "guidance", required = false) String guidance) {
        String contentType = image.headers().getContentType() == null
                ? null : image.headers().getContentType().toString();

        return DataBufferUtils.join(image.content())
                .map(this::toBytes)
                .flatMap(bytes -> generationService.generate(bytes, contentType, mode, language, text, guidance))
                .map(ResponseEntity::ok);
    }

    private byte[] toBytes(DataBuffer dataBuffer) {
        try {
            byte[] bytes = new byte[dataBuffer.readableByteCount()];
            dataBuffer.read(bytes);
            return bytes;
        } finally {
            DataBufferUtils.release(dataBuffer);
        }
    }
}
