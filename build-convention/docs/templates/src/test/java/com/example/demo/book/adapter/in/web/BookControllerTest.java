package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.in.GetBookResult;
import com.example.demo.book.application.port.in.GetBookUseCase;
import com.example.demo.book.application.port.in.RegisterBookResult;
import com.example.demo.book.application.port.in.RegisterBookUseCase;
import com.example.demo.global.exception.ProblemDetails;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class BookControllerTest {

    private static final String BODY = """
            {"isbn": "9788966260959", "title": "클린 코드"}
            """;
    private static final RegisterBookUseCase REGISTERED =
            command -> new RegisterBookResult("book-1", command.isbn(), command.title());
    private static final GetBookUseCase FOUND = bookId -> new GetBookResult(bookId, "9788966260959", "클린 코드");

    private static MockMvc mockMvc(final BookController controller) {
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BookExceptionHandler(new ProblemDetails(Clock.systemUTC())))
                .build();
    }

    @Test
    @DisplayName("도서 등록에 성공하면 201과 도서 정보를 돌려준다")
    void register() throws Exception {
        // given
        final RegisterBookUseCase useCase = REGISTERED;

        // when
        final MockHttpServletResponse response = mockMvc(new BookController(useCase, FOUND))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse();

        // then
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("\"bookId\":\"book-1\"", "\"title\":\"클린 코드\"");
    }

    @Test
    @DisplayName("중복 ISBN이면 409와 DUPLICATE_ISBN 코드를 돌려준다")
    void duplicate() throws Exception {
        // given
        final RegisterBookUseCase useCase = command -> {
            throw new DuplicateIsbnException();
        };

        // when
        final MockHttpServletResponse response = mockMvc(new BookController(useCase, FOUND))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse();

        // then
        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("\"code\":\"DUPLICATE_ISBN\"", "이미 등록된 ISBN입니다.");
    }

    @Test
    @DisplayName("저장된 도서를 조회하면 200과 도서 정보를 돌려준다")
    void findsBook() throws Exception {
        // given
        final BookController controller = new BookController(REGISTERED, FOUND);

        // when
        final MockHttpServletResponse response = mockMvc(controller)
                .perform(get("/api/books/{bookId}", "book-1")).andReturn().getResponse();

        // then
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"bookId\":\"book-1\"", "\"isbn\":\"9788966260959\"");
    }

    @Test
    @DisplayName("없는 도서를 조회하면 404와 BOOK_NOT_FOUND 코드를 돌려준다")
    void notFound() throws Exception {
        // given
        final GetBookUseCase useCase = bookId -> {
            throw new BookNotFoundException();
        };

        // when
        final MockHttpServletResponse response = mockMvc(new BookController(REGISTERED, useCase))
                .perform(get("/api/books/{bookId}", "missing")).andReturn().getResponse();

        // then
        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"BOOK_NOT_FOUND\"");
    }

}
