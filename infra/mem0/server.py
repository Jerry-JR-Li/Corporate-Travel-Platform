"""Small self-hosted HTTP adapter around the real Mem0 library; matches AgentScope endpoints."""
import os
import secrets
from functools import lru_cache
from typing import Literal
from fastapi import FastAPI, Depends, Header, HTTPException
from pydantic import BaseModel, Field
from mem0 import Memory

app = FastAPI(title="Corporate Travel Mem0")

def authorize(x_api_key: str = Header(default="")):
    expected = os.environ.get("MEM0_API_KEY", "")
    if not expected or not secrets.compare_digest(expected, x_api_key):
        raise HTTPException(401, "Invalid Mem0 API key")

@lru_cache(maxsize=1)
def memory():
    key = os.environ["DASHSCOPE_API_KEY"]
    base = os.getenv("MEM0_LLM_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1")
    return Memory.from_config({
        "version": "v1.1",
        "history_db_path": os.getenv("MEM0_HISTORY_PATH", "/data/history.db"),
        "llm": {"provider": "openai", "config": {
            "api_key": key, "openai_base_url": base,
            "model": os.getenv("MEM0_LLM_MODEL", "qwen-flash"), "temperature": 0.1}},
        "embedder": {"provider": "openai", "config": {
            "api_key": key, "openai_base_url": os.getenv("MEM0_EMBEDDING_BASE_URL", base),
            "model": os.getenv("MEM0_EMBEDDING_MODEL", "text-embedding-v4"), "embedding_dims": 1024}},
        "vector_store": {"provider": "pgvector", "config": {
            "host": os.getenv("MEM0_PG_HOST", "127.0.0.1"),
            "port": int(os.getenv("MEM0_PG_PORT", "5432")),
            "dbname": os.getenv("MEM0_PG_DATABASE", "corporate_travel"),
            "user": os.getenv("MEM0_PG_USER", "corporate_travel"),
            "password": os.environ["PGVECTOR_PASSWORD"],
            "collection_name": "travel_memories", "embedding_model_dims": 1024}}
    })

class Message(BaseModel):
    role: Literal["user", "assistant", "system"]
    content: str = Field(max_length=20000)
class AddRequest(BaseModel):
    messages: list[Message] = Field(min_length=1, max_length=50)
    user_id: str = Field(min_length=1, max_length=128)
    agent_id: str = Field(default="corporate-travel-platform", min_length=1, max_length=128)
    metadata: dict | None = None
    infer: bool = True
class SearchRequest(BaseModel):
    query: str = Field(min_length=1, max_length=10000)
    filters: dict
    top_k: int = Field(default=5, ge=1, le=20)

@app.get("/health")
def health():
    return {"status": "UP"}

@app.post("/memories", dependencies=[Depends(authorize)])
def add(body: AddRequest):
    return memory().add([m.model_dump() for m in body.messages], user_id=body.user_id,
                        agent_id=body.agent_id, metadata=body.metadata, infer=body.infer)

@app.post("/search", dependencies=[Depends(authorize)])
def search(body: SearchRequest):
    # Reject unscoped/compound filters: every caller must specify both user and project.
    if set(body.filters) != {"user_id", "agent_id"} or not all(isinstance(v,str) and v for v in body.filters.values()):
        raise HTTPException(422, "user_id and agent_id filters are required")
    return memory().search(body.query, filters=body.filters, limit=body.top_k)
