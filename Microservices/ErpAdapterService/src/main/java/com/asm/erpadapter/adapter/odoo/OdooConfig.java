package com.asm.erpadapter.adapter.odoo;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Odoo connection configuration.
 * Values come from environment variables or application.yml defaults.
 */
@Configuration
@Getter
public class OdooConfig {

    @Value("${odoo.url:http://host.docker.internal:8069/jsonrpc}")
    private String url;

    @Value("${odoo.db:odoo}")
    private String db;

    @Value("${odoo.uid:2}")
    private int uid;

    @Value("${odoo.password:admin}")
    private String password;
}
