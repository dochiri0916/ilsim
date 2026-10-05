package com.example.demo.book.adapter.in.web;

import com.example.demo.book.application.port.in.query.GetBookQuery;
import com.example.demo.book.application.port.in.query.SearchBooksQuery;
import com.example.demo.book.application.port.in.result.SearchBooksResult;
import com.example.demo.book.application.port.in.usecase.SearchBooksUseCase;
import com.example.demo.book.adapter.in.web.response.SearchBooksResponse;
import com.example.demo.book.application.port.in.command.RegisterBookCommand;

import com.example.demo.book.adapter.in.web.request.RegisterBookRequest;
import com.example.demo.book.adapter.in.web.response.GetBookResponse;
import com.example.demo.book.adapter.in.web.response.RegisterBookResponse;
import com.example.demo.book.application.port.in.result.GetBookResult;
import com.example.demo.book.application.port.in.usecase.GetBookUseCase;
import com.example.demo.book.application.port.in.result.RegisterBookResult;
import com.example.demo.book.application.port.in.usecase.RegisterBookUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/books")
public final class BookController {

    private final RegisterBookUseCase registerBookUseCase;
    private final GetBookUseCase getBookUseCase;
    private final SearchBooksUseCase searchBooksUseCase;

    @PostMapping
    public ResponseEntity<RegisterBookResponse> register(@Valid @RequestBody final RegisterBookRequest request) {
        final RegisterBookCommand command = request.toCommand();
        final RegisterBookResult result = registerBookUseCase.register(command);
        final RegisterBookResponse response = RegisterBookResponse.from(result);
        final HttpStatus status = HttpStatus.CREATED;
        return ResponseEntity.status(status)
                .body(response);
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<GetBookResponse> getBook(@PathVariable("bookId") final String bookId) {
        final GetBookResult result = getBookUseCase.getBook(new GetBookQuery(bookId));
        final GetBookResponse response = GetBookResponse.from(result);
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<SearchBooksResponse> search(
            @RequestParam(name = "isbn", required = false) final String isbn,
            @RequestParam(name = "page", defaultValue = "0") final int page,
            @RequestParam(name = "size", defaultValue = "10") final int size) {
        final SearchBooksQuery query = new SearchBooksQuery(isbn, page, size);
        final SearchBooksResult result = searchBooksUseCase.search(query);
        final SearchBooksResponse response = SearchBooksResponse.from(result);
        return ResponseEntity.ok(response);
    }

}
