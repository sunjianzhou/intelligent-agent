"""Tests for RoleManager CRUD, memory writes, and get_role_context."""
import sys
import os
import json
import pytest
from unittest.mock import MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from personas.role_models import RoleConfig, RoleCard, CoreIdentity, Commitment
from personas.role_manager import RoleManager


# ── Fixtures ──────────────────────────────────────────────────────────────────

@pytest.fixture
def rm(tmp_path):
    """RoleManager pointing at tmp_path with mocked ChromaDB + embedding."""
    mock_chroma = MagicMock()
    mock_collection = MagicMock()
    mock_collection.query.return_value = {"documents": [[]], "metadatas": [[]]}
    mock_chroma.get_or_create_collection.return_value = mock_collection

    mock_embed_fn = MagicMock()

    with patch("personas.role_manager.chromadb.PersistentClient", return_value=mock_chroma), \
         patch("personas.role_manager.embedding_functions.SentenceTransformerEmbeddingFunction",
               return_value=mock_embed_fn):
        mgr = RoleManager(
            roles_dir=str(tmp_path / "roles"),
            chroma_dir=str(tmp_path / "chroma"),
        )
    mgr._chroma = mock_chroma
    return mgr


# ── Embedding fallback ───────────────────────────────────────────────────────

def test_init_falls_back_to_chroma_embedding_when_sentence_transformers_missing(tmp_path):
    """Docker 镜像不装 sentence_transformers（~1GB）时，应降级到 chromadb 内置
    DefaultEmbeddingFunction，而不是抛 ValueError 崩掉（与 memory/long_term.py 一致）。"""
    mock_chroma = MagicMock()
    mock_default_ef = MagicMock()

    with patch("personas.role_manager.chromadb.PersistentClient", return_value=mock_chroma), \
         patch.dict(sys.modules, {"sentence_transformers": None}), \
         patch("personas.role_manager.embedding_functions.DefaultEmbeddingFunction",
               return_value=mock_default_ef) as mock_default_cls:
        mgr = RoleManager(
            roles_dir=str(tmp_path / "roles"),
            chroma_dir=str(tmp_path / "chroma"),
        )

    mock_default_cls.assert_called_once()
    assert mgr._embed_fn is mock_default_ef


def _make_role(role_id="role_test", name="Tester") -> RoleConfig:
    return RoleConfig(
        role_id=role_id,
        role_card=RoleCard(name=name, signature="just a test"),
        core_identity=CoreIdentity(personality=["friendly"], redlines=["no harm"]),
    )


# ── CRUD ─────────────────────────────────────────────────────────────────────

def test_save_and_load_role(rm, tmp_path):
    role = _make_role()
    rm.save_role(role)
    loaded = rm.load_role("role_test")
    assert loaded is not None
    assert loaded.role_id == "role_test"
    assert loaded.role_card.name == "Tester"


def test_load_missing_role_returns_none(rm):
    result = rm.load_role("nonexistent")
    assert result is None


def test_list_roles_empty(rm):
    assert rm.list_roles() == []


def test_list_roles_after_save(rm):
    rm.save_role(_make_role("r1"))
    rm.save_role(_make_role("r2"))
    ids = rm.list_roles()
    assert set(ids) == {"r1", "r2"}


def test_list_role_cards_returns_card_fields(rm):
    rm.save_role(_make_role("r1", "Alpha"))
    rm.save_role(_make_role("r2", "Beta"))
    cards = rm.list_role_cards()
    assert len(cards) == 2
    names_in_cards = {c["role_card"].get("name") for c in cards}
    assert "Alpha" in names_in_cards
    assert "Beta" in names_in_cards
    # 不含 core_identity 等完整字段
    for c in cards:
        assert set(c.keys()) == {"role_id", "role_card"}


def test_delete_role_existing(rm):
    rm.save_role(_make_role())
    deleted = rm.delete_role("role_test")
    assert deleted is True
    assert rm.load_role("role_test") is None


def test_delete_role_missing(rm):
    deleted = rm.delete_role("ghost")
    assert deleted is False


def test_delete_cleans_short_term_cache(rm):
    rm.save_role(_make_role())
    rm.add_memory("role_test", "hello", memory_type="short_term")
    assert "role_test" in rm._short_term_cache
    rm.delete_role("role_test")
    assert "role_test" not in rm._short_term_cache


def test_save_overwrites_existing(rm):
    rm.save_role(_make_role("r1", "Original"))
    role2 = _make_role("r1", "Updated")
    rm.save_role(role2)
    loaded = rm.load_role("r1")
    assert loaded.role_card.name == "Updated"


# ── add_memory ────────────────────────────────────────────────────────────────

def test_add_short_term_memory(rm):
    rm.save_role(_make_role())
    rm.add_memory("role_test", "first memory", memory_type="short_term")
    rm.add_memory("role_test", "second memory", memory_type="short_term")
    cache = list(rm._short_term_cache["role_test"])
    assert "first memory" in cache
    assert "second memory" in cache


def test_add_long_term_memory_calls_chroma(rm):
    rm.save_role(_make_role())
    rm.add_memory("role_test", "important fact", memory_type="long_term")
    # ChromaDB collection.add should have been called
    rm._chroma.get_or_create_collection.assert_called()
    mock_coll = rm._chroma.get_or_create_collection.return_value
    mock_coll.add.assert_called_once()
    call_kwargs = mock_coll.add.call_args
    assert "important fact" in call_kwargs.kwargs.get("documents", [])


def test_short_term_deque_respects_max_size(rm):
    role = _make_role()
    role.role_memory.short_term_size = 3
    rm.save_role(role)
    for i in range(5):
        rm.add_memory("role_test", f"mem{i}", memory_type="short_term")
    cache = list(rm._short_term_cache["role_test"])
    assert len(cache) == 3
    assert "mem4" in cache  # 最新的在
    assert "mem0" not in cache  # 最早的被挤出


# ── add_commitment ────────────────────────────────────────────────────────────

def test_add_commitment_persists(rm):
    rm.save_role(_make_role())
    rm.add_commitment("role_test", "每天问好")
    loaded = rm.load_role("role_test")
    texts = [c.content for c in loaded.role_memory.commitments]
    assert "每天问好" in texts


def test_add_commitment_missing_role_returns_none(rm):
    result = rm.add_commitment("ghost", "something")
    assert result is None


# ── get_role_context ──────────────────────────────────────────────────────────

def test_get_role_context_missing_role_returns_empty(rm):
    ctx = rm.get_role_context("ghost", "hello")
    assert ctx == {}


def test_get_role_context_structure(rm, monkeypatch):
    rm.save_role(_make_role())
    # mock _semantic_search to avoid actual embedding
    monkeypatch.setattr(rm, "_semantic_search",
                        lambda *a, **kw: [f"result_for_{a[0]}"])
    ctx = rm.get_role_context("role_test", "test query")
    assert "role_config" in ctx
    assert "short_term_memories" in ctx
    assert "long_term_memories" in ctx
    assert "journal_snippets" in ctx
    assert "knowledge_snippets" in ctx
    assert "query" in ctx
    assert ctx["query"] == "test query"


def test_get_role_context_uses_parallel_search(rm, monkeypatch):
    rm.save_role(_make_role())
    calls = []

    def mock_search(coll_name, query, top_k, threshold):
        calls.append(coll_name)
        return [f"from_{coll_name}"]

    monkeypatch.setattr(rm, "_semantic_search", mock_search)
    ctx = rm.get_role_context("role_test", "query")
    # 三个 collection 都被查询
    assert any("_memory" in c for c in calls)
    assert any("_journal" in c for c in calls)
    assert any("_knowledge" in c for c in calls)


def test_get_role_context_returns_short_term_from_cache(rm, monkeypatch):
    rm.save_role(_make_role())
    rm.add_memory("role_test", "cached mem", memory_type="short_term")
    monkeypatch.setattr(rm, "_semantic_search", lambda *a, **kw: [])
    ctx = rm.get_role_context("role_test", "q")
    assert "cached mem" in ctx["short_term_memories"]
