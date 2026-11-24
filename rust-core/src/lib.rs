pub mod docs;
pub mod sheets;
pub mod slides;
pub mod storage;

uniffi::setup_scaffolding!();

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum OfficeError {
    #[error("Lỗi truy cập tệp cục bộ: {message}")]
    Io { message: String },
    #[error("Đường dẫn tệp không hợp lệ")]
    InvalidPath,
    #[error("Tệp không tồn tại: {path}")]
    NotFound { path: String },
    #[error("Lỗi xuất định dạng tài liệu: {message}")]
    Export { message: String },
    #[error("Lỗi đọc định dạng tệp: {message}")]
    Parse { message: String },
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum FileKind {
    Docs,
    Sheets,
    Slides,
    Other,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct FileEntry {
    pub name: String,
    pub path: String,
    pub kind: FileKind,
    pub size_bytes: u64,
    pub modified_epoch_secs: u64,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct CoreStatus {
    pub version: String,
    pub target_arch: String,
    pub offline_only: bool,
    pub message: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocHeading {
    pub level: u8,
    pub title: String,
    pub char_offset: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocStats {
    pub words: u32,
    pub chars: u32,
    pub lines: u32,
    pub headings: u32,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct DocSearchMatch {
    pub line_number: u32,
    pub char_offset: u32,
    pub preview: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SheetCell {
    pub row: u32,
    pub col: u32,
    pub raw: String,
    pub display: String,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SheetData {
    pub name: String,
    pub row_count: u32,
    pub col_count: u32,
    pub cells: Vec<SheetCell>,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SheetSummary {
    pub sum: f64,
    pub avg: f64,
    pub count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum SlideLayout {
    Cover,
    TitleBody,
    TwoColumn,
}

#[derive(Debug, Clone, uniffi::Record)]
pub struct SlideItem {
    pub title: String,
    pub body_left: String,
    pub body_right: String,
    pub notes: String,
    pub layout: SlideLayout,
}

#[uniffi::export]
pub fn ping_core() -> CoreStatus {
    CoreStatus {
        version: env!("CARGO_PKG_VERSION").to_string(),
        target_arch: std::env::consts::ARCH.to_string(),
        offline_only: true,
        message: "Rust Core v0.5 sẵn sàng (Docs + Sheets + Slides MVP · 100% offline)".to_string(),
    }
}

#[uniffi::export]
pub fn atomic_write_text(path: String, content: String) -> Result<u64, OfficeError> {
    storage::atomic_write_bytes(&path, content.as_bytes())
}

#[uniffi::export]
pub fn atomic_write_bytes(path: String, data: Vec<u8>) -> Result<u64, OfficeError> {
    storage::atomic_write_bytes(&path, &data)
}

#[uniffi::export]
pub fn read_text_file(path: String) -> Result<String, OfficeError> {
    storage::read_text_file(&path)
}

#[uniffi::export]
pub fn list_workspace_files(dir_path: String) -> Result<Vec<FileEntry>, OfficeError> {
    storage::list_workspace_files(&dir_path)
}

#[uniffi::export]
pub fn rename_workspace_file(old_path: String, new_name: String) -> Result<String, OfficeError> {
    storage::rename_file(&old_path, &new_name)
}

#[uniffi::export]
pub fn delete_workspace_file(path: String) -> Result<(), OfficeError> {
    storage::delete_file(&path)
}

// --- Docs Exports ---

#[uniffi::export]
pub fn docs_extract_outline(markdown: String) -> Vec<DocHeading> {
    docs::extract_outline(&markdown)
}

#[uniffi::export]
pub fn docs_compute_stats(markdown: String) -> DocStats {
    docs::compute_stats(&markdown)
}

#[uniffi::export]
pub fn docs_search(markdown: String, query: String) -> Vec<DocSearchMatch> {
    docs::search_in_doc(&markdown, &query)
}

#[uniffi::export]
pub fn docs_export_docx(markdown: String, output_path: String) -> Result<u64, OfficeError> {
    docs::export_markdown_to_docx(&markdown, &output_path)
}

// --- Sheets Exports ---

#[uniffi::export]
pub fn sheets_parse_csv(name: String, csv_text: String) -> SheetData {
    sheets::parse_csv(&name, &csv_text)
}

#[uniffi::export]
pub fn sheets_to_csv(sheet: SheetData) -> String {
    sheets::to_csv(&sheet)
}

#[uniffi::export]
pub fn sheets_evaluate(sheet: SheetData) -> SheetData {
    sheets::evaluate_sheet(sheet)
}

#[uniffi::export]
pub fn sheets_read_xlsx(path: String) -> Result<Vec<SheetData>, OfficeError> {
    sheets::read_xlsx(&path)
}

#[uniffi::export]
pub fn sheets_export_xlsx(sheets_list: Vec<SheetData>, output_path: String) -> Result<u64, OfficeError> {
    sheets::export_xlsx(&sheets_list, &output_path)
}

#[uniffi::export]
pub fn sheets_summarize_numbers(values: Vec<String>) -> SheetSummary {
    sheets::summarize_numbers(&values)
}

// --- Slides Exports ---

#[uniffi::export]
pub fn slides_parse_deck(text: String) -> Vec<SlideItem> {
    slides::parse_deck_text(&text)
}

#[uniffi::export]
pub fn slides_serialize_deck(items: Vec<SlideItem>) -> String {
    slides::serialize_deck_text(&items)
}

#[uniffi::export]
pub fn slides_read_pptx(path: String) -> Result<Vec<SlideItem>, OfficeError> {
    slides::read_pptx(&path)
}

#[uniffi::export]
pub fn slides_export_pptx(items: Vec<SlideItem>, output_path: String) -> Result<u64, OfficeError> {
    slides::export_pptx(&items, &output_path)
}
