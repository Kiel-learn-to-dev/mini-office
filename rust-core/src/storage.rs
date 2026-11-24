use crate::{FileEntry, FileKind, OfficeError};
use std::fs::{self, File};
use std::io::Write;
use std::path::Path;
use std::time::UNIX_EPOCH;

fn classify_kind(path: &Path) -> FileKind {
    match path
        .extension()
        .and_then(|ext| ext.to_str())
        .map(|s| s.to_ascii_lowercase())
        .as_deref()
    {
        Some("md" | "txt" | "docx") => FileKind::Docs,
        Some("csv" | "xlsx" | "xls" | "ods") => FileKind::Sheets,
        Some("pptx" | "slides") => FileKind::Slides,
        _ => FileKind::Other,
    }
}

pub fn atomic_write_bytes(path: &str, data: &[u8]) -> Result<u64, OfficeError> {
    let target = Path::new(path);
    let parent = target.parent().ok_or(OfficeError::InvalidPath)?;
    fs::create_dir_all(parent).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;

    let file_name = target
        .file_name()
        .and_then(|n| n.to_str())
        .ok_or(OfficeError::InvalidPath)?;
    let temp_path = parent.join(format!(".{}.tmp", file_name));

    let mut file = File::create(&temp_path).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;
    file.write_all(data).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;
    file.sync_all().map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;

    fs::rename(&temp_path, target).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;

    Ok(data.len() as u64)
}

pub fn read_text_file(path: &str) -> Result<String, OfficeError> {
    let target = Path::new(path);
    if !target.exists() {
        return Err(OfficeError::NotFound {
            path: path.to_string(),
        });
    }
    fs::read_to_string(target).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })
}

pub fn list_workspace_files(dir_path: &str) -> Result<Vec<FileEntry>, OfficeError> {
    let dir = Path::new(dir_path);
    if !dir.exists() {
        fs::create_dir_all(dir).map_err(|e| OfficeError::Io {
            detail: e.to_string(),
        })?;
        return Ok(Vec::new());
    }

    let mut entries = Vec::new();
    let read_dir = fs::read_dir(dir).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;

    for item in read_dir {
        let item = item.map_err(|e| OfficeError::Io {
            detail: e.to_string(),
        })?;
        let path = item.path();
        if !path.is_file() {
            continue;
        }
        let name = match path.file_name().and_then(|n| n.to_str()) {
            Some(n) if !n.starts_with('.') => n.to_string(),
            _ => continue,
        };
        let meta = item.metadata().map_err(|e| OfficeError::Io {
            detail: e.to_string(),
        })?;
        let modified_epoch_secs = meta
            .modified()
            .ok()
            .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
            .map(|d| d.as_secs())
            .unwrap_or(0);

        entries.push(FileEntry {
            name,
            path: path.to_string_lossy().to_string(),
            kind: classify_kind(&path),
            size_bytes: meta.len(),
            modified_epoch_secs,
        });
    }

    entries.sort_by(|a, b| b.modified_epoch_secs.cmp(&a.modified_epoch_secs));
    Ok(entries)
}

pub fn rename_file(old_path: &str, new_name: &str) -> Result<String, OfficeError> {
    let trimmed = new_name.trim();
    if trimmed.is_empty() || trimmed.contains('/') || trimmed.contains('\\') {
        return Err(OfficeError::InvalidPath);
    }
    let old = Path::new(old_path);
    if !old.exists() {
        return Err(OfficeError::NotFound {
            path: old_path.to_string(),
        });
    }
    let parent = old.parent().ok_or(OfficeError::InvalidPath)?;
    let new_path = parent.join(trimmed);
    fs::rename(old, &new_path).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })?;
    Ok(new_path.to_string_lossy().to_string())
}

pub fn delete_file(path: &str) -> Result<(), OfficeError> {
    let target = Path::new(path);
    if !target.exists() {
        return Err(OfficeError::NotFound {
            path: path.to_string(),
        });
    }
    fs::remove_file(target).map_err(|e| OfficeError::Io {
        detail: e.to_string(),
    })
}
