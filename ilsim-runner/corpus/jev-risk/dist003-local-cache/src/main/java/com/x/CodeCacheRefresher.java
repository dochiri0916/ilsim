package com.x;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CodeCacheRefresher {
    private final CodeRepository codeRepository;
    private final Map<String, String> localCodes = new ConcurrentHashMap<>();

    @Scheduled(fixedDelay = 60000)
    public void refresh() {
        Map<String, String> latest = new HashMap<>();
        for (Code code : codeRepository.findAll()) {
            latest.put(code.getKey(), code.getValue());
        }
        localCodes.keySet().retainAll(latest.keySet());
        localCodes.putAll(latest);
    }

    public String find(String key) {
        return localCodes.get(key);
    }
}
