use crate::{storage, OfficeError, SlideItem, SlideLayout};
use quick_xml::events::Event;
use quick_xml::Reader;
use std::fs::File;
use std::io::{Cursor, Read, Write};
use zip::write::SimpleFileOptions;
use zip::{CompressionMethod, ZipArchive, ZipWriter};

fn xml_escape(input: &str) -> String {
    input
        .replace('&', "&amp;")
        .replace('<', "&lt;")
        .replace('>', "&gt;")
        .replace('"', "&quot;")
        .replace('\'', "&apos;")
}

/// Đọc định dạng .slides cục bộ nhẹ (phân tách từng slide bằng dòng `===`).
pub fn parse_deck_text(text: &str) -> Vec<SlideItem> {
    let mut slides = Vec::new();
    for raw_block in text.split("\n===\n") {
        let block = raw_block.trim();
        if block.is_empty() {
            continue;
        }
        let mut title = String::new();
        let mut layout = SlideLayout::TitleBody;
        let mut notes = String::new();
        let mut left_lines = Vec::new();
        let mut right_lines = Vec::new();
        let mut in_right = false;

        for line in block.lines() {
            let trimmed = line.trim();
            if let Some(t) = trimmed.strip_prefix("# ") {
                if title.is_empty() {
                    title = t.trim().to_string();
                    continue;
                }
            }
            if let Some(lv) = trimmed.strip_prefix("@layout:") {
                layout = match lv.trim().to_ascii_lowercase().as_str() {
                    "cover" => SlideLayout::Cover,
                    "two_col" | "twocolumn" => SlideLayout::TwoColumn,
                    _ => SlideLayout::TitleBody,
                };
                continue;
            }
            if let Some(n) = trimmed.strip_prefix("@notes:") {
                notes = n.trim().to_string();
                continue;
            }
            if trimmed == "@right" {
                in_right = true;
                continue;
            }
            if in_right {
                right_lines.push(line);
            } else {
                left_lines.push(line);
            }
        }

        slides.push(SlideItem {
            title,
            body_left: left_lines.join("\n").trim().to_string(),
            body_right: right_lines.join("\n").trim().to_string(),
            notes,
            layout,
        });
    }

    if slides.is_empty() {
        slides.push(SlideItem {
            title: "Trang bìa trình chiếu".to_string(),
            body_left: "Phụ đề hoặc tên người trình bày".to_string(),
            body_right: String::new(),
            notes: String::new(),
            layout: SlideLayout::Cover,
        });
    }
    slides
}

pub fn serialize_deck_text(items: &[SlideItem]) -> String {
    items
        .iter()
        .map(|s| {
            let layout_str = match s.layout {
                SlideLayout::Cover => "cover",
                SlideLayout::TitleBody => "body",
                SlideLayout::TwoColumn => "two_col",
            };
            let mut out = format!("# {}\n@layout: {}\n", s.title, layout_str);
            if !s.notes.trim().is_empty() {
                out.push_str(&format!("@notes: {}\n", s.notes.replace('\n', " ")));
            }
            if !s.body_left.trim().is_empty() {
                out.push_str(&s.body_left);
                out.push('\n');
            }
            if s.layout == SlideLayout::TwoColumn && !s.body_right.trim().is_empty() {
                out.push_str("@right\n");
                out.push_str(&s.body_right);
                out.push('\n');
            }
            out.trim_end().to_string()
        })
        .collect::<Vec<_>>()
        .join("\n===\n")
}

fn build_text_box_xml(
    id: u32,
    name: &str,
    x: i64,
    y: i64,
    cx: i64,
    cy: i64,
    text: &str,
    font_size_hundredths: u32,
    bold: bool,
    align: &str,
) -> String {
    let bold_attr = if bold { " b=\"1\"" } else { "" };
    let mut paragraphs = String::new();
    let lines: Vec<&str> = if text.is_empty() {
        vec![""]
    } else {
        text.lines().collect()
    };

    for line in lines {
        paragraphs.push_str(&format!(
            r#"<a:p><a:pPr algn="{align}"/><a:r><a:rPr lang="vi-VN" sz="{font_size_hundredths}"{bold_attr} dirty="0"/><a:t>{}</a:t></a:r></a:p>"#,
            xml_escape(line)
        ));
    }

    format!(
        r#"<p:sp>            <p:nvSpPr>                <p:cNvPr id="{id}" name="{name}"/>                <p:cNvSpPr txBox="1"/>                <p:nvPr/>            </p:nvSpPr>            <p:spPr>                <a:xfrm><a:off x="{x}" y="{y}"/><a:ext cx="{cx}" cy="{cy}"/></a:xfrm>                <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>                <a:noFill/>            </p:spPr>            <p:txBody>                <a:bodyPr wrap="square" rtlCol="0"/>                <a:lstStyle/>                {paragraphs}            </p:txBody>        </p:sp>"#
    )
}

fn build_slide_xml(item: &SlideItem) -> String {
    // Khổ widescreen 16:9 chuẩn PowerPoint: 12192000 x 6858000 EMU
    let shapes = match item.layout {
        SlideLayout::Cover => {
            let title_box = build_text_box_xml(
                2, "Title", 914400, 2100000, 10363200, 1400000, &item.title, 4400, true, "ctr",
            );
            let sub_box = build_text_box_xml(
                3, "Subtitle", 1371600, 3700000, 9448800, 1200000, &item.body_left, 2200, false, "ctr",
            );
            format!("{title_box}{sub_box}")
        }
        SlideLayout::TitleBody => {
            let title_box = build_text_box_xml(
                2, "Title", 685800, 457200, 10820400, 914400, &item.title, 3200, true, "l",
            );
            let body_box = build_text_box_xml(
                3, "Body", 685800, 1554480, 10820400, 4600000, &item.body_left, 2000, false, "l",
            );
            format!("{title_box}{body_box}")
        }
        SlideLayout::TwoColumn => {
            let title_box = build_text_box_xml(
                2, "Title", 685800, 457200, 10820400, 914400, &item.title, 3200, true, "l",
            );
            let left_box = build_text_box_xml(
                3, "LeftCol", 685800, 1554480, 5181600, 4600000, &item.body_left, 1800, false, "l",
            );
            let right_box = build_text_box_xml(
                4, "RightCol", 6324600, 1554480, 5181600, 4600000, &item.body_right, 1800, false, "l",
            );
            format!("{title_box}{left_box}{right_box}")
        }
    };

    format!(
        r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>        <p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"                xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"                xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">          <p:cSld>            <p:spTree>              <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>              <p:grpSpPr><a:xfrm><a:off x="0" y="0"/><a:ext cx="0" cy="0"/><a:chOff x="0" y="0"/><a:chExt cx="0" cy="0"/></a:xfrm></p:grpSpPr>              {shapes}            </p:spTree>          </p:cSld>          <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>        </p:sld>"#
    )
}

/// Xuất bộ slide sang tệp .pptx 16:9 hợp lệ (ECMA-376 OOXML) và lưu atomic.
pub fn export_pptx(items: &[SlideItem], output_path: &str) -> Result<u64, OfficeError> {
    let mut cursor = Cursor::new(Vec::new());
    {
        let mut zip = ZipWriter::new(&mut cursor);
        let opts = SimpleFileOptions::default().compression_method(CompressionMethod::Deflated);

        let map_err = |e: zip::result::ZipError| OfficeError::Export {
            detail: e.to_string(),
        };
        let io_err = |e: std::io::Error| OfficeError::Export {
            detail: e.to_string(),
        };

        let mut content_overrides = String::new();
        let mut pres_rels = String::new();
        let mut sld_id_list = String::new();

        for idx in 0..items.len() {
            let n = idx + 1;
            let rid = format!("rId{}", n + 1);
            content_overrides.push_str(&format!(
                r#"<Override PartName="/ppt/slides/slide{n}.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slide+xml"/>"#
            ));
            pres_rels.push_str(&format!(
                r#"<Relationship Id="{rid}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide" Target="slides/slide{n}.xml"/>"#
            ));
            sld_id_list.push_str(&format!(r#"<p:sldId id="{}" r:id="{rid}"/>"#, 256 + idx));
        }

        let content_types = format!(
            r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">              <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>              <Default Extension="xml" ContentType="application/xml"/>              <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>              <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>              <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>              {content_overrides}            </Types>"#
        );
        zip.start_file("[Content_Types].xml", opts).map_err(map_err)?;
        zip.write_all(content_types.as_bytes()).map_err(io_err)?;

        let root_rels = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>            </Relationships>"#;
        zip.start_file("_rels/.rels", opts).map_err(map_err)?;
        zip.write_all(root_rels.as_bytes()).map_err(io_err)?;

        let presentation_xml = format!(
            r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"                             xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"                             xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">              <p:sldMasterIdLst><p:sldMasterId id="2147483648" r:id="rId1"/></p:sldMasterIdLst>              <p:sldIdLst>{sld_id_list}</p:sldIdLst>              <p:sldSz cx="12192000" cy="6858000"/>              <p:notesSz cx="6858000" cy="9144000"/>            </p:presentation>"#
        );
        zip.start_file("ppt/presentation.xml", opts).map_err(map_err)?;
        zip.write_all(presentation_xml.as_bytes()).map_err(io_err)?;

        let presentation_rels = format!(
            r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>              {pres_rels}            </Relationships>"#
        );
        zip.start_file("ppt/_rels/presentation.xml.rels", opts).map_err(map_err)?;
        zip.write_all(presentation_rels.as_bytes()).map_err(io_err)?;

        let slide_master = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"                          xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main">              <p:cSld><p:spTree>                <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>                <p:grpSpPr/>              </p:spTree></p:cSld>              <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2" accent1="accent1" accent2="accent2" accent3="accent3" accent4="accent4" accent5="accent5" accent6="accent6" hlink="hlink" folHlink="folHlink"/>              <p:sldLayoutIdLst><p:sldLayoutId id="2147483649" r:id="rId1"/></p:sldLayoutIdLst>            </p:sldMaster>"#;
        zip.start_file("ppt/slideMasters/slideMaster1.xml", opts).map_err(map_err)?;
        zip.write_all(slide_master.as_bytes()).map_err(io_err)?;

        let master_rels = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>            </Relationships>"#;
        zip.start_file("ppt/slideMasters/_rels/slideMaster1.xml.rels", opts).map_err(map_err)?;
        zip.write_all(master_rels.as_bytes()).map_err(io_err)?;

        let slide_layout = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"                          xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"                          xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" type="blank">              <p:cSld><p:spTree>                <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>                <p:grpSpPr/>              </p:spTree></p:cSld>              <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>            </p:sldLayout>"#;
        zip.start_file("ppt/slideLayouts/slideLayout1.xml", opts).map_err(map_err)?;
        zip.write_all(slide_layout.as_bytes()).map_err(io_err)?;

        let layout_rels = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>            </Relationships>"#;
        zip.start_file("ppt/slideLayouts/_rels/slideLayout1.xml.rels", opts).map_err(map_err)?;
        zip.write_all(layout_rels.as_bytes()).map_err(io_err)?;

        let slide_rels = r#"<?xml version="1.0" encoding="UTF-8" standalone="yes"?>            <Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">              <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>            </Relationships>"#;

        for (idx, item) in items.iter().enumerate() {
            let n = idx + 1;
            zip.start_file(format!("ppt/slides/slide{n}.xml"), opts).map_err(map_err)?;
            zip.write_all(build_slide_xml(item).as_bytes()).map_err(io_err)?;

            zip.start_file(format!("ppt/slides/_rels/slide{n}.xml.rels"), opts).map_err(map_err)?;
            zip.write_all(slide_rels.as_bytes()).map_err(io_err)?;
        }

        zip.finish().map_err(map_err)?;
    }

    let bytes = cursor.into_inner();
    storage::atomic_write_bytes(output_path, &bytes)
}

fn extract_slide_text_blocks(xml: &str) -> Vec<String> {
    let mut reader = Reader::from_str(xml);
    let mut blocks = Vec::new();
    let mut current_shape_lines: Vec<String> = Vec::new();
    let mut current_para = String::new();
    let mut in_text = false;
    let mut buf = Vec::new();

    loop {
        match reader.read_event_into(&mut buf) {
            Ok(Event::Start(ref e)) => {
                let name = e.name();
                if name.as_ref().ends_with(b":t") || name.as_ref() == b"t" {
                    in_text = true;
                }
            }
            Ok(Event::Text(ref e)) if in_text => {
                if let Ok(t) = e.unescape() {
                    current_para.push_str(&t);
                }
            }
            Ok(Event::End(ref e)) => {
                let name = e.name();
                if name.as_ref().ends_with(b":t") || name.as_ref() == b"t" {
                    in_text = false;
                } else if name.as_ref().ends_with(b":p") || name.as_ref() == b"p" {
                    let trimmed = current_para.trim().to_string();
                    if !trimmed.is_empty() {
                        current_shape_lines.push(trimmed);
                    }
                    current_para.clear();
                } else if name.as_ref().ends_with(b":sp") || name.as_ref() == b"sp" {
                    if !current_shape_lines.is_empty() {
                        blocks.push(current_shape_lines.join("\n"));
                        current_shape_lines.clear();
                    }
                }
            }
            Ok(Event::Eof) => break,
            Err(_) => break,
            _ => {}
        }
        buf.clear();
    }

    blocks
}

/// Đọc và trích xuất danh sách slide từ tệp .pptx có sẵn.
pub fn read_pptx(path: &str) -> Result<Vec<SlideItem>, OfficeError> {
    let file = File::open(path).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;
    let mut archive = ZipArchive::new(file).map_err(|e| OfficeError::Parse {
        detail: e.to_string(),
    })?;

    let mut slide_entries: Vec<(u32, String)> = Vec::new();
    for i in 0..archive.len() {
        if let Ok(f) = archive.by_index(i) {
            let name = f.name().to_string();
            if let Some(rest) = name.strip_prefix("ppt/slides/slide") {
                if let Some(num_str) = rest.strip_suffix(".xml") {
                    if let Ok(num) = num_str.parse::<u32>() {
                        slide_entries.push((num, name));
                    }
                }
            }
        }
    }
    slide_entries.sort_by_key(|(num, _)| *num);

    let mut items = Vec::new();
    for (idx, (_, entry_name)) in slide_entries.into_iter().enumerate() {
        let mut f = archive.by_name(&entry_name).map_err(|e| OfficeError::Parse {
            detail: e.to_string(),
        })?;
        let mut xml = String::new();
        f.read_to_string(&mut xml).map_err(|e| OfficeError::Io {
            detail: e.to_string(),
        })?;

        let blocks = extract_slide_text_blocks(&xml);
        let title = blocks.first().cloned().unwrap_or_else(|| format!("Slide {}", idx + 1));
        let body_left = blocks.get(1).cloned().unwrap_or_default();
        let body_right = blocks.get(2).cloned().unwrap_or_default();
        let layout = if !body_right.is_empty() {
            SlideLayout::TwoColumn
        } else if idx == 0 {
            SlideLayout::Cover
        } else {
            SlideLayout::TitleBody
        };

        items.push(SlideItem {
            title,
            body_left,
            body_right,
            notes: String::new(),
            layout,
        });
    }

    Ok(items)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn test_slides_roundtrip_pptx() {
        let deck = vec![
            SlideItem {
                title: "Báo cáo Quý 4".to_string(),
                body_left: "Phòng Giải pháp CNTT".to_string(),
                body_right: String::new(),
                notes: "Giới thiệu tổng quan".to_string(),
                layout: SlideLayout::Cover,
            },
            SlideItem {
                title: "So sánh kiến trúc".to_string(),
                body_left: "Rust Core: nhanh, nhẹ".to_string(),
                body_right: "Compose UI: mượt 90Hz".to_string(),
                notes: String::new(),
                layout: SlideLayout::TwoColumn,
            },
        ];

        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("mini_office_deck_{}.pptx", nanos));
        let path_str = path.to_string_lossy().to_string();

        let bytes = export_pptx(&deck, &path_str).unwrap();
        assert!(bytes > 200);

        let loaded = read_pptx(&path_str).unwrap();
        assert_eq!(loaded.len(), 2);
        assert_eq!(loaded[0].title, "Báo cáo Quý 4");
        assert_eq!(loaded[1].layout, SlideLayout::TwoColumn);
        assert_eq!(loaded[1].body_right, "Compose UI: mượt 90Hz");

        let _ = fs::remove_file(&path);
    }
}
