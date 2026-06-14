"""E2E: 工具列表。"""


def test_tools_list(client):
    r = client.get("/api/tools/list")
    assert r.status_code == 200
    data = r.json()
    assert "tools" in data
    tools = data["tools"]
    assert isinstance(tools, list)
    assert len(tools) > 0

    # 每个工具应有 name 和 description
    for tool in tools:
        assert "name" in tool, f"工具缺少 name: {tool}"
