package com.soulvoyage.common.api;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/** O4：/v3/api-docs 是前端 gen:api 的契约真源（backend/openapi/openapi.json 快照由测试钉住） */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI soulVoyageOpenApi() {
        return new OpenAPI()
                .info(new Info().title("SoulVoyage API").version("v1")
                        .description("心屿漫行——多 Agent 心理成长平台后端接口契约"))
                .servers(List.of(new Server().url("/")))
                .components(new Components().addSecuritySchemes("bearerAuth",
                        new SecurityScheme().type(SecurityScheme.Type.HTTP)
                                .scheme("bearer").bearerFormat("JWT")))
                .addSecurityItem(new SecurityRequirement().addList("bearerAuth"));
    }
}
