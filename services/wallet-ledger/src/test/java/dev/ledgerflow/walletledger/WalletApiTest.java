package dev.ledgerflow.walletledger;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest({WalletAdministration.class, WalletController.class, InternalTransferController.class})
@Import(Security.class)
class WalletApiTest {
  @Autowired MockMvc mvc;
  @MockitoBean WalletQueries wallets;
  @MockitoBean Ledger ledger;
  @MockitoBean InternalTransfers internal;
  @MockitoBean JwtDecoder decoder;

  @Test
  void anonymousRequestsRequireAuthentication() throws Exception {
    mvc.perform(get("/api/v1/wallets/" + UUID.randomUUID() + "/transactions"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
        .andExpect(jsonPath("$.requestId").isNotEmpty());
    verifyNoInteractions(wallets);
  }

  @Test
  void customerCannotFreezeWallet() throws Exception {
    mvc.perform(
            put("/api/v1/admin/wallets/" + UUID.randomUUID() + "/freeze")
                .with(jwt())
                .contentType("application/json")
                .content("{\"frozen\":true,\"reason\":\"review\"}"))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    verifyNoInteractions(wallets);
  }

  @Test
  void realBearerAuthenticationMapsKeycloakRealmRoles() throws Exception {
    UUID wallet = UUID.randomUUID();
    when(decoder.decode("admin-token"))
        .thenReturn(
            Jwt.withTokenValue("admin-token")
                .header("alg", "RS256")
                .subject("admin")
                .claim("realm_access", Map.of("roles", List.of("administrator")))
                .build());
    when(wallets.freeze(eq("admin"), eq(wallet), eq(true), eq("review"), anyString()))
        .thenReturn(new WalletQueries.FreezeState(wallet, true));
    mvc.perform(
            put("/api/v1/admin/wallets/" + wallet + "/freeze")
                .header("Authorization", "Bearer admin-token")
                .contentType("application/json")
                .content("{\"frozen\":true,\"reason\":\"review\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.frozen").value(true));
  }

  @Test
  void historyUsesAuthenticatedSubjectAndIncludesRequestIdOnDenial() throws Exception {
    UUID wallet = UUID.randomUUID();
    when(wallets.history("alice", wallet, 0, 20))
        .thenThrow(new LedgerException(404, "WALLET_NOT_FOUND"));
    mvc.perform(
            get("/api/v1/wallets/" + wallet + "/transactions")
                .with(jwt().jwt(j -> j.subject("alice"))))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("WALLET_NOT_FOUND"))
        .andExpect(jsonPath("$.requestId").isNotEmpty())
        .andExpect(header().exists("X-Request-ID"));
  }

  @Test
  void freezeRequiresExplicitState() throws Exception {
    mvc.perform(
            put("/api/v1/admin/wallets/" + UUID.randomUUID() + "/freeze")
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "ROLE_administrator")))
                .contentType("application/json")
                .content("{\"reason\":\"review\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("INVALID_FREEZE_STATE"));
    verifyNoInteractions(wallets);
  }

  @Test
  void internalApiRequiresScopeAudienceAndPaymentsClient() throws Exception {
    UUID reference = UUID.randomUUID();
    mvc.perform(get("/internal/v1/transfers/" + reference).with(jwt()))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/internal/v1/transfers/" + reference)
                .with(
                    jwt()
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_ledger.post"))))
        .andExpect(status().isForbidden());
    verifyNoInteractions(internal);
    mvc.perform(
            get("/internal/v1/transfers/" + reference)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("aud", List.of("ledger-internal"))
                                    .claim("azp", "ledgerflow-web"))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_ledger.post"))))
        .andExpect(status().isForbidden());
  }

  @Test
  void scopedPaymentsServiceCanQueryInternalDecision() throws Exception {
    UUID reference = UUID.randomUUID();
    when(internal.lookup(reference))
        .thenReturn(
            new InternalTransfers.Decision(reference, "SUCCEEDED", UUID.randomUUID(), null));
    mvc.perform(
            get("/internal/v1/transfers/" + reference)
                .with(
                    jwt()
                        .jwt(
                            j ->
                                j.claim("aud", List.of("ledger-internal"))
                                    .claim("azp", "payments-service"))
                        .authorities(
                            new org.springframework.security.core.authority.SimpleGrantedAuthority(
                                "SCOPE_ledger.post"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SUCCEEDED"));
  }

  @Test
  void fundingIsDisabledByDefault() throws Exception {
    mvc.perform(
            post("/api/v1/test/fundings")
                .with(jwt())
                .header("Idempotency-Key", "fund")
                .contentType("application/json")
                .content(
                    "{\"destinationWalletId\":\""
                        + UUID.randomUUID()
                        + "\",\"amount\":\"1.00\",\"currency\":\"GBP\"}"))
        .andExpect(status().isNotFound());
    verifyNoInteractions(ledger);
  }
}
