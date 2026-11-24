# Mini Office v1.0 — Xuất APK tự động trên GitHub (Không cần cài Android Studio)

Bộ mã nguồn hoàn chỉnh **Docs + Sheets + Slides** (Rust Core + UniFFI + Kotlin Jetpack Compose) tối ưu riêng cho **Redmi Pad SE (`arm64-v8a`)**, 100% offline và không có quyền truy cập mạng.

## 4 bước lấy file APK mà không cần cài gì trên máy
1. Tạo một repository mới trên GitHub (chọn **Private** cho riêng tư).
2. Giải nén tệp này và đẩy toàn bộ nội dung bên trong thư mục `mini-office/` (bao gồm cả thư mục ẩn `.github/`) lên nhánh `main`.
3. Mở tab **Actions -> Build Mini Office v1.0 APK (arm64-v8a)** (tự động chạy khi push hoặc bấm **Run workflow**). GitHub sẽ tự cài Gradle 8.11.1, Rust, Android NDK, chạy unit test, kiểm định 0 quyền `INTERNET` và đóng gói APK.
4. Khi chạy xong, tải mục **Artifacts -> mini-office-v1.0-arm64-apk**, giải nén và cài file `.apk` thẳng lên Redmi Pad SE.

## Ký bản Release APK (tùy chọn)
Mặc định nếu chưa cấu hình Secrets, GitHub Actions xuất bản Debug APK cài dùng ngay. Khi muốn bản Release siêu gọn đã bật R8/ProGuard, thêm 4 Secrets vào **Settings -> Secrets and variables -> Actions**:
- `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`
