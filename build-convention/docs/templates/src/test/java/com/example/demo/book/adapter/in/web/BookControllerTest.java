package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.exception.BookNotFoundException;
import com.example.demo.book.application.exception.DuplicateIsbnException;
import com.example.demo.book.application.port.in.result.GetBookResult;
import com.example.demo.book.application.port.in.query.SearchBooksQuery;
import com.example.demo.book.application.port.in.result.SearchBookResultItem;
import com.example.demo.book.application.port.in.result.SearchBookResultItems;
import com.example.demo.book.application.port.in.result.SearchBooksResult;
import com.example.demo.book.application.port.in.usecase.SearchBooksUseCase;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import com.example.demo.book.application.port.in.usecase.GetBookUseCase;
import com.example.demo.book.application.port.in.result.RegisterBookResult;
import com.example.demo.book.application.port.in.usecase.RegisterBookUseCase;
import com.example.demo.global.exception.ProblemDetails;
import com.example.demo.global.exception.GlobalApiControllerAdvice;
import com.example.demo.global.application.exception.RepositoryOperationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

class BookControllerTest {

    private static final String BODY = """
            {"isbn": "9788966260959", "title": "클린 코드"}
            """;
    private static final RegisterBookUseCase REGISTERED =
            command -> new RegisterBookResult("01900000-0000-7000-8000-000000000001", command.isbn(), command.title());
    private static final GetBookUseCase FOUND = query -> new GetBookResult(query.bookId(), "9788966260959", "클린 코드");

    private static final SearchBooksUseCase SEARCH = query -> new SearchBooksResult(
            new SearchBookResultItems(List.of()), 0, query.page(), query.size());

    private static MockMvc mockMvc(final BookController controller) {
        final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        final ProblemDetails problems = new ProblemDetails(clock);
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new BookExceptionHandler(problems), new GlobalApiControllerAdvice(problems))
                .build();
    }

    @Test
    @DisplayName("도서 등록에 성공하면 201과 도서 정보를 돌려준다")
    void register() throws Exception {
        final RegisterBookUseCase useCase = REGISTERED;

        final MockHttpServletResponse response = mockMvc(new BookController(useCase, FOUND, SEARCH))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getContentAsString()).contains("\"bookId\":\"01900000-0000-7000-8000-000000000001\"", "\"title\":\"클린 코드\"");
    }

    @Test
    @DisplayName("중복 ISBN이면 409와 DUPLICATE_ISBN 코드를 돌려준다")
    void duplicate() throws Exception {
        final RegisterBookUseCase useCase = command -> {
            throw new DuplicateIsbnException();
        };

        final MockHttpServletResponse response = mockMvc(new BookController(useCase, FOUND, SEARCH))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(409);
        assertThat(response.getContentAsString()).contains("\"code\":\"DUPLICATE_ISBN\"", "이미 등록된 ISBN입니다.");
    }

    @Test
    @DisplayName("저장된 도서를 조회하면 200과 도서 정보를 돌려준다")
    void findsBook() throws Exception {
        final BookController controller = new BookController(REGISTERED, FOUND, SEARCH);

        final MockHttpServletResponse response = mockMvc(controller)
                .perform(get("/api/books/{bookId}", "01900000-0000-7000-8000-000000000001")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"bookId\":\"01900000-0000-7000-8000-000000000001\"", "\"isbn\":\"9788966260959\"");
    }

    @Test
    @DisplayName("입력 형식이 잘못되면 업무 호출 없이 code와 timestamp를 포함한 400 응답을 돌려준다")
    void rejects_invalid_request() throws Exception {
        final RegisterBookUseCase forbidden = command -> {
            throw new AssertionError("입력 검증에 실패한 요청으로 업무를 호출했습니다.");
        };

        final MockHttpServletResponse response = mockMvc(new BookController(forbidden, FOUND, SEARCH))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isbn\":\"\",\"title\":\"\"}"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_INPUT\"", "\"timestamp\":");
    }

    @Test
    @DisplayName("저장소 실패 응답은 내부 원인 없이 500과 공통 오류 코드를 제공한다")
    void hides_repository_failure_details() throws Exception {
        final RegisterBookUseCase broken = command -> {
            throw new RepositoryOperationException(new IllegalStateException("비밀 DB 연결 정보"));
        };

        final MockHttpServletResponse response = mockMvc(new BookController(broken, FOUND, SEARCH))
                .perform(post("/api/books").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentAsString()).contains("\"code\":\"INTERNAL_ERROR\"", "\"timestamp\":")
                .doesNotContain("비밀 DB 연결 정보", "IllegalStateException", "RepositoryOperationException");
    }

    @Test
    @DisplayName("없는 도서를 조회하면 404와 BOOK_NOT_FOUND 코드를 돌려준다")
    void notFound() throws Exception {
        final GetBookUseCase useCase = bookId -> {
            throw new BookNotFoundException();
        };

        final MockHttpServletResponse response = mockMvc(new BookController(REGISTERED, useCase, SEARCH))
                .perform(get("/api/books/{bookId}", "missing")).andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"code\":\"BOOK_NOT_FOUND\"");
    }

    @Test
    @DisplayName("검색 파라미터를 Query로 전달하고 원시 JSON 목록과 페이지 정보를 응답한다")
    void searchesBooks() throws Exception {
        final AtomicReference<SearchBooksQuery> received = new AtomicReference<>();
        final SearchBooksUseCase search = query -> {
            received.set(query);
            final SearchBookResultItem item = new SearchBookResultItem("01900000-0000-7000-8000-000000000001", "9788966260959", "클린 코드");
            return new SearchBooksResult(new SearchBookResultItems(List.of(item)), 10, query.page(), query.size());
        };
        final BookController controller = new BookController(REGISTERED, FOUND, search);

        final MockHttpServletResponse response = mockMvc(controller)
                .perform(get("/api/books").param("isbn", "9788966260959").param("page", "2").param("size", "3"))
                .andReturn().getResponse();

        assertThat(received.get()).isEqualTo(new SearchBooksQuery("9788966260959", 2, 3));
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).contains("\"items\":[{", "\"total\":10", "\"page\":2", "\"size\":3", "클린 코드");
    }

    @Test
    @DisplayName("검색 범위가 잘못되면 유스케이스를 호출하지 않고 400으로 응답한다")
    void rejectsInvalidSearchBounds() throws Exception {
        final SearchBooksUseCase forbidden = query -> { throw new AssertionError("잘못된 검색 호출"); };
        final BookController controller = new BookController(REGISTERED, FOUND, forbidden);

        final MockHttpServletResponse response = mockMvc(controller)
                .perform(get("/api/books").param("page", "-1").param("size", "10"))
                .andReturn().getResponse();

        assertThat(response.getStatus()).isEqualTo(400);
        assertThat(response.getContentAsString()).contains("\"code\":\"INVALID_INPUT\"", "\"timestamp\":");
    }

}
