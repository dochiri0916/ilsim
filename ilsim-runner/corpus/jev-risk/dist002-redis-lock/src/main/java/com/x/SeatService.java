package com.x;

import java.util.concurrent.locks.Lock;
import lombok.RequiredArgsConstructor;
import org.springframework.integration.redis.util.RedisLockRegistry;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class SeatService {
    private final SeatRepository seatRepository;
    private final RedisLockRegistry lockRegistry;

    public void reserve(Long seatId, Long memberId) {
        Lock lock = lockRegistry.obtain("seat:" + seatId);
        lock.lock();
        try {
            Seat seat = seatRepository.findById(seatId).orElseThrow();
            if (seat.isReserved()) {
                throw new IllegalStateException("이미 예약된 좌석");
            }
            seat.reserve(memberId);
            seatRepository.save(seat);
        } finally {
            lock.unlock();
        }
    }
}
