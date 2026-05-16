package com.asm.auth.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

@Configuration
public class DataSourceConfig {

    @Bean("appDataSource")
    public DataSource appDataSource(
            @Value("${app-datasource.url}") String url,
            @Value("${app-datasource.username}") String username,
            @Value("${app-datasource.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean("driverDataSource")
    public DataSource driverDataSource(
            @Value("${driver-datasource.url}") String url,
            @Value("${driver-datasource.username}") String username,
            @Value("${driver-datasource.password}") String password) {
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setUrl(url);
        ds.setUsername(username);
        ds.setPassword(password);
        return ds;
    }

    @Bean("appJdbc")
    public JdbcTemplate appJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("appDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }

    @Bean("driverJdbc")
    public JdbcTemplate driverJdbcTemplate(@org.springframework.beans.factory.annotation.Qualifier("driverDataSource") DataSource ds) {
        return new JdbcTemplate(ds);
    }
}
