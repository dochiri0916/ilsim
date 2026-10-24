package com.x;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SeatService {
    private final SeatRepository seatRepository;

    @Transactional
    public synchronized void reserve(Long seatId, Long memberId) {
        Seat seat = seatRepository.findById(seatId).orElseThrow();
        if (seat.isReserved()) {
            throw new IllegalStateException("이미 예약된 좌석");
        }
        seat.reserve(memberId);
    }
}
