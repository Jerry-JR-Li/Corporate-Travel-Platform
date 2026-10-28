"""HTTP adapter regression tests; no model/database/network calls."""
import os
import unittest
from unittest.mock import Mock, patch
from fastapi.testclient import TestClient
from server import app

class Mem0ServerTest(unittest.TestCase):
    def setUp(self):
        self.env = patch.dict(os.environ, {"MEM0_API_KEY": "contract-test-key"})
        self.env.start()
        self.client = TestClient(app)
        self.headers = {"X-API-Key": "contract-test-key"}
    def tearDown(self):
        self.client.close()
        self.env.stop()
    def test_auth_and_mandatory_user_project_scope(self):
        body = {"query": "seat", "filters": {"user_id": "alice", "agent_id": "travel"}}
        self.assertEqual(401, self.client.post("/search", json=body).status_code)
        for filters in [{}, {"user_id": "alice"}, {"user_id": "alice", "agent_id": {"eq": "travel"}}]:
            self.assertEqual(422, self.client.post("/search", headers=self.headers, json={**body, "filters": filters}).status_code)
    def test_search_uses_mem0_v2_filters_and_preserves_scoping(self):
        backend = Mock()
        backend.search.return_value = {"results": [{"id": "1", "memory": "window seat"}]}
        with patch("server.memory", return_value=backend):
            response = self.client.post("/search", headers=self.headers,
                json={"query": "seat", "filters": {"user_id": "alice", "agent_id": "travel"}, "top_k": 8})
        self.assertEqual(200, response.status_code)
        backend.search.assert_called_once_with("seat", filters={"user_id": "alice", "agent_id": "travel"}, limit=8)
    def test_add_retains_inference_and_user_project_ids(self):
        backend = Mock()
        backend.add.return_value = {"results": []}
        with patch("server.memory", return_value=backend):
            response = self.client.post("/memories", headers=self.headers, json={
                "messages": [{"role": "user", "content": "window seat"}], "user_id": "alice", "agent_id": "travel"})
        self.assertEqual(200, response.status_code)
        backend.add.assert_called_once_with([{"role": "user", "content": "window seat"}], user_id="alice", agent_id="travel", metadata=None, infer=True)

if __name__ == "__main__":
    unittest.main()
