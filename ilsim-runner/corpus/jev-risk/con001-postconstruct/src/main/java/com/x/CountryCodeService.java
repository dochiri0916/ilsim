package com.x;

import jakarta.annotation.PostConstruct;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CountryCodeService {
    private final CountryRepository countryRepository;
    private Map<String, String> namesByCode;

    @PostConstruct
    void load() {
        namesByCode = countryRepository.findAll().stream()
                .collect(Collectors.toUnmodifiableMap(Country::getCode, Country::getName));
    }

    public String nameOf(String code) {
        return namesByCode.get(code);
    }
}
