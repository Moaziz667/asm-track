package com.asm.driver.dto.response;
import lombok.Builder;
import lombok.Data;
@Data @Builder
public class DriverInfo {
    private String id;
    private String name;
    private String phone;
    private Boolean available;
}
