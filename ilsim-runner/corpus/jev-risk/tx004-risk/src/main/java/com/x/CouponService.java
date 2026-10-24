package com.x;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class CouponService {
    private static final Logger log = LoggerFactory.getLogger(CouponService.class);
    private final CouponRepository couponRepository;
    private final PointRepository pointRepository;

    @Transactional
    public void useCoupon(Long memberId, Long couponId) {
        try {
            couponRepository.deleteById(couponId);
            pointRepository.save(new PointHistory(memberId, 1000));
        } catch (Exception e) {
            log.warn("쿠폰 사용 실패", e);
        }
    }
}
