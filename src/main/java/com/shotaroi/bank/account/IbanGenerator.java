package com.shotaroi.bank.account;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.stream.Collectors;

@Component
public class IbanGenerator {

    private static final String COUNTRY_CODE = "SE";
    private static final int CHECK_DIGITS = 0;
    private static final String BANK_CODE = "BANK"; // In real banking in Sweden, it's the clearing number. 
    private static final int ACCOUNT_NUMBER_LENGTH = 16;
    private static final SecureRandom RANDOM = new SecureRandom();

    public String generate() {
        String accountNumber = RANDOM.ints(ACCOUNT_NUMBER_LENGTH, 0, 10)
                .mapToObj(String::valueOf)
                .collect(Collectors.joining());
        String bban = BANK_CODE + accountNumber;
        int checkDigits = calculateCheckDigits(bban);
        return COUNTRY_CODE + String.format("%02d", checkDigits) + bban;
    }

    private int calculateCheckDigits(String bban) {
        String rearranged = bban + COUNTRY_CODE + "00";
        String numeric = rearranged.chars()
                .mapToObj(c -> {
                    if (Character.isLetter(c)) {
                        return String.valueOf(c - 'A' + 10);
                    }
                    return String.valueOf((char) c);
                })
                .collect(Collectors.joining());

        int remainder = 0;
        for (int i = 0; i < numeric.length(); i++) {
            remainder = (remainder * 10 + Character.getNumericValue(numeric.charAt(i))) % 97;
        }
        return 98 - remainder;
    }
}
