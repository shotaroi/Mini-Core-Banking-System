package com.shotaroi.bank.integration;

import com.shotaroi.bank.account.Account;
import com.shotaroi.bank.account.AccountRepository;
import com.shotaroi.bank.customer.Customer;
import com.shotaroi.bank.customer.CustomerRepository;
import com.shotaroi.bank.ledger.LedgerEntry;
import com.shotaroi.bank.ledger.LedgerRepository;
import com.shotaroi.bank.security.JwtTokenProvider;
import com.shotaroi.bank.transfer.Transfer;
import com.shotaroi.bank.transfer.TransferRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
class TransferIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("bankdb")
            .withUsername("bankuser")
            .withPassword("bankpass");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private LedgerRepository ledgerRepository;
    @Autowired
    private TransferRepository transferRepository;
    @Autowired
    private PasswordEncoder passwordEncoder;
    @Autowired
    private JwtTokenProvider jwtTokenProvider;

    private String token1;
    private String token2;
    private Long account1Id;
    private Long account2Id;

    @BeforeEach
    void setUp() {
        transferRepository.deleteAll();
        ledgerRepository.deleteAll();
        accountRepository.deleteAll();
        customerRepository.deleteAll();

        Customer c1 = new Customer();
        c1.setEmail("user1@test.com");
        c1.setPasswordHash(passwordEncoder.encode("password123"));
        c1.setRole(Customer.Role.USER);
        c1 = customerRepository.save(c1);

        Customer c2 = new Customer();
        c2.setEmail("user2@test.com");
        c2.setPasswordHash(passwordEncoder.encode("password123"));
        c2.setRole(Customer.Role.USER);
        c2 = customerRepository.save(c2);

        token1 = jwtTokenProvider.generateToken(c1);
        token2 = jwtTokenProvider.generateToken(c2);

        Account a1 = new Account();
        a1.setCustomerId(c1.getId());
        a1.setIban("SE0000000000000000000001");
        a1.setCurrency("SEK");
        a1.setBalance(new BigDecimal("1000.00"));
        a1 = accountRepository.save(a1);
        account1Id = a1.getId();

        Account a2 = new Account();
        a2.setCustomerId(c2.getId());
        a2.setIban("SE0000000000000000000002");
        a2.setCurrency("SEK");
        a2.setBalance(new BigDecimal("500.00"));
        a2 = accountRepository.save(a2);
        account2Id = a2.getId();
    }

    @Test
    void successfulTransfer_createsCorrectLedgerEntriesAndBalances() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"fromAccountId":%d,"toAccountId":%d,"amount":100.50,"currency":"SEK","reference":"test transfer"}
                """.formatted(account1Id, account2Id);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").exists())
                .andExpect(jsonPath("$.status").value("SUCCESS"));

        Account a1 = accountRepository.findById(account1Id).orElseThrow();
        Account a2 = accountRepository.findById(account2Id).orElseThrow();
        assertThat(a1.getBalance()).isEqualByComparingTo(new BigDecimal("899.50"));
        assertThat(a2.getBalance()).isEqualByComparingTo(new BigDecimal("600.50"));

        List<LedgerEntry> entries1 = ledgerRepository.findByAccountIdOrderByCreatedAtDesc(account1Id, org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        List<LedgerEntry> entries2 = ledgerRepository.findByAccountIdOrderByCreatedAtDesc(account2Id, org.springframework.data.domain.PageRequest.of(0, 10)).getContent();
        assertThat(entries1).hasSize(1);
        assertThat(entries1.get(0).getType()).isEqualTo(LedgerEntry.EntryType.TRANSFER_OUT);
        assertThat(entries1.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("100.50"));
        assertThat(entries2).hasSize(1);
        assertThat(entries2.get(0).getType()).isEqualTo(LedgerEntry.EntryType.TRANSFER_IN);
        assertThat(entries2.get(0).getAmount()).isEqualByComparingTo(new BigDecimal("100.50"));
    }

    @Test
    void idempotency_sameKeyReturnsSameTransfer() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body = """
                {"fromAccountId":%d,"toAccountId":%d,"amount":50.00,"currency":"SEK","reference":"idem"}
                """.formatted(account1Id, account2Id);

        MvcResult first = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        String firstBody = first.getResponse().getContentAsString();
        Long firstTransferId = JsonPath.parse(firstBody).read("$.transferId", Long.class);

        MvcResult second = mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn();

        Long secondTransferId = JsonPath.parse(second.getResponse().getContentAsString()).read("$.transferId", Long.class);
        assertThat(secondTransferId).isEqualTo(firstTransferId);

        Account a1 = accountRepository.findById(account1Id).orElseThrow();
        assertThat(a1.getBalance()).isEqualByComparingTo(new BigDecimal("950.00"));
    }

    @Test
    void idempotency_differentPayloadReturns409() throws Exception {
        String idempotencyKey = UUID.randomUUID().toString();
        String body1 = """
                {"fromAccountId":%d,"toAccountId":%d,"amount":50.00,"currency":"SEK","reference":"first"}
                """.formatted(account1Id, account2Id);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body1))
                .andExpect(status().isCreated());

        String body2 = """
                {"fromAccountId":%d,"toAccountId":%d,"amount":75.00,"currency":"SEK","reference":"second"}
                """.formatted(account1Id, account2Id);

        mockMvc.perform(post("/api/transfers")
                        .header("Authorization", "Bearer " + token1)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body2))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrency_20TransfersNoNegativeBalance() throws Exception {
        int numTransfers = 20;
        int threads = 10;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        BigDecimal amountPerTransfer = new BigDecimal("30.00");
        BigDecimal totalFromAccount1 = new BigDecimal("1000.00");
        BigDecimal totalFromAccount2 = new BigDecimal("500.00");
        BigDecimal expectedTotal = totalFromAccount1.add(totalFromAccount2);

        for (int i = 0; i < numTransfers; i++) {
            final int idx = i;
            executor.submit(() -> {
                try {
                    latch.await();
                    String idempotencyKey = UUID.randomUUID().toString();
                    boolean from1To2 = idx % 2 == 0;
                    long fromId = from1To2 ? account1Id : account2Id;
                    long toId = from1To2 ? account2Id : account1Id;
                    String token = from1To2 ? token1 : token2;

                    String body = """
                            {"fromAccountId":%d,"toAccountId":%d,"amount":30.00,"currency":"SEK","reference":"conv-%d"}
                            """.formatted(fromId, toId, idx);

                    var result = mockMvc.perform(post("/api/transfers")
                                    .header("Authorization", "Bearer " + token)
                                    .header("Idempotency-Key", idempotencyKey)
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                            .andReturn();

                    if (result.getResponse().getStatus() == 201) {
                        successCount.incrementAndGet();
                    } else {
                        failCount.incrementAndGet();
                    }
                } catch (Exception e) {
                    failCount.incrementAndGet();
                }
            });
        }

        latch.countDown();
        executor.shutdown();
        while (!executor.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS)) {
            Thread.sleep(100);
        }

        Account a1 = accountRepository.findById(account1Id).orElseThrow();
        Account a2 = accountRepository.findById(account2Id).orElseThrow();
        assertThat(a1.getBalance().signum() >= 0).isTrue();
        assertThat(a2.getBalance().signum() >= 0).isTrue();
        assertThat(a1.getBalance().add(a2.getBalance())).isEqualByComparingTo(expectedTotal);
    }
}
