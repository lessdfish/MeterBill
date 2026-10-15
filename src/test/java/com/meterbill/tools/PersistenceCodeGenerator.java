
package com.meterbill.tools;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.generator.FastAutoGenerator;
import com.baomidou.mybatisplus.generator.config.OutputFile;
import com.baomidou.mybatisplus.generator.engine.FreemarkerTemplateEngine;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

public class PersistenceCodeGenerator {

    public static void main(String[] args) throws Exception {
        // 默认生成 M02 产品模块
        String module = args.length > 0 ? args[0] : "product";
        String[] tables = args.length > 1
                ? Arrays.copyOfRange(args, 1, args.length)
                : new String[]{"mb_product"};

        if (!module.matches("[a-z][a-z0-9]*")
                || Arrays.stream(tables)
                .anyMatch(t -> !t.matches("mb_[a-z0-9_]+"))) {
            throw new IllegalArgumentException("Invalid module or table");
        }

        Path root = Path.of("").toAbsolutePath();
        Path output = root.resolve("target/codegen").resolve(module);

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

        FastAutoGenerator.create(url, username, password)
                .globalConfig(builder -> builder
                        .author("MeterBill")
                        .disableOpenDir()
                        .outputDir(output.resolve("java").toString()))
                .packageConfig(builder -> builder
                        .parent("com.meterbill." + module + ".persistence")
                        .entity("entity")
                        .mapper("mapper")
                        .pathInfo(Map.of(
                                OutputFile.xml,
                                output.resolve("resources/mapper/" + module)
                                        .toString()
                        )))
                .strategyConfig(builder -> builder
                        .addInclude(tables)
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
