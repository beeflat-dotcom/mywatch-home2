# coding: utf-8
"""Read-only human-view workbook from authorized child-master CSV.
No access to master JSON, attendance JSON, or the original address book.
"""
from __future__ import annotations

import argparse
import csv
from pathlib import Path

from openpyxl import Workbook, load_workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.page import PageMargins

SHEET_NAME = "最新住所録（自動）"
WIDTHS = [7, 11, 12, 24, 14, 14, 22, 14, 13, 42,
          18, 19, 22, 17, 20, 14, 11, 14, 14, 42]
HEADINGS = [
    "No", "店舗", "利用状態", "児童氏名", "苗字", "児童名", "保護者名", "略称",
    "郵便番号", "住所", "建物名", "電話番号", "学校名", "生年月日",
    "受給者番号", "利用希望No", "負担率", "デイ契約日数", "日中契約日数", "児童ID",
]

def text_cell(value: str) -> str:
    value = str(value or "")
    return "'" + value if value and value[0] in "=+-@" else value

def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--csv", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--source", required=True)
    ap.add_argument("--timestamp", required=True)
    args = ap.parse_args()

    with args.csv.open("r", encoding="utf-8-sig", newline="") as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != HEADINGS:
            raise ValueError("Unexpected address-book CSV header")
        people = list(reader)
    if not 1 <= len(people) <= 2000:
        raise ValueError("Address-book person count invalid")
    ids = [v["児童ID"] for v in people]
    nos = [v["No"] for v in people]
    if any(not v for v in ids + nos) or len(set(ids)) != len(ids) or len(set(nos)) != len(nos):
        raise ValueError("Person IDs or numbers invalid or duplicated")

    wb = Workbook()
    ws = wb.active
    ws.title = SHEET_NAME
    ws.sheet_view.showGridLines = False
    ws.merge_cells("A1:T1")
    ws["A1"] = "びーふらっと　利用者住所録"
    ws["A1"].font = Font(name="Yu Gothic", size=17, bold=True, color="FFFFFF")
    ws["A1"].fill = PatternFill("solid", fgColor="153555")
    ws["A1"].alignment = Alignment(vertical="center")
    ws.row_dimensions[1].height = 39
    ws.merge_cells("A2:T2")
    ws["A2"] = f"更新: {args.timestamp}　参照マスタ: {args.source}　総数: {len(people)}"
    ws["A2"].font = Font(name="Yu Gothic", size=11, color="475569")
    ws.row_dimensions[2].height = 23
    ws.merge_cells("A3:T3")
    ws["A3"] = ("閲覧専用：このExcelを変更しても利用者マスタに反映されません。"
                "未登録の旧連絡先などは旧「名簿ろく」を表示用に参照しています。")
    ws["A3"].font = Font(name="Yu Gothic", size=10, color="92400E")
    ws.row_dimensions[3].height = 23
    header_fill = PatternFill("solid", fgColor="164E63")
    for col, h in enumerate(HEADINGS, start=1):
        cell = ws.cell(5, col, h)
        cell.font = Font(name="Yu Gothic", size=10, bold=True, color="FFFFFF")
        cell.fill = header_fill
        cell.alignment = Alignment(vertical="center", horizontal="center", wrap_text=True)
        ws.column_dimensions[get_column_letter(col)].width = WIDTHS[col - 1]
    ws.row_dimensions[5].height = 28

    inactive_fill = PatternFill("solid", fgColor="F3F4F6")
    normal_fill = PatternFill("solid", fgColor="FFFFFF")
    alternate_fill = PatternFill("solid", fgColor="F0F9FF")
    for index, person in enumerate(people, start=6):
        is_inactive = person["利用状態"] == "停止"
        for col, key in enumerate(HEADINGS, start=1):
            raw = person[key]
            cell = ws.cell(index, col, text_cell(raw))
            cell.font = Font(name="Yu Gothic", size=10, color="475569" if is_inactive else "0F172A")
            cell.alignment = Alignment(vertical="center", wrap_text=False)
            cell.fill = inactive_fill if is_inactive else alternate_fill if index % 2 == 0 else normal_fill
            if key in {"No", "利用希望No", "デイ契約日数", "日中契約日数", "負担率"}:
                if raw and not raw.startswith(("=", "+", "-", "@")):
                    try:
                        num = float(raw)
                        cell.value = int(num) if num.is_integer() and key != "負担率" else num
                    except ValueError:
                        pass
            if key in {"郵便番号", "電話番号", "受給者番号", "児童ID"}:
                cell.number_format = "@"
        ws.row_dimensions[index].height = 23

    ws.column_dimensions["T"].hidden = True
    ws.freeze_panes = "D6"
    ws.auto_filter.ref = f"A5:T{len(people) + 5}"
    ws.sheet_properties.pageSetUpPr.fitToPage = True
    ws.page_setup.orientation = "landscape"
    ws.page_setup.paperSize = ws.PAPERSIZE_A3
    ws.page_setup.fitToWidth = 1
    ws.page_setup.fitToHeight = 0
    ws.print_title_rows = "1:5"
    ws.page_margins = PageMargins(left=0.20, right=0.20, top=0.35, bottom=0.35, header=0.10, footer=0.10)
    ws.print_options.horizontalCentered = False
    wb.active = 0
    args.out.parent.mkdir(parents=True, exist_ok=True)
    wb.save(args.out)
    verify = load_workbook(args.out, read_only=True, data_only=False)
    try:
        if verify.sheetnames != [SHEET_NAME] or verify.worksheets[0].max_row != len(people) + 5:
            raise RuntimeError("Output workbook verification failed")
    finally:
        verify.close()
    print(f"VIEWER_SHEET_READY count={len(people)}", flush=True)

if __name__ == "__main__":
    main()
