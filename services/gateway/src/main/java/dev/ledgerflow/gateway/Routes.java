package dev.ledgerflow.gateway;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.gateway.route.*;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.*;

@Configuration
class Routes {
  @Bean
  RouteLocator applicationRoutes(
      RouteLocatorBuilder b,
      @Value("${services.workspace-url:http://localhost:8086}") String workspace,
      @Value("${services.ledger-url:http://localhost:8081}") String ledger,
      @Value("${services.payments-url:http://localhost:8083}") String payments) {
    return b.routes()
        .route("workspace", r -> r.path("/api/v1/workspace", "/api/v1/workspace/**").uri(workspace))
        .route(
            "ledger",
            r ->
                r.path("/api/v1/wallets/**", "/api/v1/test/fundings", "/api/v1/admin/wallets/**")
                    .uri(ledger))
        .route("payments", r -> r.path("/api/v1/payments/**", "/api/v1/transfers/**").uri(payments))
        .build();
  }
}
