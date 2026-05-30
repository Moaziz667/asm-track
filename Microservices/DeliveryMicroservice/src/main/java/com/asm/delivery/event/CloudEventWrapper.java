package com.asm.delivery.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CloudEventWrapper<T> {
    @Builder.Default
    private String specversion = "1.0";
    
    @Builder.Default
    private String id = UUID.randomUUID().toString();
    
    private String source;
    
    private String type;
    
    @Builder.Default
    private String time = ZonedDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT);
    
    private T data;
}
