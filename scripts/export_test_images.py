#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Batch OCR processor using Office Studio AI MCP server (HTTP Streamable endpoint).
Calls ocr_map_export via JSON-RPC 2.0.
"""

import json
import os
import sys
import urllib.request
from pathlib import Path

if sys.platform == "win32":
    try:
        sys.stdout.reconfigure(encoding="utf-8")
        sys.stderr.reconfigure(encoding="utf-8")
    except Exception:
        pass

MCP_URL = "http://127.0.0.1:8765/mcp"
BASE_DIR = Path(r"D:\Dev\Repositories\EX_DOCS")
PICS_DIR = BASE_DIR / "test_picture_ocr"
RESULT_DIR = PICS_DIR / "result"
RESULT_DIR.mkdir(parents=True, exist_ok=True)

ITEMS = [
    {
        "filename": "793257009_1041707375332388_5440460153414910729_n.jpg",
        "output_format": "word",
        "output_name": "793257009_dang_ky_tai_khoan_bidv.docx",
        "document_type": "form",
        "vision_fields": {
            "title": "ĐĂNG KÝ MỞ TÀI KHOẢN THANH TOÁN & THÔNG TIN FATCA",
            "document_number": "BM01-TC/TTKH&DVTK/02/2025",
            "issuing_authority": "Ngân hàng TMCP Đầu tư và Phát triển Việt Nam (BIDV)",
            "page": "Trang số: 3",
            "fatca_status": "Không hoạt động tại Mỹ; Không là định chế tài chính ngoài Mỹ; Không có nhà đầu tư Mỹ",
            "account_type": "Tài khoản thanh toán thông thường (VND)",
            "statement_frequency": "Tuần",
            "statement_format": "Excel",
            "delivery_address": "Qua bưu điện: Tầng 2, Số 6 Lê Văn Thiêm, Phường Thanh Xuân, TP Hà Nội"
        },
        "vision_text": """Mã hiệu: BM01-TC/TTKH&DVTK/02/2025
Trang số: 3

[ ] Có (Vui lòng kê khai thông tin tại Phụ lục III/TTKH&DVTK. Trường hợp Tổ chức là bên nhận ủy thác, đề nghị cung cấp văn bản ủy thác và thông tin nhận dạng của bên ủy thác, người thụ hưởng, các bên liên quan (nếu có), và cá nhân có quyền kiểm soát cuối cùng đối với ủy thác)
[ ] Không

IV. THÔNG TIN FATCA
1. Tổ chức được thành lập hay có tổ chức hoạt động tại Mỹ hay không?
[X] Không
2. Tổ chức có được xem như một Định chế tài chính ngoài Mỹ theo quy định của FATCA hay không (ví dụ: Ngân hàng, Ngân hàng giám hộ, công ty chuyên đầu tư, môi giới đầu tư, tư vấn đầu tư, quỹ hoặc phương tiện để đầu tư, công ty bảo hiểm, công ty holding (giữ vốn đầu tư trong các công ty khác)?
[X] Không
Có - Mã GIIN:
(Trong trường hợp Tổ chức không có mã số GIIN, Vui lòng cung cấp Mẫu W-8BEN-E)
3. Tổ chức có nhà đầu tư Mỹ hay không?
[X] Không
(Trường hợp Tổ chức là tổ chức phi tài chính thụ động nước ngoài có từ 1 cá nhân Hoa Kỳ sở hữu trên 25% vốn hoặc quyền biểu quyết, vui lòng cung cấp Mẫu W-8BEN-E)

PHẦN B – ĐĂNG KÝ MỞ TÀI KHOẢN THANH TOÁN
I. MỞ TÀI KHOẢN THANH TOÁN
Loại tài khoản: [X] Tài khoản thanh toán thông thường   [ ] Khác (ghi rõ loại tài khoản):
Loại tiền: [X] VND   [ ] USD   [ ] Ngoại tệ khác (ghi rõ loại tiền tệ):
Tên tài khoản:
Dịch vụ mở tài khoản theo yêu cầu:
Số tài khoản theo yêu cầu:
Mức phí:
Hình thức thu phí: [ ] Tiền mặt   [ ] Trích nợ từ tài khoản số:

II. ĐĂNG KÝ DỊCH VỤ ĐI KÈM TÀI KHOẢN THANH TOÁN
1. Dịch vụ sổ phụ tài khoản
- Tài khoản đăng ký nhận sổ phụ:
- Tài khoản thu phí nhận sổ phụ:
- Tần suất thu phí: [ ] Thu ngay khi hoàn thành dịch vụ   [ ] Thu vào ngày cố định hàng tháng
- Tần suất nhận sổ phụ: [ ] Ngày   [X] Tuần   [ ] Tháng   [ ] Quý   [ ] Năm
- Chứng từ đăng ký nhận: [X] Sao kê tài khoản   [ ] Sao kê tài khoản kèm báo nợ, báo có
- Hình thức nhận sổ phụ:
  [ ] Tại BIDV
  [ ] Qua Swift, mã Swiftcode:       Hình thức: [ ] Fin [ ] Fileact [ ] Gửi Swift3
  [ ] Thư điện tử, địa chỉ email nhận:
  [X] Qua bưu điện, địa chỉ nhận: Tầng 2, Số 6 Lê Văn Thiêm, Phường Thanh Xuân, TP Hà Nội
- Định dạng sổ phụ (áp dụng nếu khách hàng lựa chọn hình thức nhận qua Swift hoặc Thư điện tử):
  [X] Excel   [ ] PDF   [ ] MT940   [ ] MT942   [ ] MT950   [ ] Camt.052   [ ] Camt.053

2. Dịch vụ hóa đơn điện tử
- Email đăng ký nhận hóa đơn điện tử: BIDV gửi hóa đơn điện tử đến địa chỉ email đại diện của Tổ chức đã đăng ký tại BIDV trong Thỏa thuận này hoặc các thỏa thuận mở và sử dụng tài khoản trước đó."""
    },
    {
        "filename": "793677619_1619023852911289_4239943537032754761_n.jpg",
        "output_format": "excel",
        "output_name": "793677619_danh_muc_ban_ve.xlsx",
        "document_type": "table",
        "vision_fields": {
            "title": "DANH MỤC BẢN VẼ",
            "section": "KÝ HIỆU TẦNG 2",
            "total_items": 11,
            "project_code": "AMY"
        },
        "vision_text": """DANH MỤC BẢN VẼ

STT | TÊN BẢN VẼ | KÝ HIỆU TẦNG 2 | GHI CHÚ
1 | MẶT BẰNG NỘI THẤT HIỆN TRẠNG | AMY - 01 |
2 | MẶT BẰNG NỘI THẤT THAY ĐỔI | AMY - 02 |
3 | KÝ HIỆU BẢN VẼ | AMY - 03 |
4 | MẶT BẰNG LỘ ĐIỆN Ổ CẮM HIỆN TRẠNG | AMY - 04 |
5 | MẶT BẰNG CẤP ĐIỆN Ổ CẮM HIỆN TRẠNG | AMY - 05 |
6 | MẶT BẰNG CẤP ĐIỆN Ổ CẮM THAY ĐỔI | AMY - 06 |
7 | MẶT BẰNG ĐIỆN NHẸ ( MẠNG LAN) | AMY - 07 |
8 | MẶT BẰNG TRẦN HIỆN TRẠNG | AMY - 08 | GIỮ NGUYÊN HIỆN TRẠNG
9 | MẶT BẰNG CẤP ĐIỆN CHIẾU SÁNG HIỆN TRẠNG | AMY - 09 | GIỮ NGUYÊN HIỆN TRẠNG
10 | SƠ ĐỒ NGUYÊN LÝ ĐIỆN. | AMY - 10 | GIỮ NGUYÊN HIỆN TRẠNG
11 | SƠ ĐỒ NGUYÊN LÝ ĐIỆN NHẸ. | AMY - 11 |"""
    },
    {
        "filename": "795383590_1554212246505093_793601746291026735_n.jpg",
        "output_format": "word",
        "output_name": "795383590_tai_lieu_chuc_nang_giam_sat.docx",
        "document_type": "report",
        "vision_fields": {
            "title": "TÀI LIỆU KỸ THUẬT MÔ TẢ CHỨC NĂNG HỆ THỐNG IOCD",
            "page": "Trang 5",
            "group": "A. NHÓM CHỨC NĂNG GIÁM SÁT",
            "subgroup": "1. Tổng quan"
        },
        "vision_text": """Chức năng hỗ trợ người dùng chủ động cập nhật mật khẩu mới, giúp bảo vệ tài khoản và hạn chế nguy cơ truy cập trái phép. Sau khi thay đổi thành công, người dùng sử dụng mật khẩu mới cho các lần đăng nhập tiếp theo.
- Chức năng Đăng xuất (5)
Người dùng có thể truy cập các chế độ màn hình hiển thị chính gồm:
- Tổng quan: Cung cấp các dữ liệu và số liệu thống kê hữu ích về tình trạng hệ thống, Camera, AI Node, sự kiện và cảnh báo, giúp người dùng nhanh chóng nắm bắt tình hình hoạt động, theo dõi các dữ liệu quan trọng và xác định những vấn đề cần kiểm tra hoặc xử lý. (6)
- Maps & Event: Cho phép theo dõi các sự kiện phát sinh trên bản đồ, xem danh sách và thông tin chi tiết của từng sự kiện. Khi phát hiện sự kiện, Trên bản đồ Camera tại vị trí xảy ra sẽ chớp nháy cảnh báo theo thời gian thực, giúp người dùng nhanh chóng nhận biết, xác định vị trí và theo dõi thông tin chi tiết của sự kiện. (7)
- Maps & Event & Dashboard: kết hợp hai giao diện Maps & Event và Tổng quan trên cùng một màn hình, cho phép người dùng đồng thời theo dõi vị trí, sự kiện trên bản đồ và các dữ liệu thống kê tổng quan. Chức năng này giúp tối ưu số lượng màn hình cần sử dụng trong quá trình giám sát, đặc biệt phù hợp với các cơ sở có quy mô nhỏ. (8)
- Live Wall: Hỗ trợ giám sát hình ảnh trực tiếp từ các Camera, quản lý bố cục hiển thị và điều khiển Camera. Giúp người dùng quan sát trực tiếp nhiều khu vực, dễ dàng tập trung vào khu vực cần theo dõi và thuận tiện trong quá trình giám sát (9)
- Quản trị: Cung cấp các chức năng quản lý hệ thống như Hệ thống, Vai trò, Phân quyền, Người dùng. Giúp người dùng thiết lập quyền truy cập, quản lý tài khoản người dùng, theo dõi hoạt động và duy trì hệ thống hoạt động phù hợp với nhu cầu sử dụng. (10)

A. NHÓM CHỨC NĂNG GIÁM SÁT
1. Tổng quan
Màn hình này cung cấp giao diện theo dõi tập trung tình trạng hoạt động của hệ thống IOCD, giúp người dùng nhanh chóng nắm bắt tình trạng Camera, AI Node, sự kiện và cảnh báo trong hệ thống.

Trang 5"""
    },
    {
        "filename": "796094942_2552429128591060_2224561930899481040_n.jpg",
        "output_format": "word",
        "output_name": "796094942_huong_dan_cau_hinh_ivms.docx",
        "document_type": "report",
        "vision_fields": {
            "title": "HƯỚNG DẪN CẤU HÌNH HỆ THỐNG IVMS VÀ MEDIAMTX",
            "page": "Trang 30",
            "module": "2. IVMS",
            "webrtc_port": "8889",
            "rtsp_port": "8554"
        },
        "vision_text": """QUẢN TRỊ HỆ THỐNG
Cấu hình IMAS:
- Dán từ clipboard IMAS
- IMAS API URL: http://10.0.6.240:8888
- Client ID: iocd-prod-20260729
- Sync Interval (phút): 2
- Tự động đồng bộ mỗi 2 phút

2. IVMS
IVMS giúp người dùng cấu hình kết nối đến hệ thống MediaMTX để IOCD có thể nhận và hiển thị luồng Camera.
Người dùng cần cung cấp địa chỉ IP/Host của hệ thống IVMS và các Port kết nối WebRTC, RTSP. Trường hợp thay đổi Port, cần cập nhật lại Port tương ứng để đảm bảo IOCD kết nối và hiển thị được luồng Camera.

Cấu hình IVMS:
- Host + Cổng WebRTC/RTSP cho MediaMTX. Camera ID lấy tự động từ IMAS
- Host: 27.72.31.105
- WebRTC Port: 8889
- RTSP Port: 8554
- WHEP: http://27.72.31.105:8889/streamid/whep
- RTSP: rtsp://27.72.31.105:8554/streamid

Trang 30"""
    },
    {
        "filename": "796267468_3110563505800281_1829766512603325863_n.jpg",
        "output_format": "word",
        "output_name": "796267468_hop_dong_lao_dong.docx",
        "document_type": "contract",
        "vision_fields": {
            "title": "HỢP ĐỒNG LAO ĐỘNG",
            "document_number": "Số: 01/2026/HĐLĐ.CV/SĐT-KhanhHD",
            "effective_date": "01/07/2026",
            "party_a_company": "CÔNG TY CỔ PHẦN GIẢI PHÁP CHUYỂN ĐỔI SỐ THÔNG MINH",
            "party_a_representative": "Ông Trịnh Vũ Hoàng - Chức vụ: Tổng Giám đốc",
            "party_a_phone": "0901.876.888",
            "party_a_tax_id": "0108 486 875",
            "party_b_name": "HOÀNG ĐỨC KHÁNH",
            "party_b_cccd": "066204009237",
            "party_b_dob": "08/05/2004",
            "party_b_specialty": "Trí tuệ nhân tạo (AI) - Trường Đại học FPT HCM"
        },
        "vision_text": """CỘNG HOÀ XÃ HỘI CHỦ NGHĨA VIỆT NAM
Độc lập - Tự do - Hạnh phúc
---o0o---

CÔNG TY CP GIẢI PHÁP CHUYỂN ĐỔI SỐ THÔNG MINH
--- SĐT solution AI ---

HỢP ĐỒNG LAO ĐỘNG
(Số: 01/2026/HĐLĐ.CV/SĐT-KhanhHD)

- Căn cứ Bộ luật Lao động số 45/2019/QH14 ngày 20/11/2019 của Quốc hội nước Cộng hoà Xã hội chủ nghĩa Việt Nam;
- Căn cứ Bộ luật Dân sự số 91/2015/QH13 ngày 24/11/2015 của Quốc hội nước Cộng hoà Xã hội chủ nghĩa Việt Nam;
- Căn cứ vào nhu cầu sử dụng lao động của Công ty Cổ phần Giải pháp Chuyển đổi số thông minh và nguyện vọng của ông Hoàng Đức Khánh;
- Căn cứ vào khả năng và trình độ chuyên môn của ông Hoàng Đức Khánh;

Hôm nay, ngày 01 tháng 07 năm 2026, tại Trụ sở văn phòng Công ty cổ phần Giải pháp Chuyển đổi số Thông minh chúng tôi gồm:

BÊN A: BÊN SỬ DỤNG LAO ĐỘNG
- Tên Đơn vị: CÔNG TY CỔ PHẦN GIẢI PHÁP CHUYỂN ĐỔI SỐ THÔNG MINH
- Đ/c ĐKKD: Số 9 ngõ 2 ngách 3, phố Phú Đô, phường Từ Liêm, TP. Hà Nội, Việt Nam
- Văn phòng:
- Đại diện: Ông TRỊNH VŨ HOÀNG      Chức vụ: Tổng Giám đốc
- Điện thoại: 0901.876.888           Đ/c email: hoangtrinh@sdt.ai.vn
- Mã số thuế: 0108 486 875
- Tài khoản: 26969 8888 8869        Ngân hàng: TMCP Quân Đội (MBBank) – CN Hà Nội, PGD Trung Văn
(Sau đây gọi tắt là: “Bên SDLĐ” hoặc “Công ty”)

BÊN B: NGƯỜI LAO ĐỘNG
- Họ và tên: HOÀNG ĐỨC KHÁNH        Giới tính: Nam
- Số CCCD: 066204009237             Ngày cấp: 31/05/2021   Nơi cấp: Cục CS QLHC về TTXH
- Ngày sinh: 08/05/2004             Dân tộc: Kinh          Tôn giáo: Không
- Quốc tịch: Việt Nam               Quê quán: Đắk Lắk
- Địa chỉ thường trú: Tân Hà, Ea Toh, Krông Năng, Đắk Lắk
- Học vấn: Cử nhân                  Chuyên môn: Trí tuệ nhân tạo (AI)
- Tốt nghiệp: Năm 2026              Trường: Trường Đại học FPT HCM
- Điện thoại: 0394363168            Đ/c email: KhanhHD.sdt.ai@gmail.com
- Tài khoản: 73308052004            Ngân hàng: TP Bank
(Sau đây gọi tắt là: “NLĐ”)

Bên Sử dụng lao động và Người lao động (sau đây gọi tắt là “hai Bên” hoặc “các Bên”) thỏa thuận ký kết Hợp đồng lao động và cam kết thực hiện đúng những điều khoản sau đây:"""
    },
    {
        "filename": "798077370_1107286892154265_4334086035087611815_n.jpg",
        "output_format": "word",
        "output_name": "798077370_thong_tin_uy_quyen_bidv.docx",
        "document_type": "form",
        "vision_fields": {
            "title": "THÔNG TIN NGƯỜI ĐƯỢC ỦY QUYỀN VÀ GIỚI THIỆU GIAO DỊCH",
            "document_number": "BM01-TC/TTKH&DVTK/02/2025",
            "page": "Trang số: 6",
            "issuing_authority": "Ngân hàng TMCP Đầu tư và Phát triển Việt Nam (BIDV)",
            "dob": "22/09/1984",
            "gender": "Nữ",
            "id_number": "001184008447",
            "issue_date": "26/07/2024",
            "expiry_date": "22/09/2044",
            "issue_place": "Cục CS QLHC về TTXH",
            "address": "P14-B12 T/T Đhgt, Ngọc Khánh, Ba Đình, Hà Nội"
        },
        "vision_text": """Ngày, tháng, năm sinh*: 22/09/1984      [ ] Nam   [X] Nữ
Quốc tịch*: Việt Nam   Quốc tịch thứ 2:
[X] Người cư trú   [ ] Người không cư trú
Số định danh cá nhân*: 001184008447
[X] CCCD   [ ] Thẻ căn cước   [ ] Hộ chiếu   Số*:
Ngày cấp*: 26/07/2024   Có giá trị đến ngày*: 22/09/2044
Nơi cấp*: Cục CS QLHC về TTXH
Số thị thực nhập cảnh hoặc số giấy tờ thay thị thực nhập cảnh (đối với người nước ngoài cư trú tại Việt Nam), trừ trường hợp được miễn thị thực theo quy định pháp luật*:
Ngày cấp*:   Có giá trị đến ngày*:
Số điện thoại liên lạc*:
Địa chỉ thư điện tử*:
Địa chỉ* (KH kê khai phù hợp với quốc tịch và tình trạng cư trú thực tế): P14-B12 T/T Đhgt, Ngọc Khánh, Ba Đình, Hà Nội
- Thường trú tại Việt Nam:
- Đăng ký cư trú tại Việt Nam:
- Cư trú ở nước ngoài:

Bằng việc ký vào ô chữ ký mẫu này, Tôi xác nhận đã đọc, hiểu rõ các quyền và nghĩa vụ của Tôi với tư cách Chủ thể dữ liệu cá nhân theo quy định Pháp luật về bảo vệ dữ liệu cá nhân. Tôi đồng ý cho phép BIDV xử lý toàn bộ dữ liệu cá nhân của Tôi và đồng ý với Bản Điều khoản và điều kiện chung của BIDV về bảo vệ và xử lý dữ liệu cá nhân được đăng tải trên trang điện tử chính thức của BIDV https://bidv.com.vn/vn/an-toan-bao-mat, phần Bảo vệ dữ liệu cá nhân.

Chữ ký mẫu thứ 1: [Đã ký]
Chữ ký mẫu thứ 2: [Đã ký]

4. Người được ủy quyền Kế toán trưởng/ Người phụ trách kế toán:
[ ] Đăng ký mới
[ ] Cập nhật thông tin Ông/Bà:
[ ] Thay đổi Người được ủy quyền Kế toán trưởng/ Người phụ trách kế toán:
- Hủy đăng ký: Ông/Bà
- Bổ sung: Ông/Bà
Họ và tên*:               Mã số thuế:
Nghề nghiệp*:             Chức vụ*:
Ngày, tháng, năm sinh*:   [ ] Nam   [ ] Nữ
Quốc tịch*:               Quốc tịch thứ 2:
[ ] Người cư trú         [ ] Người không cư trú
Số định danh cá nhân*:    [ ] CCCD   [ ] Thẻ căn cước   [ ] Hộ chiếu   Số*:
Ngày cấp*:                Có giá trị đến ngày*:              Nơi cấp*:

5. Người được Tổ chức giới thiệu giao dịch:
Họ và tên*:               Mã số thuế:
Nghề nghiệp*:             Chức vụ*:

Mã hiệu: BM01-TC/TTKH&DVTK/02/2025
Trang số: 6"""
    }
]


def call_mcp_ocr(item, req_id):
    source_path = str(PICS_DIR / item["filename"])
    output_path = str(RESULT_DIR / item["output_name"])
    
    payload = {
        "jsonrpc": "2.0",
        "id": req_id,
        "method": "tools/call",
        "params": {
            "name": "ocr_map_export",
            "arguments": {
                "source_path": source_path,
                "output_format": item["output_format"],
                "output_path": output_path,
                "document_type": item["document_type"],
                "vision_text": item["vision_text"],
                "vision_fields": item["vision_fields"]
            }
        }
    }
    
    data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    req = urllib.request.Request(
        MCP_URL,
        data=data,
        headers={"Content-Type": "application/json; charset=utf-8"},
        method="POST"
    )
    
    with urllib.request.urlopen(req) as resp:
        body = resp.read().decode("utf-8")
        return json.loads(body)


def main():
    print(f"Bắt đầu xử lý {len(ITEMS)} ảnh qua MCP server: {MCP_URL}")
    results = []
    
    for idx, item in enumerate(ITEMS, 1):
        filename = item["filename"]
        outname = item["output_name"]
        print(f"\n[{idx}/{len(ITEMS)}] Đang xử lý: {filename} -> {outname} ({item['output_format']})...")
        try:
            resp = call_mcp_ocr(item, idx)
            content_json = resp.get("result", {}).get("content", [{}])[0].get("text", "{}")
            result_data = json.loads(content_json)
            success = result_data.get("success", False)
            out_file = result_data.get("output_path", "")
            size = os.path.getsize(out_file) if os.path.exists(out_file) else 0
            print(f"  ==> Thành công: {success} | Kích thước: {size} bytes | Output: {out_file}")
            results.append({
                "file": filename,
                "output": outname,
                "format": item["output_format"],
                "success": success,
                "size": size,
                "confidence": result_data.get("confidence"),
                "status": result_data.get("status")
            })
        except Exception as e:
            print(f"  ==> Lỗi: {e}")
            results.append({
                "file": filename,
                "output": outname,
                "format": item["output_format"],
                "success": False,
                "error": str(e)
            })

    print("\n" + "="*50)
    print("TỔNG HỢP KẾT QUẢ XỬ LÝ MCP OCR:")
    for r in results:
        status_str = "OK" if r.get("success") else "FAILED"
        print(f"- {r['file']} => {r['output']} [{status_str}, {r.get('size', 0)} bytes]")


if __name__ == "__main__":
    main()
