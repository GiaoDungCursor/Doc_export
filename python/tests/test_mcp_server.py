import unittest

import mcp_server


class McpServerTest(unittest.TestCase):
    def test_initialize_and_tool_listing(self):
        initialized = mcp_server.handle_request({
            "jsonrpc": "2.0", "id": 1, "method": "initialize",
            "params": {"protocolVersion": "2024-11-05"},
        })
        self.assertEqual("office-studio-ai", initialized["result"]["serverInfo"]["name"])
        listed = mcp_server.handle_request({"jsonrpc": "2.0", "id": 2, "method": "tools/list"})
        names = {tool["name"] for tool in listed["result"]["tools"]}
        self.assertIn("ocr_map_export", names)
        schema = next(tool for tool in listed["result"]["tools"] if tool["name"] == "ocr_map_export")
        self.assertIn("vision_text", schema["inputSchema"]["properties"])

    def test_vietnamese_official_document_recommends_official_template(self):
        document = {
            "document_type": "generic",
            "raw_text": "CỘNG HÒA XÃ HỘI CHỦ NGHĨA VIỆT NAM\nSố: 12/TANDTC-PC\nV/v thông báo\nKính gửi:",
        }
        selected, _ = mcp_server.recommend_template(document, "word")
        self.assertIsNotNone(selected)
        self.assertIn("cong van", mcp_server._normalize(selected["name"]))


if __name__ == "__main__":
    unittest.main()
