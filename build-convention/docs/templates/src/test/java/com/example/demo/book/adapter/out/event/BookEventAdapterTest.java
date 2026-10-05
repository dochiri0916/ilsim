package com.example.demo.book.adapter.out.event;

import com.example.demo.book.domain.event.BookRegistered;
import com.example.demo.book.domain.vo.BookId;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class BookEventAdapterTest {

    @Test
    @DisplayName("등록 이벤트를 스프링 이벤트로 발행한다")
    void publish() {
        // given
        final List<Object> published = new ArrayList<>();
        final BookEventAdapter adapter = new BookEventAdapter(published::add);
        final BookRegistered event = new BookRegistered(BookId.parse("01900000-0000-7000-8000-000000000001"), Isbn.of("9788966260959"));

        // when
        adapter.publish(event);

        // then
        assertThat(published).containsExactly(event);
    }

}
