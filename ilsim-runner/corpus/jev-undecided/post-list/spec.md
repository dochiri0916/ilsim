# 게시글 목록 조회 API

## 요구사항
- REQ-1: `GET /posts?page={page}`는 게시글 목록을 페이지 단위로 돌려준다. `page`는 0부터 시작한다.
- REQ-2: 각 항목은 `id`, `title`, `authorName`, `createdAt`을 담는다.
- REQ-3: 응답은 `content`, `page`, `totalPages`, `hasNext`를 담는다.

## 영향 범위
- `src/main/java/com/x/post/PostController.java`
- `src/main/java/com/x/post/PostQueryService.java` (새 파일)
- `src/main/java/com/x/post/PostRepository.java`
- `src/main/java/com/x/post/dto/` 아래 `PostSummaryResponse.java`, `PostPageResponse.java` (새 파일)
- `src/test/java/com/x/post/PostControllerTest.java`

## 미결정 사항
- Q1. 기본 정렬은 최신순인가, 오래된 순인가?
- Q2. 페이지 크기는 얼마인가? `size` 파라미터로 바꿀 수 있게 하는가?
- Q3. 삭제 표시(`deleted = true`)된 글을 목록에서 빼는가?
- Q4. 로그인하지 않은 사용자도 목록을 볼 수 있는가?
