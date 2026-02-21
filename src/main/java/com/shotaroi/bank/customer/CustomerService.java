package com.shotaroi.bank.customer;

import com.shotaroi.bank.customer.dto.LoginRequest;
import com.shotaroi.bank.customer.dto.LoginResponse;
import com.shotaroi.bank.customer.dto.RegisterRequest;
import com.shotaroi.bank.security.JwtTokenProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CustomerService {

    private static final Logger log = LoggerFactory.getLogger(CustomerService.class);

    private final CustomerRepository customerRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtTokenProvider jwtTokenProvider;

    public CustomerService(CustomerRepository customerRepository,
                           PasswordEncoder passwordEncoder,
                           JwtTokenProvider jwtTokenProvider) {
        this.customerRepository = customerRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtTokenProvider = jwtTokenProvider;
    }

    @Transactional
    public Customer register(RegisterRequest request) {
        if (customerRepository.existsByEmail(request.email())) {
            throw new IllegalArgumentException("Email already registered");
        }
        Customer customer = new Customer();
        customer.setEmail(request.email().toLowerCase());
        customer.setPasswordHash(passwordEncoder.encode(request.password()));
        customer.setRole(Customer.Role.USER);
        customer = customerRepository.save(customer);
        log.info("Customer registered: {}", customer.getEmail());
        return customer;
    }

    public LoginResponse login(LoginRequest request) {
        Customer customer = customerRepository.findByEmail(request.email().toLowerCase())
                .orElseThrow(() -> new IllegalArgumentException("Invalid email or password"));

        if (!passwordEncoder.matches(request.password(), customer.getPasswordHash())) {
            throw new IllegalArgumentException("Invalid email or password");
        }

        String token = jwtTokenProvider.generateToken(customer);
        log.info("Customer logged in: {}", customer.getEmail());
        return new LoginResponse(token);
    }

    public Customer findById(Long id) {
        return customerRepository.findById(id)
                .orElseThrow(() -> new com.shotaroi.bank.common.exceptions.ResourceNotFoundException("Customer not found: " + id));
    }

    public Customer findByEmail(String email) {
        return customerRepository.findByEmail(email)
                .orElseThrow(() -> new com.shotaroi.bank.common.exceptions.ResourceNotFoundException("Customer not found"));
    }
}
