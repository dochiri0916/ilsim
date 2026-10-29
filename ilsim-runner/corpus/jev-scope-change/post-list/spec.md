# 게시글 목록 조회

## 요구사항
- R1: 게시글 목록 API는 page와 size 파라미터를 받고, size는 최대 100으로 제한한다.
- R2: 게시글은 최신 작성순(createdAt 내림차순)으로 정렬한다.
- R3: 삭제된 글(deleted = true)은 목록에서 제외한다.

## 영향 범위
- `src/main/java/com/x/post/PostController.java`
- `src/main/java/com/x/post/PostService.java`

## 기존 기능 영향
- 게시글 작성·수정·삭제는 바꾸지 않는다.
