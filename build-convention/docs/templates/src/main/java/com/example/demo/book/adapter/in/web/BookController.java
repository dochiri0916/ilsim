package com.example.demo.book.adapter.in.web;

import com.example.demo.book.adapter.in.web.request.RegisterBookRequest;
import com.example.demo.book.adapter.in.web.response.GetBookResponse;
import com.example.demo.book.adapter.in.web.response.RegisterBookResponse;
import com.example.demo.book.application.port.in.GetBookResult;
import com.example.demo.book.application.port.in.GetBookUseCase;
import com.example.demo.book.application.port.in.RegisterBookCommand;
import com.example.demo.book.application.port.in.RegisterBookResult;
import com.example.demo.book.application.port.in.RegisterBookUseCase;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/books")
public class BookController {

    private final RegisterBookUseCase registerBookUseCase;
    private final GetBookUseCase getBookUseCase;

    @PostMapping
    public ResponseEntity<RegisterBookResponse> register(@RequestBody final RegisterBookRequest request) {
        final RegisterBookResult result = registerBookUseCase.register(new RegisterBookCommand(request.isbn(), request.title()));
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new RegisterBookResponse(result.bookId(), result.isbn(), result.title()));
    }

    @GetMapping("/{bookId}")
    public ResponseEntity<GetBookResponse> getBook(@PathVariable final String bookId) {
        final GetBookResult result = getBookUseCase.getBook(bookId);
        return ResponseEntity.ok(new GetBookResponse(result.bookId(), result.isbn(), result.title()));
    }

}
