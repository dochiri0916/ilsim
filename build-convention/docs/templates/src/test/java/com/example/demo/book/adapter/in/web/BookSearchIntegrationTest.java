package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.port.out.BookRepositoryPort;
import com.example.demo.book.domain.Book;
import com.example.demo.book.domain.vo.BookTitle;
import com.example.demo.book.domain.vo.Isbn;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class BookSearchIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private BookRepositoryPort repository;

    @Test
    @DisplayName("HTTP 검색이 실제 서비스와 Querydsl 저장소를 거쳐 필터된 JSON을 응답한다")
    void searchesThroughRealLayers() throws Exception {
        // given
        repository.create(Book.create(Isbn.of("9788966260959"), BookTitle.of("클린 코드")));
        repository.create(Book.create(Isbn.of("9780000000000"), BookTitle.of("다른 도서")));

        // when & then
        mockMvc.perform(get("/api/books").param("isbn", "9788966260959"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].isbn").value("9788966260959"))
                .andExpect(jsonPath("$.items[0].title").value("클린 코드"))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(10));
    }

    @Test
    @DisplayName("잘못된 페이지와 크기는 실제 HTTP 경로에서도 입력 오류로 응답한다")
    void rejectsInvalidPagination() throws Exception {
        // given
        final String path = "/api/books";

        // when & then
        mockMvc.perform(get(path).param("size", "101"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"))
                .andExpect(jsonPath("$.timestamp").exists());
        mockMvc.perform(get(path).param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
    }

}
