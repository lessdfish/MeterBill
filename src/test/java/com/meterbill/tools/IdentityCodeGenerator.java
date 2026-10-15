
package com.meterbill.tools;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public class IdentityCodeGenerator {

    public static void main(String[] args) throws Exception {
        Path root = Path.of("").toAbsolutePath();
        Properties env = new Properties();

        try (Reader reader = Files.newBufferedReader(
                root.resolve(".env"), StandardCharsets.UTF_8)) {
            env.load(reader);
        }

        String host = env.getProperty("MYSQL_HOST", "127.0.0.1");
        String port = env.getProperty("MYSQL_HOST_PORT", "13306");
        String database = env.getProperty("MYSQL_DATABASE", "meterbill");
        String username = env.getProperty("MYSQL_USER", "meterbill");
        String password = env.getProperty("MYSQL_PASSWORD");

        if (password == null || password.isBlank()) {
            throw new IllegalStateException("MYSQL_PASSWORD is missing");
        }

        String url = "jdbc:mysql://" + host + ":" + port + "/"
                + database + "?connectionTimeZone=UTC";

        Path output = root.resolve("target/codegen");

        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> builder
                        .author("MeterBill")
                        .disableOpenDir()
                        .outputDir(output.resolve("java").toString()))
                .packageConfig(builder -> builder
                        .parent("com.meterbill.identity.persistence")
                        .entity("entity")
                        .mapper("mapper")
                        .pathInfo(Map.of(
                                OutputFile.xml,
                                output.resolve("resources/mapper/identity")
                                        .toString()
                        )))
                .strategyConfig(builder -> builder
                        .addInclude(
                                "mb_tenant",
                                "mb_user",
                                "mb_tenant_member"
                        )
                        .addTablePrefix("mb_")
                        .entityBuilder()
                        .enableLombok()
                        .enableTableFieldAnnotation()
                        .idType(IdType.AUTO)
                        .mapperBuilder()
                        .enableMapperAnnotation())
                .templateEngine(new FreemarkerTemplateEngine())
                .execute();

        System.out.println("Generated code: " + output);
    }
}
