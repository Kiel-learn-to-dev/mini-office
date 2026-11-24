use crate::{storage, OfficeError, SheetCell, SheetData, SheetSummary};
use calamine::{open_workbook_auto, Data, Reader};
use rust_xlsxwriter::Workbook;
use std::collections::{HashMap, HashSet};

fn format_number(val: f64) -> String {
    if val.is_nan() || val.is_infinite() {
        return "#ERR!".to_string();
    }
    if (val - val.round()).abs() < 1e-9 && val.abs() < 1e15 {
        format!("{}", val.round() as i64)
    } else {
        let s = format!("{:.6}", val);
        s.trim_end_matches('0').trim_end_matches('.').to_string()
    }
}

/// Chuyển chỉ số cột (0 -> "A", 25 -> "Z", 26 -> "AA").
pub fn col_to_letters(mut col: u32) -> String {
    let mut out = String::new();
    loop {
        let rem = (col % 26) as u8;
        out.insert(0, (b'A' + rem) as char);
        if col < 26 {
            break;
        }
        col = col / 26 - 1;
    }
    out
}

/// Phân tích tham chiếu ô kiểu "A1", "BC12" -> (row_0_based, col_0_based).
fn parse_cell_ref(token: &str) -> Option<(u32, u32)> {
    let t = token.trim().trim_start_matches('$');
    let mut col: u32 = 0;
    let mut split_idx = 0;
    for (i, ch) in t.char_indices() {
        if ch.is_ascii_alphabetic() {
            col = col
                .checked_mul(26)?
                .checked_add((ch.to_ascii_uppercase() as u32) - ('A' as u32) + 1)?;
            split_idx = i + ch.len_utf8();
        } else {
            break;
        }
    }
    if split_idx == 0 {
        return None;
    }
    let row_part = t[split_idx..].trim_start_matches('$');
    let row_1: u32 = row_part.parse().ok()?;
    if row_1 == 0 {
        return None;
    }
    Some((row_1 - 1, col - 1))
}

fn expand_range(arg: &str) -> Option<Vec<(u32, u32)>> {
    let (a, b) = arg.split_once(':')?;
    let (r1, c1) = parse_cell_ref(a)?;
    let (r2, c2) = parse_cell_ref(b)?;
    let (r_min, r_max) = (r1.min(r2), r1.max(r2));
    let (c_min, c_max) = (c1.min(c2), c1.max(c2));
    let mut coords = Vec::new();
    for r in r_min..=r_max {
        for c in c_min..=c_max {
            coords.push((r, c));
        }
    }
    Some(coords)
}

fn split_top_level_args(s: &str) -> Vec<String> {
    let mut parts = Vec::new();
    let mut depth = 0i32;
    let mut start = 0usize;
    for (i, ch) in s.char_indices() {
        match ch {
            '(' => depth += 1,
            ')' => depth -= 1,
            ',' if depth == 0 => {
                parts.push(s[start..i].trim().to_string());
                start = i + 1;
            }
            _ => {}
        }
    }
    let tail = s[start..].trim();
    if !tail.is_empty() {
        parts.push(tail.to_string());
    }
    parts
}

struct EvalContext<'a> {
    raw_map: &'a HashMap<(u32, u32), String>,
    cache: HashMap<(u32, u32), Result<f64, String>>,
    visiting: HashSet<(u32, u32)>,
}

impl<'a> EvalContext<'a> {
    fn eval_cell_num(&mut self, coord: (u32, u32)) -> Result<f64, String> {
        if let Some(cached) = self.cache.get(&coord) {
            return cached.clone();
        }
        if !self.visiting.insert(coord) {
            return Err("#REF!".to_string());
        }
        let raw = self.raw_map.get(&coord).map(|s| s.trim()).unwrap_or("");
        let res = if raw.is_empty() {
            Ok(0.0)
        } else if let Some(expr) = raw.strip_prefix('=') {
            self.eval_expr(expr)
        } else {
            raw.replace(',', "").parse::<f64>().map_err(|_| "#VALUE!".to_string())
        };
        self.visiting.remove(&coord);
        self.cache.insert(coord, res.clone());
        res
    }

    fn eval_cell_display(&mut self, coord: (u32, u32)) -> String {
        let raw = self.raw_map.get(&coord).map(|s| s.as_str()).unwrap_or("");
        if let Some(expr) = raw.trim().strip_prefix('=') {
            match self.eval_expr(expr) {
                Ok(v) => format_number(v),
                Err(code) => code,
            }
        } else {
            raw.to_string()
        }
    }

    fn eval_expr(&mut self, expr: &str) -> Result<f64, String> {
        let s = expr.trim();
        if s.is_empty() {
            return Ok(0.0);
        }
        // Gỡ ngoặc bao ngoài nếu khớp cặp
        if s.starts_with('(') && s.ends_with(')') && is_wrapped_parens(s) {
            return self.eval_expr(&s[1..s.len() - 1]);
        }

        // Phép cộng / trừ ở mức ưu tiên thấp nhất (duyệt từ phải sang trái)
        if let Some((idx, op)) = find_top_level_op(s, &['+', '-']) {
            let left = self.eval_expr(&s[..idx])?;
            let right = self.eval_expr(&s[idx + 1..])?;
            return Ok(if op == '+' { left + right } else { left - right });
        }

        // Phép nhân / chia
        if let Some((idx, op)) = find_top_level_op(s, &['*', '/']) {
            let left = self.eval_expr(&s[..idx])?;
            let right = self.eval_expr(&s[idx + 1..])?;
            if op == '/' {
                if right.abs() < 1e-12 {
                    return Err("#DIV/0!".to_string());
                }
                return Ok(left / right);
            }
            return Ok(left * right);
        }

        // Hàm: SUM(...), AVERAGE(...), MIN(...), MAX(...), COUNT(...), IF(...)
        if let Some(open) = s.find('(') {
            if s.ends_with(')') {
                let func_name = s[..open].trim().to_ascii_uppercase();
                let inner = &s[open + 1..s.len() - 1];
                return self.eval_function(&func_name, inner);
            }
        }

        // Tham chiếu ô đơn (A1, C4...)
        if let Some(coord) = parse_cell_ref(s) {
            return self.eval_cell_num(coord);
        }

        // Số thuần
        s.parse::<f64>().map_err(|_| "#NAME?".to_string())
    }

    fn collect_numeric_args(&mut self, inner: &str) -> Result<Vec<f64>, String> {
        let mut vals = Vec::new();
        for arg in split_top_level_args(inner) {
            if arg.contains(':') {
                let coords = expand_range(&arg).ok_or_else(|| "#REF!".to_string())?;
                for c in coords {
                    if let Some(raw) = self.raw_map.get(&c) {
                        if !raw.trim().is_empty() {
                            if let Ok(n) = self.eval_cell_num(c) {
                                vals.push(n);
                            }
                        }
                    }
                }
            } else {
                vals.push(self.eval_expr(&arg)?);
            }
        }
        Ok(vals)
    }

    fn eval_function(&mut self, name: &str, inner: &str) -> Result<f64, String> {
        match name {
            "SUM" => Ok(self.collect_numeric_args(inner)?.into_iter().sum()),
            "AVERAGE" | "AVG" => {
                let vals = self.collect_numeric_args(inner)?;
                if vals.is_empty() {
                    Err("#DIV/0!".to_string())
                } else {
                    Ok(vals.iter().sum::<f64>() / vals.len() as f64)
                }
            }
            "MIN" => {
                let vals = self.collect_numeric_args(inner)?;
                Ok(vals.into_iter().fold(f64::INFINITY, f64::min))
            }
            "MAX" => {
                let vals = self.collect_numeric_args(inner)?;
                Ok(vals.into_iter().fold(f64::NEG_INFINITY, f64::max))
            }
            "COUNT" => Ok(self.collect_numeric_args(inner)?.len() as f64),
            "IF" => {
                let args = split_top_level_args(inner);
                if args.len() < 3 {
                    return Err("#VALUE!".to_string());
                }
                let cond = self.eval_condition(&args[0])?;
                if cond {
                    self.eval_expr(&args[1])
                } else {
                    self.eval_expr(&args[2])
                }
            }
            _ => Err("#NAME?".to_string()),
        }
    }

    fn eval_condition(&mut self, cond: &str) -> Result<bool, String> {
        for op in [">=", "<=", "!=", "<>", ">", "<", "="] {
            if let Some((l, r)) = cond.split_once(op) {
                let lv = self.eval_expr(l)?;
                let rv = self.eval_expr(r)?;
                return Ok(match op {
                    ">=" => lv >= rv,
                    "<=" => lv <= rv,
                    "!=" | "<>" => (lv - rv).abs() > 1e-9,
                    ">" => lv > rv,
                    "<" => lv < rv,
                    "=" => (lv - rv).abs() <= 1e-9,
                    _ => false,
                });
            }
        }
        Ok(self.eval_expr(cond)?.abs() > 1e-9)
    }
}

fn is_wrapped_parens(s: &str) -> bool {
    let mut depth = 0i32;
    for (i, ch) in s.char_indices() {
        match ch {
            '(' => depth += 1,
            ')' => {
                depth -= 1;
                if depth == 0 && i < s.len() - 1 {
                    return false;
                }
            }
            _ => {}
        }
    }
    depth == 0
}

fn find_top_level_op(s: &str, ops: &[char]) -> Option<(usize, char)> {
    let mut depth = 0i32;
    let chars: Vec<(usize, char)> = s.char_indices().collect();
    for idx in (0..chars.len()).rev() {
        let (byte_i, ch) = chars[idx];
        match ch {
            ')' => depth += 1,
            '(' => depth -= 1,
            _ if depth == 0 && ops.contains(&ch) => {
                // Bỏ qua dấu âm/dương đứng đầu biểu thức (unary)
                if idx == 0 {
                    continue;
                }
                let prev = chars[idx - 1].1;
                if ['+', '-', '*', '/', '(', ',', '=', '>', '<'].contains(&prev) {
                    continue;
                }
                return Some((byte_i, ch));
            }
            _ => {}
        }
    }
    None
}

pub fn evaluate_sheet(mut sheet: SheetData) -> SheetData {
    let mut raw_map = HashMap::new();
    for cell in &sheet.cells {
        raw_map.insert((cell.row, cell.col), cell.raw.clone());
    }
    let mut ctx = EvalContext {
        raw_map: &raw_map,
        cache: HashMap::new(),
        visiting: HashSet::new(),
    };
    for cell in &mut sheet.cells {
        cell.display = ctx.eval_cell_display((cell.row, cell.col));
    }
    sheet
}

fn parse_csv_line(line: &str) -> Vec<String> {
    let mut fields = Vec::new();
    let mut cur = String::new();
    let mut in_quotes = false;
    let mut chars = line.chars().peekable();

    while let Some(ch) = chars.next() {
        match ch {
            '"' => {
                if in_quotes && chars.peek() == Some(&'"') {
                    cur.push('"');
                    chars.next();
                } else {
                    in_quotes = !in_quotes;
                }
            }
            ',' if !in_quotes => {
                fields.push(cur.clone());
                cur.clear();
            }
            _ => cur.push(ch),
        }
    }
    fields.push(cur);
    fields
}

pub fn parse_csv(name: &str, csv_text: &str) -> SheetData {
    let mut cells = Vec::new();
    let mut max_row: u32 = 0;
    let mut max_col: u32 = 0;

    for (r_idx, line) in csv_text.lines().enumerate() {
        let r = r_idx as u32;
        max_row = max_row.max(r + 1);
        for (c_idx, field) in parse_csv_line(line).into_iter().enumerate() {
            let c = c_idx as u32;
            max_col = max_col.max(c + 1);
            if !field.is_empty() {
                cells.push(SheetCell {
                    row: r,
                    col: c,
                    raw: field.clone(),
                    display: field,
                });
            }
        }
    }

    evaluate_sheet(SheetData {
        name: name.to_string(),
        row_count: max_row.max(20),
        col_count: max_col.max(8),
        cells,
    })
}

pub fn to_csv(sheet: &SheetData) -> String {
    let mut map: HashMap<(u32, u32), &str> = HashMap::new();
    let mut last_r = 0u32;
    let mut last_c = 0u32;

    for cell in &sheet.cells {
        if !cell.raw.is_empty() {
            map.insert((cell.row, cell.col), cell.raw.as_str());
            last_r = last_r.max(cell.row + 1);
            last_c = last_c.max(cell.col + 1);
        }
    }

    let mut lines = Vec::new();
    for r in 0..last_r {
        let mut cols = Vec::new();
        for c in 0..last_c {
            let v = map.get(&(r, c)).copied().unwrap_or("");
            if v.contains(',') || v.contains('"') || v.contains('\n') {
                cols.push(format!("\"{}\"", v.replace('"', "\"\"")));
            } else {
                cols.push(v.to_string());
            }
        }
        lines.push(cols.join(","));
    }
    lines.join("\n")
}

pub fn read_xlsx(path: &str) -> Result<Vec<SheetData>, OfficeError> {
    let mut workbook = open_workbook_auto(path).map_err(|e| OfficeError::Parse {
        detail: e.to_string(),
    })?;

    let mut result = Vec::new();
    let names = workbook.sheet_names().to_vec();

    for name in names {
        if let Ok(range) = workbook.worksheet_range(&name) {
            let mut cells = Vec::new();
            let (rows, cols) = range.get_size();
            for (r, row_vals) in range.rows().enumerate() {
                for (c, val) in row_vals.iter().enumerate() {
                    let raw = match val {
                        Data::Empty => continue,
                        Data::String(s) => s.clone(),
                        Data::Float(f) => format_number(*f),
                        Data::Int(i) => i.to_string(),
                        Data::Bool(b) => b.to_string(),
                        Data::DateTime(dt) => format!("{}", dt),
                        other => format!("{}", other),
                    };
                    cells.push(SheetCell {
                        row: r as u32,
                        col: c as u32,
                        raw: raw.clone(),
                        display: raw,
                    });
                }
            }
            result.push(evaluate_sheet(SheetData {
                name,
                row_count: (rows as u32).max(20),
                col_count: (cols as u32).max(8),
                cells,
            }));
        }
    }

    Ok(result)
}

pub fn export_xlsx(sheets_list: &[SheetData], output_path: &str) -> Result<u64, OfficeError> {
    let mut workbook = Workbook::new();

    for sheet in sheets_list {
        let ws = workbook.add_worksheet();
        let _ = ws.set_name(&sheet.name);

        for cell in &sheet.cells {
            let trimmed = cell.raw.trim();
            if trimmed.is_empty() {
                continue;
            }
            let r = cell.row;
            let c = cell.col as u16;

            if trimmed.starts_with('=') {
                ws.write_formula(r, c, trimmed).map_err(|e| OfficeError::Export {
                    detail: e.to_string(),
                })?;
            } else if let Ok(num) = trimmed.parse::<f64>() {
                ws.write_number(r, c, num).map_err(|e| OfficeError::Export {
                    detail: e.to_string(),
                })?;
            } else {
                ws.write_string(r, c, trimmed).map_err(|e| OfficeError::Export {
                    detail: e.to_string(),
                })?;
            }
        }
    }

    let bytes = workbook.save_to_buffer().map_err(|e| OfficeError::Export {
        detail: e.to_string(),
    })?;
    storage::atomic_write_bytes(output_path, &bytes)
}

pub fn summarize_numbers(values: &[String]) -> SheetSummary {
    let mut sum = 0.0;
    let mut count = 0u32;
    for v in values {
        if let Ok(n) = v.trim().replace(',', "").parse::<f64>() {
            sum += n;
            count += 1;
        }
    }
    let avg = if count > 0 { sum / count as f64 } else { 0.0 };
    SheetSummary { sum, avg, count }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::fs;
    use std::time::{SystemTime, UNIX_EPOCH};

    #[test]
    fn test_formula_evaluation_and_cycle_guard() {
        let csv = "HangMuc,SL,DonGia,ThanhTien
MayChu,2,8500000,=B2*C2
BanQuyen,1,6000000,=B3*C3
Tong,=SUM(B2:B3),,=SUM(D2:D3)
KiemTra,,,\"=IF(D4>20000000,1,0)\"";
        let sheet = parse_csv("BaoGia", csv);

        let find = |r: u32, c: u32| -> String {
            sheet
                .cells
                .iter()
                .find(|x| x.row == r && x.col == c)
                .map(|x| x.display.clone())
                .unwrap_or_default()
        };

        assert_eq!(find(1, 3), "17000000");
        assert_eq!(find(2, 3), "6000000");
        assert_eq!(find(3, 1), "3");
        assert_eq!(find(3, 3), "23000000");
        assert_eq!(find(4, 3), "1");

        // Kiểm tra chống vòng lặp tham chiếu
        let cyclic = parse_csv("Cycle", "=B1,=A1");
        assert_eq!(cyclic.cells[0].display, "#REF!");
    }

    #[test]
    fn test_xlsx_export_and_read_back() {
        let sheet = parse_csv("ChiPhi", "Muc,SoTien
A,100
B,250
Tong,=SUM(B2:B3)");
        let nanos = SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_nanos();
        let path = std::env::temp_dir().join(format!("mini_office_sheet_{}.xlsx", nanos));
        let path_str = path.to_string_lossy().to_string();

        let bytes = export_xlsx(&[sheet], &path_str).unwrap();
        assert!(bytes > 100);

        let loaded = read_xlsx(&path_str).unwrap();
        assert_eq!(loaded.len(), 1);
        assert_eq!(loaded[0].name, "ChiPhi");
        let _ = fs::remove_file(&path);
    }
}
