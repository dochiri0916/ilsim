# 첨부 파일 업로드

## 요구사항
- REQ-1: `POST /posts/{postId}/attachments`는 multipart 파일을 받아 `FileStorage` 포트로 저장하고 첨부 정보를 돌려준다.
- REQ-2: 첨부 정보는 `id`, `originalName`, `size`, `url`이다.
- REQ-3: 글 작성자만 올릴 수 있다. 다른 사용자면 403을 돌려준다.

## 영향 범위
- `src/main/java/com/x/attachment/AttachmentController.java` (새 파일)
- `src/main/java/com/x/attachment/AttachmentService.java` (새 파일)
- `src/main/java/com/x/attachment/Attachment.java` (새 파일)
- `src/test/java/com/x/attachment/AttachmentControllerTest.java`

## 미결정 사항
- Q1. 파일 크기 상한은 얼마인가?
- Q2. 허용하는 확장자를 제한하는가?
- Q3. 같은 이름 파일을 다시 올리면 덮어쓰는가, 새 이름으로 저장하는가?
- Q4. 글 하나에 붙일 수 있는 첨부 개수에 상한을 두는가?
