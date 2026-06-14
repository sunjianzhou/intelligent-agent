"""E2E: Skill 管理 — 列表 / 创建 / 更新 / toggle / 删除 / 模板。

Skill 字段说明（Python 侧）：
  id, name, description, trigger_keywords, tool_hints, forced_tools,
  scenario_tags, overall_strategy, steps, enabled
无 tags / content 字段。
"""
import pytest


def _extract_skill_id(data: dict) -> str:
    return (data.get("id") or data.get("skill_id")
            or data.get("skill", {}).get("id") or "")


def test_skills_list(client):
    r = client.get("/api/skills")
    assert r.status_code == 200
    data = r.json()
    assert "skills" in data
    assert isinstance(data["skills"], list)


def test_skill_templates(client):
    r = client.get("/api/skills/templates/list")
    assert r.status_code == 200
    data = r.json()
    assert "templates" in data or isinstance(data, dict)


def test_skill_crud(client):
    payload = {
        "name": "E2E测试Skill",
        "description": "由E2E测试创建，可安全删除",
        "trigger_keywords": ["e2e", "test"],
        "enabled": True,
    }
    # 创建
    r = client.post("/api/skills", json=payload)
    assert r.status_code == 200
    data = r.json()
    assert data.get("success") is True, f"创建失败: {data}"
    skill_id = _extract_skill_id(data)
    assert skill_id, f"创建未返回 id: {data}"

    # 更新
    r2 = client.put(f"/api/skills/{skill_id}", json={**payload, "description": "updated by E2E"})
    assert r2.status_code == 200

    # Toggle
    r3 = client.patch(f"/api/skills/{skill_id}/toggle")
    assert r3.status_code == 200

    # 删除
    r4 = client.delete(f"/api/skills/{skill_id}")
    assert r4.status_code == 200
    assert r4.json().get("success") is not False


def test_skills_filter_by_tag(client):
    r = client.get("/api/skills?tag=test")
    assert r.status_code == 200


def test_skills_enabled_only(client):
    r = client.get("/api/skills?enabled_only=true")
    assert r.status_code == 200
    skills = r.json().get("skills", [])
    for s in skills:
        assert s.get("enabled") is True
