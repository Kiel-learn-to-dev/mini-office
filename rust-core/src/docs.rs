use crate::{storage, DocHeading, DocSearchMatch, DocStats, OfficeError};
use docx_rs::{Docx, Paragraph, Run, Table, TableCell, TableRow};
use pulldown_cmark::{Event, HeadingLevel, Options, Parser, Tag, TagEnd};
use std::io::Cursor;

fn heading_level_to_u8(level: HeadingLevel) -> u8 {
    match level {
        HeadingLevel::H1 => 1,
        HeadingLevel::H2 => 2,
        HeadingLevel::H3 => 3,
        HeadingLevel::H4 => 4,
        HeadingLevel::H5 => 5,
        HeadingLevel::H6 => 6,
    }
}

/// Trích xuất mục lục (H1–H3) kèm vị trí ký tự UTF-16 tương thích trực tiếp với TextFieldValue của Compose.
pub fn extract_outline(markdown: &str) -> Vec<DocHeading> {
    let mut headings = Vec::new();
    let parser = Parser::new_ext(markdown, Options::ENABLE_TABLES).into_offset_iter();

    let mut active_level: Option<u8> = None;
    let mut active_offset: u32 = 0;
    let mut buffer = String::new();

    for (event, range) in parser {
        match event {
            Event::Start(Tag::Heading { level, .. }) => {
                let lv = heading_level_to_u8(level);
                if lv <= 3 {
                    active_level = Some(lv);
                    // Chuyển byte offset sang UTF-16 code unit offset cho Kotlin/Compose
                    active_offset = markdown[..range.start].encode_utf16().count() as u32;
                    buffer.clear();
                }
            }
            Event::Text(t) | Event::Code(t) if active_level.is_some() => {
                buffer.push_str(&t);
            }
            Event::End(TagEnd::Heading(_)) => {
                if let Some(level) = active_level.take() {
                    let title = buffer.trim().to_string();
                    if !title.is_empty() {
                        headings.push(DocHeading {
                            level,
                            title,
                            char_offset: active_offset,
                        });
                    }
                    buffer.clear();
                }
            }
            _ => {}
        }
    }

    headings
}

pub fn compute_stats(markdown: &str) -> DocStats {
    let words = markdown.split_whitespace().count() as u32;
    let chars = markdown.chars().count() as u32;
    let lines = if markdown.is_empty() {
        0
    } else {
        markdown.lines().count() as u32
    };
    let headings = extract_outline(markdown).len() as u32;

    DocStats {
        words,
        chars,
        lines,
        headings,
    }
}

/// Tìm kiếm không phân biệt hoa thường trong văn bản, trả về vị trí dòng và đoạn trích.
pub fn search_in_doc(markdown: &str, query: &str) -> Vec<DocSearchMatch> {
    let needle = query.trim().to_lowercase();
    if needle.is_empty() {
        return Vec::new();
    }

    let mut results = Vec::new();
    let mut utf16_offset: u32 = 0;

    for (idx, line) in markdown.split('\n').enumerate() {
        let lower_line = line.to_lowercase();
        if let Some(byte_pos) = lower_line.find(&needle) {
            let local_utf16 = lower_line[..byte_pos].encode_utf16().count() as u32;
            let preview: String = line.trim().chars().take(80).collect();
            results.push(DocSearchMatch {
                line_number: (idx + 1) as u32,
                char_offset: utf16_offset + local_utf16,
                preview,
            });
        }
        utf16_offset += line.encode_utf16().count() as u32 + 1; // +1 cho ký tự '\n'
    }

    results
}

/// Chuyển Markdown (tiêu đề H1–H3, đậm/nghiêng, danh sách, bảng GFM) sang .docx và lưu atomic.
pub fn export_markdown_to_docx(markdown: &str, output_path: &str) -> Result<u64, OfficeError> {
    let mut docx = Docx::new();
    let parser = Parser::new_ext(markdown, Options::ENABLE_TABLES);

    let mut bold = false;
    let mut italic = false;
    let mut heading_level: Option<u8> = None;
    let mut in_list_item = false;
    let mut current_para: Option<Paragraph> = None;

    // Trạng thái bảng GFM
    let mut in_table = false;
    let mut table_rows: Vec<TableRow> = Vec::new();
    let mut current_row_cells: Vec<TableCell> = Vec::new();
    let mut current_cell_text = String::new();
    let mut in_table_head = false;

    for event in parser {
        match event {
            Event::Start(Tag::Heading { level, .. }) => {
                heading_level = Some(heading_level_to_u8(level));
                current_para = Some(Paragraph::new());
            }
            Event::End(TagEnd::Heading(_)) => {
                if let Some(p) = current_para.take() {
                    docx = docx.add_paragraph(p);
                }
                heading_level = None;
            }
            Event::Start(Tag::Paragraph) => {
                if !in_table {
                    current_para = Some(Paragraph::new());
                }
            }
            Event::End(TagEnd::Paragraph) => {
                if !in_table {
                    if let Some(p) = current_para.take() {
                        docx = docx.add_paragraph(p);
                    }
                }
            }
            Event::Start(Tag::Item) => {
                in_list_item = true;
                current_para = Some(Paragraph::new().add_run(Run::new().add_text("• ")));
            }
            Event::End(TagEnd::Item) => {
                if let Some(p) = current_para.take() {
                    docx = docx.add_paragraph(p);
                }
                in_list_item = false;
            }
            Event::Start(Tag::Strong) => bold = true,
            Event::End(TagEnd::Strong) => bold = false,
            Event::Start(Tag::Emphasis) => italic = true,
            Event::End(TagEnd::Emphasis) => italic = false,

            Event::Start(Tag::Table(_)) => {
                in_table = true;
                table_rows.clear();
            }
            Event::End(TagEnd::Table) => {
                if !table_rows.is_empty() {
                    let rows = std::mem::take(&mut table_rows);
                    docx = docx.add_table(Table::new(rows));
                }
                in_table = false;
            }
            Event::Start(Tag::TableHead) => {
                in_table_head = true;
                current_row_cells.clear();
            }
            Event::End(TagEnd::TableHead) => {
                if !current_row_cells.is_empty() {
                    let cells = std::mem::take(&mut current_row_cells);
                    table_rows.push(TableRow::new(cells));
                }
                in_table_head = false;
            }
            Event::Start(Tag::TableRow) => {
                current_row_cells.clear();
            }
            Event::End(TagEnd::TableRow) => {
                if !current_row_cells.is_empty() {
                    let cells = std::mem::take(&mut current_row_cells);
                    table_rows.push(TableRow::new(cells));
                }
            }
            Event::Start(Tag::TableCell) => {
                current_cell_text.clear();
            }
            Event::End(TagEnd::TableCell) => {
                let mut run = Run::new().add_text(current_cell_text.trim());
                if in_table_head {
                    run = run.bold();
                }
                current_row_cells.push(TableCell::new().add_paragraph(Paragraph::new().add_run(run)));
                current_cell_text.clear();
            }

            Event::Text(text) | Event::Code(text) => {
                if in_table {
                    current_cell_text.push_str(&text);
                } else {
                    let mut run = Run::new().add_text(text.as_ref());
                    if bold || heading_level.is_some() {
                        run = run.bold();
                    }
                    if italic {
                        run = run.italic();
                    }
                    if let Some(lv) = heading_level {
                        // Cỡ chữ nửa-pt (half-points): H1=36 (18pt), H2=30 (15pt), H3=26 (13pt)
                        let size = match lv {
                            1 => 36,
                            2 => 30,
                            _ => 26,
                        };
                        run = run.size(size);
                    }
                    let para = current_para.take().unwrap_or_else(|| {
                        if in_list_item {
                            Paragraph::new().add_run(Run::new().add_text("• "))
                        } else {
                            Paragraph::new()
                        }
                    });
                    current_para = Some(para.add_run(run));
                }
            }
            Event::SoftBreak | Event::HardBreak => {
                if in_table {
                    current_cell_text.push(' ');
                } else if let Some(para) = current_para.take() {
                    current_para = Some(para.add_run(Run::new().add_break(docx_rs::BreakType::TextWrapping)));
                }
            }
            _ => {}
        }
    }

    if let Some(p) = current_para.take() {
        docx = docx.add_paragraph(p);
    }

    let mut buf = Cursor::new(Vec::new());
    docx.build()
        .pack(&mut buf)
        .map_err(|e| OfficeError::Export {
            message: e.to_string(),
        })?;

    let bytes = buf.into_inner();
    storage::atomic_write_bytes(output_path, &bytes)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn test_outline_stats_and_search() {
        let md = "# Kế hoạch Quý 4

Nội dung **quan trọng**.

## Ngân sách
- Máy chủ
- Bản quyền
";
        let outline = extract_outline(md);
        assert_eq!(outline.len(), 2);
        assert_eq!(outline[0].level, 1);
        assert_eq!(outline[0].title, "Kế hoạch Quý 4");
        assert_eq!(outline[1].level, 2);
        assert_eq!(outline[1].title, "Ngân sách");

        let stats = compute_stats(md);
        assert!(stats.words >= 10);
        assert_eq!(stats.headings, 2);

        let matches = search_in_doc(md, "ngân sách");
        assert_eq!(matches.len(), 1);
        assert_eq!(matches[0].line_number, 5);
    }

    #[test]
    fn test_export_docx_produces_valid_zip() {
        let md = "# Báo cáo

Đoạn văn **đậm** và *nghiêng*.

| Hạng mục | Chi phí |
| --- | --- |
| Thiết bị | 10,000,000 |
";
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let out = std::env::temp_dir().join(format!("mini_office_doc_{}.docx", nanos));
        let bytes_written = export_markdown_to_docx(md, &out.to_string_lossy()).unwrap();
        assert!(bytes_written > 100);

        let raw = fs::read(&out).unwrap();
        // Tệp .docx hợp lệ luôn bắt đầu bằng chữ ký ZIP: PK
        assert_eq!(&raw[0..4], b"PK");
        let _ = fs::remove_file(&out);
    }
}
